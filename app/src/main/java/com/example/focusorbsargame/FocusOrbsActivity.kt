package com.example.focusorbsargame

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.Choreographer
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
 * Game mechanics:
 * - A reticle is fixed at the center of the screen
 * - An orb spawns at a random position and slowly moves toward the center
 * - When the orb overlaps the reticle for a sustained duration, it "pops"
 * - Score increases and a new orb spawns
 *
 * For Step 1: The orb auto-moves toward center to simulate head tracking.
 * In later steps, this will be replaced with actual head/gaze input.
 */
class FocusOrbsActivity : AppCompatActivity(), Choreographer.FrameCallback {

    // ==================== View Binding ====================
    private lateinit var binding: ActivityFocusOrbsBinding

    // ==================== Game State ====================
    private var score: Int = 0
    private var isGameRunning: Boolean = false

    // ==================== Orb Position & Movement ====================
    // Current orb position (center of the orb in screen coordinates)
    private var orbX: Float = 0f
    private var orbY: Float = 0f

    // Movement speed (pixels per second) - orb drifts toward center
    private val orbSpeed: Float = 120f

    // ==================== Focus/Alignment Tracking ====================
    // Radius within which the orb is considered "aligned" with reticle
    private val alignmentRadius: Float = 60f

    // Time required to hold alignment before orb pops (in seconds)
    private val requiredFocusTime: Float = 0.6f

    // Accumulated focus time while aligned
    private var currentFocusTime: Float = 0f

    // ==================== Timing ====================
    private var lastFrameTimeNanos: Long = 0L

    // ==================== Layout Dimensions ====================
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var orbSize: Int = 0
    private var reticleSize: Int = 0

    // Screen center coordinates (where the reticle is)
    private var centerX: Float = 0f
    private var centerY: Float = 0f

    // ==================== Animation State ====================
    private var isPopping: Boolean = false

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

        // Wait for layout to be measured before starting game
        binding.gameContainer.post {
            initializeGameDimensions()
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

        // Calculate center of screen (where reticle is positioned)
        centerX = screenWidth / 2f
        centerY = screenHeight / 2f
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

        // Move orb toward center (simulated head tracking for Step 1)
        moveOrbTowardCenter(deltaTime)

        // Update orb visual position
        updateOrbPosition()

        // Check alignment and handle focus timer
        checkAlignment(deltaTime)
    }

    // ==================== Orb Movement ====================

    /**
     * Move the orb toward the screen center.
     * In Step 1, this simulates the player "aiming" with their head.
     * Later, this will be replaced with actual head tracking input.
     */
    private fun moveOrbTowardCenter(deltaTime: Float) {
        // Calculate direction toward center
        val dx = centerX - orbX
        val dy = centerY - orbY

        // Calculate distance to center
        val distance = hypot(dx, dy)

        // If we're very close to center, don't move (avoid jitter)
        if (distance < 2f) {
            orbX = centerX
            orbY = centerY
            return
        }

        // Normalize direction and apply speed
        val moveDistance = orbSpeed * deltaTime
        val actualMove = moveDistance.coerceAtMost(distance)

        orbX += (dx / distance) * actualMove
        orbY += (dy / distance) * actualMove
    }

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
     * Check if the orb is aligned with the reticle (within alignment radius).
     * If aligned, accumulate focus time. If not, reset.
     */
    private fun checkAlignment(deltaTime: Float) {
        // Calculate distance from orb center to screen center
        val distance = hypot(orbX - centerX, orbY - centerY)

        if (distance <= alignmentRadius) {
            // Orb is aligned - accumulate focus time
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
            // Orb is not aligned - reset focus timer
            currentFocusTime = 0f
            binding.focusProgressBar.visibility = View.INVISIBLE
            binding.focusProgressBar.progress = 0
        }
    }

    // ==================== Orb Pop & Respawn ====================

    /**
     * Pop the orb with a flash animation, increment score, and spawn new orb.
     */
    private fun popOrb() {
        isPopping = true
        currentFocusTime = 0f
        binding.focusProgressBar.visibility = View.INVISIBLE

        // Increment score
        score++
        updateScoreDisplay()

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
        val minDistanceFromCenter = 150f

        var attempts = 0
        do {
            orbX = Random.nextFloat() * (maxX - minX) + minX
            orbY = Random.nextFloat() * (maxY - minY) + minY
            val distanceFromCenter = hypot(orbX - centerX, orbY - centerY)
            attempts++
        } while (distanceFromCenter < minDistanceFromCenter && attempts < 20)

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
}

