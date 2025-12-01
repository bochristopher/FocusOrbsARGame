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

    // ==================== Tap Control ====================
    // Virtual aim offset from center
    private var aimOffsetX: Float = 0f
    private var aimOffsetY: Float = 0f

    // Auto-moving aim cursor state
    private var isAimMoving: Boolean = true      // Whether aim is currently moving (tap to stop)
    private var aimAngle: Float = 0f             // Current angle in radians (for circular motion)
    private var aimRadius: Float = 200f          // Current radius from center
    private var aimRotationSpeed: Float = 2.0f   // Radians per second (how fast it spins)

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

    // ==================== Tap Input (Rokid AR Glasses) ====================

    /**
     * Handle touch events from the Rokid slider touch sensor.
     * TAP: Stop/start the aim cursor movement.
     * When stopped on the orb, the focus timer fills and orb pops.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event ?: return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Tap detected - toggle aim movement
                onTap()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * Handle tap action - toggle between moving and stopped aim cursor.
     */
    private fun onTap() {
        if (isAimMoving) {
            // Stop the aim cursor where it is
            isAimMoving = false

            // Visual feedback - make aim cursor brighter/pulse
            binding.aimCursor.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(100)
                .withEndAction {
                    binding.aimCursor.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        } else {
            // Resume aim cursor movement
            isAimMoving = true
            currentFocusTime = 0f  // Reset focus timer
            binding.focusProgressBar.visibility = View.INVISIBLE
        }
    }

    // ==================== Button Input (Rokid AR Glasses) ====================

    /**
     * Handle key/button events from Rokid AR glasses.
     * Button press → same as tap (stop/start aim cursor)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle various button codes that Rokid might send
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_SPACE -> {
                onTap()  // Button acts same as tap
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

        // Move aim cursor automatically if not stopped
        if (isAimMoving) {
            updateAutoAim(deltaTime)
        }

        // Update aim cursor visual position
        updateAimCursorPosition()

        // Check alignment between virtual aim point and orb
        checkAlignment(deltaTime)
    }

    /**
     * Update the auto-moving aim cursor.
     * The aim cursor moves in a circular pattern around the screen.
     * Player taps to stop it when it's over the orb.
     */
    private fun updateAutoAim(deltaTime: Float) {
        // Rotate the aim angle
        aimAngle += aimRotationSpeed * deltaTime

        // Keep angle in 0 to 2π range
        if (aimAngle > 2 * Math.PI) {
            aimAngle -= (2 * Math.PI).toFloat()
        }

        // Convert polar coordinates (angle, radius) to cartesian offset (x, y)
        aimOffsetX = (kotlin.math.cos(aimAngle) * aimRadius)
        aimOffsetY = (kotlin.math.sin(aimAngle) * aimRadius)
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
     * Only counts when aim is STOPPED (player tapped to lock aim).
     *
     * If the aim point is within captureRadius of the orb center,
     * we accumulate focus time. Otherwise, we reset.
     */
    private fun checkAlignment(deltaTime: Float) {
        // Only check alignment when aim is stopped
        if (isAimMoving) {
            binding.focusProgressBar.visibility = View.INVISIBLE
            return
        }

        // Calculate the virtual aim point
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

            // Auto-resume movement after a short delay if missed
            // (Player needs to tap again to try)
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
            // After animation completes, spawn new orb and restart aim movement
            spawnNewOrb()
            isAimMoving = true  // Resume auto-aim for next orb
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
     * The orb stays fixed until popped. Aim cursor will sweep in a circle
     * at the same radius as the orb, so timing the tap is key.
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
        val minDistanceFromCenter = 120f

        var attempts = 0
        do {
            orbX = Random.nextFloat() * (maxX - minX) + minX
            orbY = Random.nextFloat() * (maxY - minY) + minY
            val distanceFromCenter = hypot(orbX - centerX, orbY - centerY)
            attempts++
            // Make sure orb is reachable (within aim range) but not too close
        } while ((distanceFromCenter < minDistanceFromCenter || distanceFromCenter > MAX_AIM_OFFSET) && attempts < 50)

        // Set aim radius to match orb's distance from center
        // This ensures the sweeping aim cursor will pass over the orb
        aimRadius = hypot(orbX - centerX, orbY - centerY)

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
