package com.dt.manager.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatEditText
import com.dt.manager.core.SyntaxHighlighter
import kotlin.math.abs

/**
 * EditText that paints a line-number gutter on the left side and
 * applies syntax highlighting. Supports pinch-to-zoom to change the text size.
 */
class CodeEditorView : AppCompatEditText {

    companion object {
        private const val GUTTER_WIDTH_DP = 36
        private const val GUTTER_PADDING_DP = 6
        private const val DEFAULT_TEXT_SIZE_SP = 11f
        private const val MIN_TEXT_SIZE_SP = 7f
        private const val MAX_TEXT_SIZE_SP = 28f
    }

    private lateinit var gutterBgPaint: Paint
    private lateinit var lineNumberPaint: Paint
    private lateinit var separatorPaint: Paint
    private var gutterWidthPx = 0
    private var gutterPaddingPx = 0

    var textSizeSp = DEFAULT_TEXT_SIZE_SP
        private set
    private lateinit var scaleDetector: ScaleGestureDetector

    var language: SyntaxHighlighter.Language = SyntaxHighlighter.Language.TEXT
        set(value) {
            field = value
            if (highlightEnabled) applyHighlight()
        }

    var highlightEnabled = true
        set(value) {
            field = value
            if (value) applyHighlight()
        }

    private var suppressHighlight = false
    private val highlightRunnable = Runnable { applyHighlight() }

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        val density = context.resources.displayMetrics.density
        gutterWidthPx = (GUTTER_WIDTH_DP * density).toInt()
        gutterPaddingPx = (GUTTER_PADDING_DP * density).toInt()

        gutterBgPaint = Paint().apply {
            color = -0xd4d4d5 // 0xFF2B2B2B
            style = Paint.Style.FILL
        }

        separatorPaint = Paint().apply {
            color = -0xc5c5c6 // 0xFF3A3A3A
            style = Paint.Style.FILL
        }

        lineNumberPaint = Paint().apply {
            color = -0x7f7f80 // 0xFF808080
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = DEFAULT_TEXT_SIZE_SP * density
            textAlign = Paint.Align.RIGHT
        }

        typeface = Typeface.MONOSPACE
        setTextColor(-0x111112) // 0xFFEEEEEE
        setBackgroundColor(Color.TRANSPARENT)
        setTextSize(DEFAULT_TEXT_SIZE_SP)
        setLineSpacing(3 * density, 1.0f)
        setHorizontallyScrolling(true)
        gravity = Gravity.TOP

        setPadding(
            gutterWidthPx + gutterPaddingPx,
            (8 * density).toInt(),
            (12 * density).toInt(),
            (8 * density).toInt()
        )

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val newSize = (textSizeSp * factor).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
                if (abs(newSize - textSizeSp) < 0.1f) return true
                textSizeSp = newSize
                setTextSize(textSizeSp)
                val d = context.resources.displayMetrics.density
                lineNumberPaint.textSize = textSizeSp * d
                invalidate()
                return true
            }
        })

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressHighlight || !highlightEnabled) return
                removeCallbacks(highlightRunnable)
                postDelayed(highlightRunnable, 250)
            }
        })
    }

    fun setTextSizeSp(sp: Float) {
        textSizeSp = sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        setTextSize(textSizeSp)
        val density = context.resources.displayMetrics.density
        lineNumberPaint.textSize = textSizeSp * density
        invalidate()
    }

    private fun applyHighlight() {
        val e = text ?: return
        if (e.isEmpty()) return

        val spans = e.getSpans(0, e.length, CharacterStyle::class.java)
        for (s in spans) {
            e.removeSpan(s)
        }

        if (language == SyntaxHighlighter.Language.TEXT) return

        val highlighted = SyntaxHighlighter.highlight(e.toString(), language)
        val newSpans = highlighted.getSpans(0, highlighted.length, CharacterStyle::class.java)
        suppressHighlight = true
        for (s in newSpans) {
            val start = highlighted.getSpanStart(s)
            val end = highlighted.getSpanEnd(s)
            e.setSpan(CharacterStyle.wrap(s), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        suppressHighlight = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, gutterWidthPx.toFloat(), height.toFloat(), gutterBgPaint)

        val l = layout
        if (l != null) {
            val lineCount = lineCount
            var firstVisibleLine = l.getLineForVertical(scrollY)
            var lastVisibleLine = l.getLineForVertical(scrollY + height)
            if (lastVisibleLine >= lineCount - 1) lastVisibleLine = lineCount - 1
            if (lastVisibleLine < 0) lastVisibleLine = 0
            if (firstVisibleLine < 0) firstVisibleLine = 0

            for (i in firstVisibleLine..lastVisibleLine) {
                val baseline = l.getLineBaseline(i) + paddingTop
                val numText = (i + 1).toString()
                val x = (gutterWidthPx - gutterPaddingPx).toFloat()
                canvas.drawText(numText, x, baseline.toFloat(), lineNumberPaint)
            }
        }

        canvas.drawRect(gutterWidthPx.toFloat(), 0f, (gutterWidthPx + 1).toFloat(), height.toFloat(), separatorPaint)
        super.onDraw(canvas)
    }
}
