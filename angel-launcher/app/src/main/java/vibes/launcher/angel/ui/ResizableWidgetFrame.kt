package vibes.launcher.angel.ui

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import vibes.launcher.angel.R
import vibes.launcher.angel.data.UserWidgetConfig

class ResizableWidgetFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private var hostView: AppWidgetHostView? = null
    var widgetConfig: UserWidgetConfig? = null

    var isEditMode: Boolean = false
        set(value) {
            field = value
            editOverlayContainer?.visibility = if (value) View.VISIBLE else View.GONE
            invalidate()
        }

    var onHeightChangedListener: ((Int) -> Unit)? = null
    var onRemoveClickListener: (() -> Unit)? = null
    var onMoveUpClickListener: (() -> Unit)? = null
    var onMoveDownClickListener: (() -> Unit)? = null
    var onTogglePositionListener: (() -> Unit)? = null

    private var editOverlayContainer: FrameLayout? = null
    private var resizeHandle: LinearLayout? = null
    private var tvHandleText: TextView? = null
    private var btnRemoveChip: View? = null
    private var btnMoveUpChip: View? = null
    private var btnMoveDownChip: View? = null
    private var btnTogglePosChip: View? = null
    private var tvPosText: TextView? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF80D8FF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1A80D8FF.toInt()
    }
    private val borderRect = RectF()

    init {
        setWillNotDraw(false)
        setupEditOverlay()
    }

    private fun setupEditOverlay() {
        val overlay = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        editOverlayContainer = overlay

        // Top Controls Bar (Remove, Move Up/Down, Toggle Position)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }

        // Remove Chip
        val (removeChip, _) = createChip("✕ Remove", 0xFFFF5252.toInt()) {
            onRemoveClickListener?.invoke()
        }
        btnRemoveChip = removeChip
        topBar.addView(removeChip)

        val spacer1 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
        }
        topBar.addView(spacer1)

        // Move Up Chip
        val (moveUpChip, _) = createChip("↑", 0xFF80D8FF.toInt()) {
            onMoveUpClickListener?.invoke()
        }
        btnMoveUpChip = moveUpChip
        topBar.addView(moveUpChip)

        val spacer2 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((4 * density).toInt(), 1)
        }
        topBar.addView(spacer2)

        // Move Down Chip
        val (moveDownChip, _) = createChip("↓", 0xFF80D8FF.toInt()) {
            onMoveDownClickListener?.invoke()
        }
        btnMoveDownChip = moveDownChip
        topBar.addView(moveDownChip)

        val spacer3 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
        }
        topBar.addView(spacer3)

        // Position Toggle Chip
        val (posChip, posTv) = createChip("Above Clock", 0xFF80D8FF.toInt()) {
            onTogglePositionListener?.invoke()
        }
        btnTogglePosChip = posChip
        tvPosText = posTv
        topBar.addView(posChip)

        overlay.addView(topBar)

        // Bottom Resize Handle Pill
        val bottomHandle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.search_bar_bg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x66006874.toInt())
            setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
            
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (6 * density).toInt()
            layoutParams = lp
        }

        val handleTv = TextView(context).apply {
            text = "↕ Drag to Resize"
            setTextColor(0xFF80D8FF.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bottomHandle.addView(handleTv)
        tvHandleText = handleTv
        resizeHandle = bottomHandle

        var startY = 0f
        var initialHeightDp = 0
        bottomHandle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    initialHeightDp = if (layoutParams.height > 0) (layoutParams.height / density).toInt() else 140
                    v.animate().scaleX(1.04f).scaleY(1.08f).setDuration(60).start()
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaPx = event.rawY - startY
                    val deltaDp = (deltaPx / density).toInt()
                    val newHeightDp = (initialHeightDp + deltaDp).coerceIn(60, 380)
                    
                    val lp = layoutParams
                    lp.height = (newHeightDp * density).toInt()
                    layoutParams = lp
                    requestLayout()

                    handleTv.text = "↕ $newHeightDp dp"
                    onHeightChangedListener?.invoke(newHeightDp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    handleTv.text = "↕ Drag to Resize"
                    true
                }
                else -> false
            }
        }

        overlay.addView(bottomHandle)
        addView(overlay)
    }

    private fun createChip(label: String, tintColor: Int, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.search_bar_bg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(tintColor, 0x40)
            )
            setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
            setOnClickListener { onClick() }
        }
        val tv = TextView(context).apply {
            text = label
            setTextColor(tintColor)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        chip.addView(tv)
        return Pair(chip, tv)
    }

    fun setWidgetHostView(view: AppWidgetHostView, config: UserWidgetConfig) {
        if (hostView != null && hostView != view) {
            removeView(hostView)
        }
        hostView = view
        widgetConfig = config
        
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(view, 0, lp)

        tvPosText?.text = if (config.isAboveClock) "Above Clock" else "Below Clock"
        
        val frameHeight = if (config.heightDp > 0) (config.heightDp * density).toInt() else LayoutParams.WRAP_CONTENT
        val frameLp = layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, frameHeight)
        frameLp.height = frameHeight
        layoutParams = frameLp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isEditMode) {
            borderRect.set(
                (2 * density),
                (2 * density),
                width - (2 * density),
                height - (2 * density)
            )
            canvas.drawRoundRect(borderRect, 16 * density, 16 * density, fillPaint)
            canvas.drawRoundRect(borderRect, 16 * density, 16 * density, strokePaint)
        }
    }
}
