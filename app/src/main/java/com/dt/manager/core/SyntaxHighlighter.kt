package com.dt.manager.core

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.util.regex.Pattern

/**
 * Applies syntax highlighting spans to source text. Supports XML (both binary decoded & text XML),
 * JSON, smali, Java, Kotlin, Markdown, and plain code.
 *
 * Color scheme (matches MT Manager):
 *   - Tags / keywords    → orange (#FFB74D)
 *   - Attributes / keys  → purple (#B388FF)
 *   - Strings / values   → green (#A5D6A7)
 *   - Comments           → gray (#808080, italic)
 *   - Numbers            → amber (#FFCC80)
 *   - Punctuation        → white (#FFFFFF)
 */
object SyntaxHighlighter {

    const val COLOR_TAG = -0x48b3 // 0xFFFFB74D (orange)
    const val COLOR_ATTR = -0x4c7701 // 0xFFB388FF (purple)
    const val COLOR_STRING = -0x5a2959 // 0xFFA5D6A7 (green)
    const val COLOR_COMMENT = -0x7f7f80 // 0xFF808080 (gray)
    const val COLOR_NUMBER = -0x3380 // 0xFFFFCC80 (amber)
    const val COLOR_KEYWORD = -0x6f3507 // 0xFF90CAF9 (light blue)
    const val COLOR_PUNCT = -0x1 // 0xFFFFFFFF (white)
    const val COLOR_DEFAULT = -0x111112 // 0xFFEEEEEE (off-white)

    enum class Language {
        XML, JSON, SMALI, JAVA, KOTLIN, MARKDOWN, TEXT
    }

    @JvmStatic
    fun detectLanguage(fileName: String?): Language {
        if (fileName == null) return Language.TEXT
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".xml") -> Language.XML
            lower.endsWith(".json") -> Language.JSON
            lower.endsWith(".smali") -> Language.SMALI
            lower.endsWith(".kt") || lower.endsWith(".kts") -> Language.KOTLIN
            lower.endsWith(".java") -> Language.JAVA
            lower.endsWith(".md") || lower.endsWith(".markdown") -> Language.MARKDOWN
            else -> Language.TEXT
        }
    }

    @JvmStatic
    fun highlight(text: String?, lang: Language): Spannable {
        val sb = SpannableStringBuilder(text ?: "")
        if (text.isNullOrEmpty()) return sb

        when (lang) {
            Language.XML -> highlightXml(sb)
            Language.JSON -> highlightJson(sb)
            Language.SMALI -> highlightSmali(sb)
            Language.JAVA -> highlightJava(sb)
            Language.KOTLIN -> highlightKotlin(sb)
            Language.MARKDOWN -> highlightMarkdown(sb)
            Language.TEXT -> {}
        }
        return sb
    }

    /* ============== XML ============== */

    private val XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->")
    private val XML_TAG = Pattern.compile("<[/]?[a-zA-Z_][\\w:.-]*")
    private val XML_ATTR = Pattern.compile("\\s([a-zA-Z_][\\w:.-]*)(?==)")
    private val XML_STRING = Pattern.compile("\"[^\"]*\"|'[^']*'")

    private fun highlightXml(sb: SpannableStringBuilder) {
        applySpan(sb, XML_COMMENT, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, XML_STRING, ForegroundColorSpan(COLOR_STRING))
        applySpan(sb, XML_ATTR, ForegroundColorSpan(COLOR_ATTR))
        applySpan(sb, XML_TAG, ForegroundColorSpan(COLOR_TAG))
    }

    /* ============== JSON ============== */

    private val JSON_KEY = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"(?=\\s*:)")
    private val JSON_STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"")
    private val JSON_NUMBER = Pattern.compile("\\b-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")
    private val JSON_KEYWORD = Pattern.compile("\\b(?:true|false|null)\\b")

    private fun highlightJson(sb: SpannableStringBuilder) {
        val mKey = JSON_KEY.matcher(sb)
        while (mKey.find()) {
            sb.setSpan(ForegroundColorSpan(COLOR_ATTR), mKey.start(), mKey.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val mStr = JSON_STRING.matcher(sb)
        while (mStr.find()) {
            if (!hasSpan(sb, mStr.start(), mStr.end())) {
                sb.setSpan(ForegroundColorSpan(COLOR_STRING), mStr.start(), mStr.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        applySpan(sb, JSON_NUMBER, ForegroundColorSpan(COLOR_NUMBER))
        applySpan(sb, JSON_KEYWORD, ForegroundColorSpan(COLOR_KEYWORD))
    }

    /* ============== Smali / Java / Kotlin ============== */

    private val SMALI_COMMENT = Pattern.compile("#[^\\n]*")
    private val JAVA_COMMENT_LINE = Pattern.compile("//[^\\n]*")
    private val JAVA_COMMENT_BLOCK = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val SMALI_STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"")
    private val SMALI_NUMBER = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b-?\\d+(?:\\.\\d+)?[fFlL]?\\b")
    private val SMALI_KEYWORD = Pattern.compile(
        "\\b(class|super|implements|extends|public|private|protected|static|final|abstract|" +
                "interface|enum|annotation|field|method|registers|locals|param|prologue|line|end|" +
                "void|boolean|byte|char|short|int|long|float|double|" +
                "if|else|while|for|return|new|this|super|true|false|null)\\b"
    )
    private val KOTLIN_KEYWORD = Pattern.compile(
        "\\b(val|var|fun|class|interface|object|package|import|return|if|else|when|while|for|" +
                "in|is|as|null|true|false|this|super|typealias|companion|override|private|protected|" +
                "public|internal|open|abstract|final|sealed|data|inline|reified|suspend)\\b"
    )
    private val SMALI_TYPE = Pattern.compile("L[\\w/$]+;")

    private fun highlightSmali(sb: SpannableStringBuilder) {
        applySpan(sb, SMALI_COMMENT, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, SMALI_STRING, ForegroundColorSpan(COLOR_STRING))
        applySpan(sb, SMALI_TYPE, ForegroundColorSpan(COLOR_TAG))
        applySpan(sb, SMALI_NUMBER, ForegroundColorSpan(COLOR_NUMBER))
        applySpan(sb, SMALI_KEYWORD, ForegroundColorSpan(COLOR_KEYWORD), StyleSpan(Typeface.BOLD))
    }

    private fun highlightJava(sb: SpannableStringBuilder) {
        applySpan(sb, JAVA_COMMENT_BLOCK, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, JAVA_COMMENT_LINE, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, SMALI_STRING, ForegroundColorSpan(COLOR_STRING))
        applySpan(sb, SMALI_NUMBER, ForegroundColorSpan(COLOR_NUMBER))
        applySpan(sb, SMALI_KEYWORD, ForegroundColorSpan(COLOR_KEYWORD), StyleSpan(Typeface.BOLD))
    }

    private fun highlightKotlin(sb: SpannableStringBuilder) {
        applySpan(sb, JAVA_COMMENT_BLOCK, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, JAVA_COMMENT_LINE, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, SMALI_STRING, ForegroundColorSpan(COLOR_STRING))
        applySpan(sb, SMALI_NUMBER, ForegroundColorSpan(COLOR_NUMBER))
        applySpan(sb, KOTLIN_KEYWORD, ForegroundColorSpan(COLOR_KEYWORD), StyleSpan(Typeface.BOLD))
    }

    /* ============== Markdown ============== */

    private val MD_HEADER = Pattern.compile("(?m)^#{1,6} .+$")
    private val MD_CODE_BLOCK = Pattern.compile("(?ms)```.*?```")
    private val MD_INLINE_CODE = Pattern.compile("`[^`\\n]+`")
    private val MD_BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val MD_ITALIC = Pattern.compile("(?<![\\*_])(\\*|_)(?=\\S)(?!\\1)(.+?)(?<=\\S)\\1(?![\\*_])")
    private val MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\s)]+)\\)")
    private val MD_QUOTE = Pattern.compile("(?m)^> .+$")
    private val MD_LIST = Pattern.compile("(?m)^\\s*[-*+] .+$")
    private val MD_HR = Pattern.compile("(?m)^---+$")

    private fun highlightMarkdown(sb: SpannableStringBuilder) {
        applySpan(sb, MD_CODE_BLOCK, ForegroundColorSpan(COLOR_STRING))
        applySpan(sb, MD_HEADER, ForegroundColorSpan(COLOR_TAG), StyleSpan(Typeface.BOLD))
        applySpan(sb, MD_HR, ForegroundColorSpan(COLOR_COMMENT))
        applySpan(sb, MD_QUOTE, ForegroundColorSpan(COLOR_COMMENT), StyleSpan(Typeface.ITALIC))
        applySpan(sb, MD_INLINE_CODE, ForegroundColorSpan(COLOR_STRING))
        applySpan(sb, MD_LINK, ForegroundColorSpan(COLOR_KEYWORD))
        applySpan(sb, MD_LIST, ForegroundColorSpan(COLOR_ATTR))
        applySpan(sb, MD_BOLD, ForegroundColorSpan(COLOR_ATTR), StyleSpan(Typeface.BOLD))
        applySpan(sb, MD_ITALIC, ForegroundColorSpan(COLOR_STRING), StyleSpan(Typeface.ITALIC))
    }

    private fun applySpan(sb: SpannableStringBuilder, p: Pattern, vararg styles: CharacterStyle) {
        val m = p.matcher(sb)
        while (m.find()) {
            for (style in styles) {
                sb.setSpan(CharacterStyle.wrap(style), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun hasSpan(sb: SpannableStringBuilder, start: Int, end: Int): Boolean {
        val spans = sb.getSpans(start, end, CharacterStyle::class.java)
        return spans != null && spans.isNotEmpty()
    }
}
