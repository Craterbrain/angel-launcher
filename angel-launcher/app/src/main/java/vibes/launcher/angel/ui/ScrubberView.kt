package vibes.launcher.angel.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.exp
import kotlin.math.abs

class ScrubberView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val letters = listOf("☆", "#") + ('A'..'Z').map { it.toString() } + listOf("○")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(2f, 1f, 1f, Color.parseColor("#80000000"))
    }
    
    private val apexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF") // Faded white
        textAlign = Paint.Align.CENTER
        textSize = 64f * resources.displayMetrics.density // Large
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.parseColor("#80000000"))
    }
    
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // Translucent white
        style = Paint.Style.FILL
    }

    fun setTextColor(color: Int, opacity: Float = 1.0f) {
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(color, alpha)
        val apexAlpha = (opacity.coerceIn(0f, 1f) * 0.5f * 255).toInt()
        apexPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(color, apexAlpha)
        invalidate()
    }

    var onLetterSelected: ((String) -> Unit)? = null
    var onInteractionStarted: (() -> Unit)? = null
    var onInteractionEnded: (() -> Unit)? = null
    
    var isActive = false
        private set
        
    private var lastSelectedIndex = -1
    
    // Wave animation variables
    private var touchY: Float? = null
    private var waveAmplitude: Float = 0f
    private val maxAmplitude = 60f * resources.displayMetrics.density
    private val waveSpread = (150f * resources.displayMetrics.density).let { it * it } // Pre-squared
    
    private var animator: ValueAnimator? = null



    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val startY = height * (2f / 7f)
        val usableHeight = height * (4f / 7f)
        val itemHeight = usableHeight / letters.size
        val screenWidth = resources.displayMetrics.widthPixels
        val baseX = width - (screenWidth * 0.03f) // Right aligned (3% of screen width)
        

        for (i in letters.indices) {
            val y = startY + (i * itemHeight) + (itemHeight / 2) - ((paint.descent() + paint.ascent()) / 2)
            var x = baseX
            
            if (isActive) {
                touchY?.let { ty ->
                    val distance = abs(startY + (i * itemHeight) + (itemHeight / 2) - ty)
                    // Gaussian bell curve for horizontal offset
                    val offset = waveAmplitude * exp(-(distance * distance) / waveSpread)
                    x -= offset // Shift left
                }
            }
            
            canvas.drawText(letters[i], x, y, paint)
        }
        
        // Draw apex character if active
        if (isActive && touchY != null && waveAmplitude > 0 && lastSelectedIndex in letters.indices) {
            val letter = letters[lastSelectedIndex]
            val apexX = baseX - maxAmplitude - (50f * resources.displayMetrics.density) // Further left
            canvas.drawText(letter, apexX, touchY!! - ((apexPaint.descent() + apexPaint.ascent()) / 2), apexPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || visibility != View.VISIBLE) {
            return false
        }
        val startY = height * (2f / 7f)
        val usableHeight = height * (4f / 7f)
        val endY = startY + usableHeight

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < startY || event.y > endY) {
                    return false // Ignore touches outside the 4/7 scrubber area
                }
                if (!isActive) {
                    isActive = true
                    onInteractionStarted?.invoke()
                }
                handleTouch(event, startY, usableHeight)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouch(event, startY, usableHeight)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isActive) {
                    isActive = false
                    onInteractionEnded?.invoke()
                }
                lastSelectedIndex = -1
                animator = ValueAnimator.ofFloat(waveAmplitude, 0f).apply {
                    duration = 200
                    addUpdateListener { anim ->
                        waveAmplitude = anim.animatedValue as Float
                        if (waveAmplitude == 0f) {
                            touchY = null
                        }
                        invalidate()
                    }
                    start()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun handleTouch(event: MotionEvent, startY: Float, usableHeight: Float) {
        animator?.cancel()
        waveAmplitude = maxAmplitude
        touchY = event.y.coerceIn(startY, startY + usableHeight)
        
        val itemHeight = usableHeight / letters.size
        var index = ((touchY!! - startY) / itemHeight).toInt()
        index = index.coerceIn(0, letters.size - 1)
        
        if (index != lastSelectedIndex) {
            lastSelectedIndex = index
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onLetterSelected?.invoke(letters[index])
        }
        invalidate()
    }
}
