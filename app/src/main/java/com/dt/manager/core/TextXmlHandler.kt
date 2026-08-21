package com.dt.manager.core

import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/**
 * Handles Plain Text XML files (detection, pretty-printing, validation, and encoding).
 *
 * Distinguishes between compiled Android Binary XML (AXML) and Plain Text XML,
 * providing utilities to format, validate, and convert XML text without data loss.
 */
object TextXmlHandler {

    /**
     * Determines if given bytes represent a plain text XML file (rather than binary XML or non-XML).
     */
    @JvmStatic
    fun isTextXml(data: ByteArray?): Boolean {
        if (data == null || data.isEmpty()) return false
        if (BinaryXmlDecoder.isBinaryXml(data)) return false

        // Check for UTF BOMs or leading whitespace / '<'
        var start = 0
        if (data.size >= 3 && (data[0].toInt() and 0xFF) == 0xEF && (data[1].toInt() and 0xFF) == 0xBB && (data[2].toInt() and 0xFF) == 0xBF) {
            start = 3
        } else if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFE && (data[1].toInt() and 0xFF) == 0xFF) {
            start = 2
        } else if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xFE) {
            start = 2
        }

        // Skip ASCII whitespace
        while (start < data.size && (data[start] == ' '.code.toByte() || data[start] == '\t'.code.toByte() || data[start] == '\r'.code.toByte() || data[start] == '\n'.code.toByte())) {
            start++
        }

        if (start < data.size && data[start] == '<'.code.toByte()) {
            // Further verify first line or chunk
            val previewLen = Math.min(data.size - start, 512)
            val sample = String(data, start, previewLen, StandardCharsets.UTF_8).trim()
            if (sample.startsWith("<?xml") || sample.startsWith("<") && (sample.contains(">") || sample.contains("xmlns"))) {
                return true
            }
        }
        return false
    }

    /**
     * Formats (pretty-prints) a plain text XML string with 4-space indentation.
     */
    @JvmStatic
    fun formatXml(xmlString: String, indent: Int = 4): String {
        if (xmlString.isBlank()) return xmlString
        return try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = true
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(InputSource(ByteArrayInputStream(xmlString.toByteArray(StandardCharsets.UTF_8))))

            val tf = TransformerFactory.newInstance()
            val transformer = tf.newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty(OutputKeys.METHOD, "xml")
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", indent.toString())

            val writer = StringWriter()
            transformer.transform(DOMSource(doc), StreamResult(writer))
            writer.toString().trim()
        } catch (_: Exception) {
            // Fallback: rule-based formatter if DOM parser fails on non-standard Android attributes
            formatXmlFallback(xmlString, indent)
        }
    }

    /**
     * Fast regex-based fallback formatter for Android XMLs with unbound namespaces.
     */
    @JvmStatic
    fun formatXmlFallback(input: String, indentSpaces: Int = 4): String {
        val sb = StringBuilder()
        val indentUnit = " ".repeat(indentSpaces)
        var depth = 0
        val tokens = input.replace("><", ">\n<").lines()

        for (rawLine in tokens) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("</")) {
                depth = (depth - 1).coerceAtLeast(0)
                sb.append(indentUnit.repeat(depth)).append(line).append("\n")
            } else if (line.startsWith("<") && line.endsWith("/>")) {
                sb.append(indentUnit.repeat(depth)).append(line).append("\n")
            } else if (line.startsWith("<?") || line.startsWith("<!")) {
                sb.append(indentUnit.repeat(depth)).append(line).append("\n")
            } else if (line.startsWith("<") && !line.startsWith("</")) {
                sb.append(indentUnit.repeat(depth)).append(line).append("\n")
                if (!line.contains("</")) {
                    depth++
                }
            } else {
                sb.append(indentUnit.repeat(depth)).append(line).append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Validates XML syntax. Returns null if valid, or an error description string if invalid.
     */
    @JvmStatic
    fun validateXml(xmlString: String): String? {
        if (xmlString.isBlank()) return "Empty XML content"
        return try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            val db = dbf.newDocumentBuilder()
            db.parse(InputSource(ByteArrayInputStream(xmlString.toByteArray(StandardCharsets.UTF_8))))
            null
        } catch (e: Exception) {
            e.localizedMessage ?: e.message ?: "Invalid XML format"
        }
    }
}
