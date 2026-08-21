package com.dt.manager.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

import androidx.appcompat.widget.AppCompatEditText;

import com.dt.manager.core.SyntaxHighlighter;

/**
 * EditText that paints a line-number gutter on the left side and
 * (optionally) applies syntax highlighting. Supports pinch-to-zoom
 * to change the text size.
 *
 * The view should be wrapped inside a NestedScrollView (vertical)
 * + HorizontalScrollView (horizontal) for proper scroll behavior.
 * Set layout_width and layout_height to wrap_content.
 */
public class CodeEditorView extends AppCompatEditText {

    private static final int GUTTER_WIDTH_DP = 36;
    private static final int GUTTER_PADDING_DP = 6;
    private static final float DEFAULT_TEXT_SIZE_SP = 11f;
    private static final float MIN_TEXT_SIZE_SP = 7f;
    private static final float MAX_TEXT_SIZE_SP = 28f;

    private Paint gutterBgPaint;
    private Paint lineNumberPaint;
    private Paint separatorPaint;
    private int gutterWidthPx;
    private int gutterPaddingPx;

    private float textSizeSp = DEFAULT_TEXT_SIZE_SP;
    private ScaleGestureDetector scaleDetector;

    private SyntaxHighlighter.Language language = SyntaxHighlighter.Language.TEXT;
    private boolean highlightEnabled = true;
    private boolean suppressHighlight = false;

    private final Runnable highlightRunnable = this::applyHighlight;

    public CodeEditorView(Context context) {
        super(context);
        init();
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;
        gutterWidthPx = (int) (GUTTER_WIDTH_DP * density);
        gutterPaddingPx = (int) (GUTTER_PADDING_DP * density);

        gutterBgPaint = new Paint();
        gutterBgPaint.setColor(0xFF2B2B2B);
        gutterBgPaint.setStyle(Paint.Style.FILL);

        separatorPaint = new Paint();
        separatorPaint.setColor(0xFF3A3A3A);
        separatorPaint.setStyle(Paint.Style.FILL);

        lineNumberPaint = new Paint();
        lineNumberPaint.setColor(0xFF808080);
        lineNumberPaint.setTypeface(Typeface.MONOSPACE);
        lineNumberPaint.setAntiAlias(true);
        lineNumberPaint.setTextSize(DEFAULT_TEXT_SIZE_SP * density);
        lineNumberPaint.setTextAlign(Paint.Align.RIGHT);

        setTypeface(Typeface.MONOSPACE);
        setTextColor(0xFFEEEEEE);
        setBackgroundColor(Color.TRANSPARENT);
        setTextSize(DEFAULT_TEXT_SIZE_SP);
        setLineSpacing(3 * density, 1.0f);
        setHorizontallyScrolling(true);
        setGravity(Gravity.TOP);

        setPadding(gutterWidthPx + gutterPaddingPx,
                (int) (8 * density),
                (int) (12 * density),
                (int) (8 * density));

        // Pinch-to-zoom
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float newSize = textSizeSp * factor;
                if (newSize < MIN_TEXT_SIZE_SP) newSize = MIN_TEXT_SIZE_SP;
                if (newSize > MAX_TEXT_SIZE_SP) newSize = MAX_TEXT_SIZE_SP;
                if (Math.abs(newSize - textSizeSp) < 0.1f) return true;
                textSizeSp = newSize;
                setTextSize(textSizeSp);
                float density = getContext().getResources().getDisplayMetrics().density;
                lineNumberPaint.setTextSize(textSizeSp * density);
                invalidate();
                return true;
            }
        });

        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressHighlight || !highlightEnabled) return;
                removeCallbacks(highlightRunnable);
                postDelayed(highlightRunnable, 250);
            }
        });
    }

    public void setLanguage(SyntaxHighlighter.Language language) {
        this.language = language;
        if (highlightEnabled) applyHighlight();
    }

    public void setHighlightEnabled(boolean enabled) {
        this.highlightEnabled = enabled;
        if (enabled) applyHighlight();
    }

    public void setTextSizeSp(float sp) {
        textSizeSp = sp;
        if (textSizeSp < MIN_TEXT_SIZE_SP) textSizeSp = MIN_TEXT_SIZE_SP;
        if (textSizeSp > MAX_TEXT_SIZE_SP) textSizeSp = MAX_TEXT_SIZE_SP;
        setTextSize(textSizeSp);
        float density = getContext().getResources().getDisplayMetrics().density;
        lineNumberPaint.setTextSize(textSizeSp * density);
        invalidate();
    }

    public float getTextSizeSp() {
        return textSizeSp;
    }

    /** Apply syntax highlighting to the current text. */
    private void applyHighlight() {
        Editable e = getText();
        if (e == null || e.length() == 0) return;

        // Clear existing spans
        CharacterStyle[] spans = e.getSpans(0, e.length(), CharacterStyle.class);
        for (CharacterStyle s : spans) {
            e.removeSpan(s);
        }

        if (language == SyntaxHighlighter.Language.TEXT) return;

        Spannable highlighted = SyntaxHighlighter.highlight(e.toString(), language);
        CharacterStyle[] newSpans = highlighted.getSpans(0, highlighted.length(), CharacterStyle.class);
        suppressHighlight = true;
        for (CharacterStyle s : newSpans) {
            int start = highlighted.getSpanStart(s);
            int end = highlighted.getSpanEnd(s);
            e.setSpan(CharacterStyle.wrap(s), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        suppressHighlight = false;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw gutter background
        canvas.drawRect(0, 0, gutterWidthPx, getHeight(), gutterBgPaint);

        Layout layout = getLayout();
        if (layout != null) {
            int lineCount = getLineCount();
            int firstVisibleLine = layout.getLineForVertical(getScrollY());
            int lastVisibleLine = layout.getLineForVertical(getScrollY() + getHeight());
            if (lastVisibleLine >= lineCount - 1) lastVisibleLine = lineCount - 1;
            if (lastVisibleLine < 0) lastVisibleLine = 0;

            for (int i = firstVisibleLine; i <= lastVisibleLine; i++) {
                int baseline = layout.getLineBaseline(i) + getPaddingTop();
                String numText = String.valueOf(i + 1);
                float x = gutterWidthPx - gutterPaddingPx;
                canvas.drawText(numText, x, baseline, lineNumberPaint);
            }
        }

        // Vertical separator between gutter and code
        canvas.drawRect(gutterWidthPx, 0, gutterWidthPx + 1, getHeight(), separatorPaint);

        super.onDraw(canvas);
    }
}
