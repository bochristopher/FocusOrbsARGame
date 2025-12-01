package com.example.focusorbsargame

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.focusorbsargame.databinding.ActivityFocusOrbsBinding
import kotlin.math.hypot
import kotlin.random.Random

/**
 * FocusOrbsActivity - Core game loop for the Focus Orbs AR game.
 *
 * Step 2 Implementation:
 * - Head tilt (device orientation) controls the virtual aim point
 * - The reticle stays visually fixed at center
 * - Internally, aim offset is computed from pitch/roll deltas
 * - Basic difficulty system: levels increase every 5 pops
 *
 * Game mechanics:
 * - A reticle is fixed at the center of the screen (visual only)
 * - An orb spawns at a random position and stays there
 * - Player tilts head to move the virtual "aim point"
 * - When aim point is close to orb for required duration, orb "pops"
 * - Score increases, difficulty ramps up, new orb spawns
 */
class FocusOrbsActivity : AppCompatActivity(), Choreographer.FrameCallback {

    // ==================== View Binding ====================
    private lateinit var binding: ActivityFocusOrbsBinding

    // ==================== Game State ====================
    private var score: Int = 0
    private var level: Int = 1
    private var isGameRunning: Boolean = false

    // ==================== Orb Position ====================
    // Current orb position (center of the orb in screen coordinates)
    // In Step 2: Orb stays fixed after spawning; player moves aim point via head tilt
    private var orbX: Float = 0f
    private var orbY: Float = 0f

    // ==================== Difficulty Parameters ====================
    // These are adjusted based on current level
    private var captureRadius: Float = BASE_CAPTURE_RADIUS
    private var requiredFocusTime: Float = BASE_FOCUS_TIME

    // ==================== Focus/Alignment Tracking ====================
    // Accumulated focus time while aim point is within capture radius of orb
    private var currentFocusTime: Float = 0f

    // ==================== Slider Touch Control ====================
    // Virtual aim offset from center (controlled by slider touch)
    // This represents how far the aim point has moved from screen center
    private var aimOffsetX: Float = 0f
    private var aimOffsetY: Float = 0f

    // Touch tracking for slider
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var isTouching: Boolean = false

    // Sensitivity: how many pixels to move aim per pixel of touch movement
    private val sliderSensitivityX: Float = 3.0f  // Horizontal sensitivity
    private val sliderSensitivityY: Float = 3.0f  // Vertical sensitivity (if slider supports it)

    // ==================== Timing ====================
    private var lastFrameTimeNanos: Long = 0L

    // ==================== Layout Dimensions ====================
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var orbSize: Int = 0
    private var reticleSize: Int = 0
    private var aimCursorSize: Int = 0

    // Screen center coordinates (where the reticle is visually positioned)
    private var centerX: Float = 0f
    private var centerY: Float = 0f

    // ==================== Animation State ====================
    private var isPopping: Boolean = false

    // ==================== Constants ====================
    companion object {
        // Base difficulty values (Level 1)
        private const val BASE_CAPTURE_RADIUS = 80f      // pixels - distance within which aim counts as "on target"
        private const val BASE_FOCUS_TIME = 0.5f         // seconds - time to hold aim on target to pop

        // Difficulty scaling per level
        private const val CAPTURE_RADIUS_DECREASE_PER_LEVEL = 8f   // pixels smaller each level
        private const val FOCUS_TIME_INCREASE_PER_LEVEL = 0.05f    // seconds longer each level
        private const val MIN_CAPTURE_RADIUS = 35f                  // minimum capture radius (don't go below this)
        private const val MAX_FOCUS_TIME = 1.2f                     // maximum focus time

        // Pops required to level up
        private const val POPS_PER_LEVEL = 5

        // Maximum aim offset from center (prevents aiming off-screen)
        private const val MAX_AIM_OFFSET = 350f
    }

    // ==================== Lifecycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup view binding
        binding = ActivityFocusOrbsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on for gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable immersive fullscreen mode
        setupFullscreen()

        // Initialize UI
        updateScoreDisplay()
        updateLevelDisplay()

        // Wait for layout to be measured before starting game
        binding.gameContainer.post {
            initializeGameDimensions()
            updateDifficultyForLevel()
            spawnNewOrb()
        }
    }

    override fun onResume() {
        super.onResume()
        startGameLoop()
    }

    override fun onPause() {
        super.onPause()
        stopGameLoop()
    }

    // ==================== Slider Touch Input (Rokid AR Glasses) ====================

    /**
     * Handle touch events from the Rokid slider touch sensor.
     * Swiping on the slider moves the aim cursor.
     * The aim position is preserved between swipes.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event ?: return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Start of touch - record initial position
                lastTouchX = event.x
                lastTouchY = event.y
                isTouching = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTouching) {
                    // Calculate how far the finger moved since last event
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY

                    // Apply sensitivity and add to aim offset
                    aimOffsetX += deltaX * sliderSensitivityX
                    aimOffsetY += deltaY * sliderSensitivityY

                    // Clamp to max range
                    aimOffsetX = aimOffsetX.coerceIn(-MAX_AIM_OFFSET, MAX_AIM_OFFSET)
                    aimOffsetY = aimOffsetY.coerceIn(-MAX_AIM_OFFSET, MAX_AIM_OFFSET)

                    // Update last position for next delta calculation
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // End of touch - aim stays at current position
                isTouching = false
            }
        }

        return true
    }

    /**
     * Handle generic motion events (some Rokid models send slider data here).
     */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)

        // Handle trackpad/slider-style input
        if (event.action == MotionEvent.ACTION_MOVE) {
            val deltaX = event.x - lastTouchX
            val deltaY = event.y - lastTouchY

            if (lastTouchX != 0f || lastTouchY != 0f) {
                aimOffsetX += deltaX * sliderSensitivityX
                aimOffsetY += deltaY * sliderSensitivityY

                aimOffsetX = aimOffsetX.coerceIn(-MAX_AIM_OFFSET, MAX_AIM_OFFSET)
                aimOffsetY = aimOffsetY.coerceIn(-MAX_AIM_OFFSET, MAX_AIM_OFFSET)
            }

            lastTouchX = event.x
            lastTouchY = event.y
            return true
        }

        return super.onGenericMotionEvent(event)
    }

    // ==================== Button Input (Rokid AR Glasses) ====================

    /**
     * Handle key/button events from Rokid AR glasses.
     * Button press → recenter the aim cursor
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle various button codes that Rokid might send
        // Common codes: KEYCODE_ENTER, KEYCODE_DPAD_CENTER, KEYCODE_BUTTON_A
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_SPACE -> {
                recenterAim()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Reset the aim cursor to center position.
     * Press button to recenter the aim.
     */
    private fun recenterAim() {
        // Reset aim offset to center
        aimOffsetX = 0f
        aimOffsetY = 0f

        // Visual feedback: briefly flash the reticle
        binding.reticle.animate()
            .scaleX(1.5f)
            .scaleY(1.5f)
            .setDuration(100)
            .withEndAction {
                binding.reticle.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    // ==================== Fullscreen Setup ====================

    private fun setupFullscreen() {
        // Use the modern approach for immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ==================== Game Initialization ====================

    /**
     * Initialize dimensions after layout is measured.
     * This gives us the actual screen size and view sizes.
     */
    private fun initializeGameDimensions() {
        screenWidth = binding.gameContainer.width
        screenHeight = binding.gameContainer.height
        orbSize = binding.orb.width
        reticleSize = binding.reticle.width
        aimCursorSize = binding.aimCursor.width

        // Calculate center of screen (where reticle is positioned)
        centerX = screenWidth / 2f
        centerY = screenHeight / 2f
    }

    // ==================== Difficulty System ====================

    /**
     * Update difficulty parameters based on current level.
     * Higher levels = smaller capture radius, longer hold time.
     */
    private fun updateDifficultyForLevel() {
        // Decrease capture radius as level increases (harder to aim)
        captureRadius = (BASE_CAPTURE_RADIUS - (level - 1) * CAPTURE_RADIUS_DECREASE_PER_LEVEL)
            .coerceAtLeast(MIN_CAPTURE_RADIUS)

        // Increase required focus time as level increases (need to hold longer)
        requiredFocusTime = (BASE_FOCUS_TIME + (level - 1) * FOCUS_TIME_INCREASE_PER_LEVEL)
            .coerceAtMost(MAX_FOCUS_TIME)
    }

    /**
     * Check if player should level up based on score.
     */
    private fun checkLevelUp() {
        val newLevel = (score / POPS_PER_LEVEL) + 1
        if (newLevel > level) {
            level = newLevel
            updateDifficultyForLevel()
            updateLevelDisplay()
        }
    }

    // ==================== Game Loop ====================

    /**
     * Start the game loop using Choreographer for smooth frame-synced updates.
     */
    private fun startGameLoop() {
        if (!isGameRunning) {
            isGameRunning = true
            lastFrameTimeNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Stop the game loop.
     */
    private fun stopGameLoop() {
        isGameRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /**
     * Choreographer frame callback - called every frame (~60fps).
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (!isGameRunning) return

        // Calculate delta time in seconds
        val deltaTime = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
        lastFrameTimeNanos = frameTimeNanos

        // Clamp delta time to avoid huge jumps (e.g., after pause)
        val clampedDeltaTime = deltaTime.coerceIn(0f, 0.1f)

        // Update game state
        updateFrame(clampedDeltaTime)

        // Schedule next frame
        if (isGameRunning) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Main update function called every frame.
     * @param deltaTime Time elapsed since last frame in seconds.
     */
    private fun updateFrame(deltaTime: Float) {
        // Don't update if we're in the middle of a pop animation
        if (isPopping) return

        // Update aim cursor visual position to match head tilt
        updateAimCursorPosition()

        // Check alignment between virtual aim point and orb
        checkAlignment(deltaTime)
    }

    /**
     * Update the visual aim cursor position based on current aim offset.
     * This shows the player where they're currently aiming.
     */
    private fun updateAimCursorPosition() {
        val aimX = centerX + aimOffsetX
        val aimY = centerY + aimOffsetY

        // Position the aim cursor (offset by half its size to center it)
        binding.aimCursor.x = aimX - aimCursorSize / 2f
        binding.aimCursor.y = aimY - aimCursorSize / 2f
    }

    // ==================== Orb Position ====================

    /**
     * Update the orb View's position to match our logical position.
     */
    private fun updateOrbPosition() {
        // The orb's x/y properties refer to its top-left corner,
        // but orbX/orbY represent the center, so we offset by half the size
        binding.orb.x = orbX - orbSize / 2f
        binding.orb.y = orbY - orbSize / 2f
    }

    // ==================== Alignment & Focus ====================

    /**
     * Check if the virtual aim point is aligned with the orb.
     *
     * The aim point is: (centerX + aimOffsetX, centerY + aimOffsetY)
     * This represents where the player is "looking" based on head tilt.
     *
     * If the aim point is within captureRadius of the orb center,
     * we accumulate focus time. Otherwise, we reset.
     */
    private fun checkAlignment(deltaTime: Float) {
        // Calculate the virtual aim point based on head tilt
        val aimX = centerX + aimOffsetX
        val aimY = centerY + aimOffsetY

        // Calculate distance from aim point to orb center
        val distance = hypot(aimX - orbX, aimY - orbY)

        if (distance <= captureRadius) {
            // Aim point is on target - accumulate focus time
            currentFocusTime += deltaTime

            // Show and update progress bar
            binding.focusProgressBar.visibility = View.VISIBLE
            val progress = ((currentFocusTime / requiredFocusTime) * 100).toInt().coerceIn(0, 100)
            binding.focusProgressBar.progress = progress

            // Check if we've held focus long enough
            if (currentFocusTime >= requiredFocusTime) {
                popOrb()
            }
        } else {
            // Aim point is off target - reset focus timer
            currentFocusTime = 0f
            binding.focusProgressBar.visibility = View.INVISIBLE
            binding.focusProgressBar.progress = 0
        }
    }

    // ==================== Orb Pop & Respawn ====================

    /**
     * Pop the orb with a flash animation, increment score, check level up, spawn new orb.
     */
    private fun popOrb() {
        isPopping = true
        currentFocusTime = 0f
        binding.focusProgressBar.visibility = View.INVISIBLE

        // Increment score
        score++
        updateScoreDisplay()

        // Check for level up
        checkLevelUp()

        // Play pop animation
        playPopAnimation {
            // After animation completes, spawn new orb
            spawnNewOrb()
            isPopping = false
        }
    }

    /**
     * Play a flash/scale animation for the orb pop effect.
     * Uses green-only visuals for AR glasses compatibility.
     */
    private fun playPopAnimation(onComplete: () -> Unit) {
        // Position flash overlay at orb location
        binding.flashOverlay.x = orbX - binding.flashOverlay.width / 2f
        binding.flashOverlay.y = orbY - binding.flashOverlay.height / 2f

        // Orb scale up and fade out
        val orbScaleX = ObjectAnimator.ofFloat(binding.orb, View.SCALE_X, 1f, 1.5f)
        val orbScaleY = ObjectAnimator.ofFloat(binding.orb, View.SCALE_Y, 1f, 1.5f)
        val orbFade = ObjectAnimator.ofFloat(binding.orb, View.ALPHA, 1f, 0f)

        // Flash overlay fade in and out
        val flashFadeIn = ObjectAnimator.ofFloat(binding.flashOverlay, View.ALPHA, 0f, 0.8f)
        flashFadeIn.duration = 80
        val flashFadeOut = ObjectAnimator.ofFloat(binding.flashOverlay, View.ALPHA, 0.8f, 0f)
        flashFadeOut.duration = 150
        flashFadeOut.startDelay = 80

        // Flash scale
        val flashScaleX = ObjectAnimator.ofFloat(binding.flashOverlay, View.SCALE_X, 0.5f, 1.5f)
        val flashScaleY = ObjectAnimator.ofFloat(binding.flashOverlay, View.SCALE_Y, 0.5f, 1.5f)

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
            orbScaleX, orbScaleY, orbFade,
            flashFadeIn, flashFadeOut,
            flashScaleX, flashScaleY
        )
        animatorSet.duration = 200
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Reset orb visual properties
                binding.orb.scaleX = 1f
                binding.orb.scaleY = 1f
                binding.orb.alpha = 1f
                binding.flashOverlay.scaleX = 1f
                binding.flashOverlay.scaleY = 1f
                binding.flashOverlay.alpha = 0f
                onComplete()
            }
        })

        animatorSet.start()
    }

    /**
     * Spawn the orb at a random position within safe screen bounds.
     * The orb stays fixed until popped (player must aim at it via head tilt).
     */
    private fun spawnNewOrb() {
        // Define safe margins to keep orb fully visible
        val margin = orbSize + 50

        // Random position within safe bounds
        val minX = margin.toFloat()
        val maxX = (screenWidth - margin).toFloat()
        val minY = margin.toFloat()
        val maxY = (screenHeight - margin).toFloat()

        // Ensure the orb doesn't spawn too close to center (make player work for it!)
        // Also ensure it spawns within the aimable range (MAX_AIM_OFFSET from center)
        val minDistanceFromCenter = 100f

        var attempts = 0
        do {
            orbX = Random.nextFloat() * (maxX - minX) + minX
            orbY = Random.nextFloat() * (maxY - minY) + minY
            val distanceFromCenter = hypot(orbX - centerX, orbY - centerY)
            attempts++
            // Make sure orb is reachable (within aim range) but not too close
        } while ((distanceFromCenter < minDistanceFromCenter || distanceFromCenter > MAX_AIM_OFFSET) && attempts < 50)

        // Update visual position
        updateOrbPosition()

        // Reset focus state
        currentFocusTime = 0f

        // Spawn animation - orb appears with a quick scale-in
        binding.orb.scaleX = 0f
        binding.orb.scaleY = 0f
        binding.orb.alpha = 1f

        val spawnScaleX = ObjectAnimator.ofFloat(binding.orb, View.SCALE_X, 0f, 1f)
        val spawnScaleY = ObjectAnimator.ofFloat(binding.orb, View.SCALE_Y, 0f, 1f)

        val spawnAnimator = AnimatorSet()
        spawnAnimator.playTogether(spawnScaleX, spawnScaleY)
        spawnAnimator.duration = 150
        spawnAnimator.interpolator = DecelerateInterpolator()
        spawnAnimator.start()
    }

    // ==================== UI Updates ====================

    /**
     * Update the score display TextView.
     */
    private fun updateScoreDisplay() {
        binding.scoreText.text = "Score: $score"
    }

    /**
     * Update the level display TextView.
     */
    private fun updateLevelDisplay() {
        binding.levelText.text = "Level: $level"
    }
}
