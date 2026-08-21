package com.dt.manager.core;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies syntax highlighting spans to source text. Supports XML, JSON,
 * smali, and generic code. Returns a Spannable that can be set as the
 * text of an EditText or TextView.
 *
 * Color scheme (matches the MT Manager reference):
 *   - Tags / keywords    → orange (#FFB74D)
 *   - Attributes / keys  → purple (#B388FF)
 *   - Strings / values   → green (#A5D6A7)
 *   - Comments           → gray (#808080, italic)
 *   - Numbers            → amber (#FFCC80)
 *   - Punctuation        → white
 */
public class SyntaxHighlighter {

    public static final int COLOR_TAG        = 0xFFFFB74D; // orange
    public static final int COLOR_ATTR        = 0xFFB388FF; // purple
    public static final int COLOR_STRING     = 0xFFA5D6A7; // green
    public static final int COLOR_COMMENT     = 0xFF808080; // gray
    public static final int COLOR_NUMBER      = 0xFFFFCC80; // amber
    public static final int COLOR_KEYWORD     = 0xFF90CAF9; // light blue
    public static final int COLOR_PUNCT       = 0xFFFFFFFF; // white
    public static final int COLOR_DEFAULT     = 0xFFEEEEEE; // off-white

    public enum Language {
        XML, JSON, SMALI, JAVA, MARKDOWN, TEXT
    }

    public static Language detectLanguage(String fileName) {
        if (fileName == null) return Language.TEXT;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".xml")) return Language.XML;
        if (lower.endsWith(".json")) return Language.JSON;
        if (lower.endsWith(".smali")) return Language.SMALI;
        if (lower.endsWith(".java") || lower.endsWith(".kt")) return Language.JAVA;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return Language.MARKDOWN;
        return Language.TEXT;
    }

    public static Spannable highlight(String text, Language lang) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        if (text == null || text.isEmpty()) return sb;

        switch (lang) {
            case XML:      highlightXml(sb); break;
            case JSON:     highlightJson(sb); break;
            case SMALI:    highlightSmali(sb); break;
            case JAVA:     highlightJava(sb); break;
            case MARKDOWN: highlightMarkdown(sb); break;
            case TEXT:     break;
        }
        return sb;
    }

    /* ============== XML ============== */

    private static final Pattern XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern XML_TAG = Pattern.compile("<[/]?[a-zA-Z_][\\w:.-]*");
    private static final Pattern XML_ATTR = Pattern.compile("\\s([a-zA-Z_][\\w:.-]*)(?==)");
    private static final Pattern XML_STRING = Pattern.compile("\"[^\"\\n]*\"");
    private static final Pattern XML_TAG_END = Pattern.compile("[<>]");

    private static void highlightXml(SpannableStringBuilder sb) {
        // Comments first (highest priority)
        applySpan(sb, XML_COMMENT, new ForegroundColorSpan(COLOR_COMMENT), new StyleSpan(android.graphics.Typeface.ITALIC));
        // Strings
        applySpan(sb, XML_STRING, new ForegroundColorSpan(COLOR_STRING));
        // Attributes
        applySpan(sb, XML_ATTR, new ForegroundColorSpan(COLOR_ATTR));
        // Tags
        applySpan(sb, XML_TAG, new ForegroundColorSpan(COLOR_TAG));
    }

    /* ============== JSON ============== */

    private static final Pattern JSON_KEY = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"(?=\\s*:)");
    private static final Pattern JSON_STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
    private static final Pattern JSON_NUMBER = Pattern.compile("\\b-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b");
    private static final Pattern JSON_KEYWORD = Pattern.compile("\\b(?:true|false|null)\\b");

    private static void highlightJson(SpannableStringBuilder sb) {
        // Strings inside keys (purple)
        Matcher m = JSON_KEY.matcher(sb);
        while (m.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ATTR), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Other strings (green) — skip parts already spanned as keys
        m = JSON_STRING.matcher(sb);
        while (m.find()) {
            if (!hasSpan(sb, m.start(), m.end())) {
                sb.setSpan(new ForegroundColorSpan(COLOR_STRING), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        // Numbers
        applySpan(sb, JSON_NUMBER, new ForegroundColorSpan(COLOR_NUMBER));
        // Booleans/null
        applySpan(sb, JSON_KEYWORD, new ForegroundColorSpan(COLOR_KEYWORD));
    }

    /* ============== Smali / Java ============== */

    private static final Pattern SMALI_COMMENT = Pattern.compile("#[^\\n]*");
    private static final Pattern JAVA_COMMENT_LINE = Pattern.compile("//[^\\n]*");
    private static final Pattern JAVA_COMMENT_BLOCK = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern SMALI_STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
    private static final Pattern SMALI_NUMBER = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b-?\\d+(?:\\.\\d+)?[fLl]?\\b");
    private static final Pattern SMALI_KEYWORD = Pattern.compile(
            "\\b(class|super|implements|extends|public|private|protected|static|final|abstract|" +
            "interface|enum|annotation|field|method|registers|locals|param|prologue|line|end|" +
            "void|boolean|byte|char|short|int|long|float|double|" +
            "if|else|while|for|return|new|this|super|true|false|null)\\b");
    private static final Pattern SMALI_TYPE = Pattern.compile("L[\\w/$]+;");

    private static void highlightSmali(SpannableStringBuilder sb) {
        applySpan(sb, SMALI_COMMENT, new ForegroundColorSpan(COLOR_COMMENT), new StyleSpan(android.graphics.Typeface.ITALIC));
        applySpan(sb, SMALI_STRING, new ForegroundColorSpan(COLOR_STRING));
        applySpan(sb, SMALI_TYPE, new ForegroundColorSpan(COLOR_TAG));
        applySpan(sb, SMALI_NUMBER, new ForegroundColorSpan(COLOR_NUMBER));
        applySpan(sb, SMALI_KEYWORD, new ForegroundColorSpan(COLOR_KEYWORD), new StyleSpan(android.graphics.Typeface.BOLD));
    }

    private static void highlightJava(SpannableStringBuilder sb) {
        applySpan(sb, JAVA_COMMENT_BLOCK, new ForegroundColorSpan(COLOR_COMMENT), new StyleSpan(android.graphics.Typeface.ITALIC));
        applySpan(sb, JAVA_COMMENT_LINE, new ForegroundColorSpan(COLOR_COMMENT), new StyleSpan(android.graphics.Typeface.ITALIC));
        applySpan(sb, SMALI_STRING, new ForegroundColorSpan(COLOR_STRING));
        applySpan(sb, SMALI_NUMBER, new ForegroundColorSpan(COLOR_NUMBER));
        applySpan(sb, SMALI_KEYWORD, new ForegroundColorSpan(COLOR_KEYWORD), new StyleSpan(android.graphics.Typeface.BOLD));
    }

    /* ============== Markdown ============== */

    // ATX headers: lines starting with 1-6 # chars
    private static final Pattern MD_HEADER = Pattern.compile("(?m)^#{1,6} .+$");
    // Fenced code blocks ``` ... ```
    private static final Pattern MD_CODE_BLOCK = Pattern.compile("(?ms)```.*?```");
    // Inline code `foo`
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`[^`\\n]+`");
    // Bold **foo** or __foo__
    private static final Pattern MD_BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    // Italic *foo* or _foo_ (single — must NOT match bold)
    private static final Pattern MD_ITALIC = Pattern.compile("(?<![\\*_])(\\*|_)(?=\\S)(?!\\1)(.+?)(?<=\\S)\\1(?![\\*_])");
    // Links [text](url)
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\s)]+)\\)");
    // Block quote
    private static final Pattern MD_QUOTE = Pattern.compile("(?m)^> .+$");
    // List items (- or * or + at line start)
    private static final Pattern MD_LIST = Pattern.compile("(?m)^\\s*[-*+] .+$");
    // Horizontal rule
    private static final Pattern MD_HR = Pattern.compile("(?m)^---+$");

    private static void highlightMarkdown(SpannableStringBuilder sb) {
        // Order matters: apply more specific patterns first.
        // StyleSpan takes int constants (Typeface.BOLD, .ITALIC, .BOLD_ITALIC),
        // NOT Typeface objects. MONOSPACE is a Typeface constant, not a style.
        applySpan(sb, MD_CODE_BLOCK, new ForegroundColorSpan(COLOR_STRING));
        applySpan(sb, MD_HEADER, new ForegroundColorSpan(COLOR_TAG),
                new StyleSpan(android.graphics.Typeface.BOLD));
        applySpan(sb, MD_HR, new ForegroundColorSpan(COLOR_COMMENT));
        applySpan(sb, MD_QUOTE, new ForegroundColorSpan(COLOR_COMMENT),
                new StyleSpan(android.graphics.Typeface.ITALIC));
        applySpan(sb, MD_INLINE_CODE, new ForegroundColorSpan(COLOR_STRING));
        applySpan(sb, MD_LINK, new ForegroundColorSpan(COLOR_KEYWORD));
        applySpan(sb, MD_LIST, new ForegroundColorSpan(COLOR_ATTR));
        applySpan(sb, MD_BOLD, new ForegroundColorSpan(COLOR_ATTR),
                new StyleSpan(android.graphics.Typeface.BOLD));
        applySpan(sb, MD_ITALIC, new ForegroundColorSpan(COLOR_STRING),
                new StyleSpan(android.graphics.Typeface.ITALIC));
    }

    /* ============== utilities ============== */

    private static void applySpan(SpannableStringBuilder sb, Pattern p, CharacterStyle... styles) {
        Matcher m = p.matcher(sb);
        while (m.find()) {
            for (CharacterStyle style : styles) {
                sb.setSpan(CharacterStyle.wrap(style), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static boolean hasSpan(SpannableStringBuilder sb, int start, int end) {
        CharacterStyle[] spans = sb.getSpans(start, end, CharacterStyle.class);
        return spans != null && spans.length > 0;
    }
}
