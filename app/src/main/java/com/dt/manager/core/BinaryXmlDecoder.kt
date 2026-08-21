package com.dt.manager.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Decodes Android binary XML (AXML) files into human-readable text XML.
 *
 * Binary XML is the format used for AndroidManifest.xml and other XML
 * resources inside an APK — they're not stored as text. This parser
 * walks the chunks (string pool, resource map, XML namespace/element
 * events) and reconstructs the original XML text with high performance.
 *
 * Reference: AOSP frameworks/base/include/androidfw/ResourceTypes.h
 */
class BinaryXmlDecoder private constructor(data: ByteArray) {

    private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private var stringPool: Array<String> = emptyArray()
    private var resourceIds: IntArray = IntArray(0)

    companion object {
        private const val MAGIC_BINARY_XML = 0x00080003

        @JvmStatic
        fun isBinaryXml(data: ByteArray?): Boolean {
            if (data == null || data.size < 8) return false
            val magic = (data[3].toInt() shl 24) or
                    ((data[2].toInt() and 0xFF) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[0].toInt() and 0xFF)
            return magic == MAGIC_BINARY_XML
        }

        @JvmStatic
        fun decode(data: ByteArray): String {
            val d = BinaryXmlDecoder(data)
            return d.decodeInternal()
        }
    }

    private fun decodeInternal(): String {
        val out = StringBuilder(buf.capacity())
        if (buf.remaining() < 8) return ""
        buf.position(8)

        var currentIndent = ""

        while (buf.remaining() >= 8) {
            val chunkType = buf.short.toInt() and 0xFFFF
            val chunkHeader = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int
            if (chunkSize < 8) break
            val startPos = buf.position() - 8

            when (chunkType) {
                0x0001 -> { // RES_STRING_POOL_TYPE
                    parseStringPool(chunkSize)
                }
                0x0180 -> { // RES_XML_RESOURCE_MAP_TYPE
                    parseResourceMap(chunkSize)
                }
                0x0100, 0x0101 -> { // RES_XML_START_NAMESPACE_TYPE / RES_XML_END_NAMESPACE_TYPE
                    buf.position(startPos + chunkSize)
                }
                0x0102 -> { // RES_XML_START_ELEMENT_TYPE
                    currentIndent = parseStartElement(out, currentIndent)
                }
                0x0103 -> { // RES_XML_END_ELEMENT_TYPE
                    currentIndent = parseEndElement(out, currentIndent)
                }
                0x0104 -> { // RES_XML_CDATA_TYPE
                    parseCData(out, currentIndent)
                    buf.position(startPos + chunkSize)
                }
                else -> {
                    buf.position(startPos + chunkSize)
                }
            }
        }

        return out.toString()
    }

    private fun parseStringPool(chunkSize: Int) {
        val poolStart = buf.position() - 8
        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        val stylesStart = buf.int

        val utf8 = (flags and 0x100) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buf.int
        }

        stringPool = Array(stringCount) { "" }
        val stringsBase = poolStart + stringsStart
        for (i in 0 until stringCount) {
            val strPos = stringsBase + offsets[i]
            stringPool[i] = readString(strPos, utf8)
        }

        buf.position(poolStart + chunkSize)
    }

    private fun readString(pos: Int, utf8: Boolean): String {
        val oldPos = buf.position()
        buf.position(pos)
        try {
            return if (utf8) {
                val u16len = decodeLength8(pos)
                val u8lenOffset = pos + if (u16len > 0x7F) 2 else 1
                val u8len = decodeLength8(u8lenOffset)
                val start = u8lenOffset + if (u8len > 0x7F) 2 else 1
                val data = ByteArray(u8len)
                buf.position(start)
                buf.get(data)
                String(data, StandardCharsets.UTF_8)
            } else {
                val s0 = buf.short.toInt() and 0xFFFF
                var len = s0
                if ((s0 and 0x8000) != 0) {
                    val s1 = buf.short.toInt() and 0xFFFF
                    len = ((s0 and 0x7FFF) shl 16) or s1
                }
                val chars = CharArray(len)
                for (i in 0 until len) {
                    chars[i] = buf.char
                }
                String(chars)
            }
        } finally {
            buf.position(oldPos)
        }
    }

    private fun decodeLength8(offset: Int): Int {
        val b0 = buf.get(offset).toInt() and 0xFF
        if ((b0 and 0x80) != 0) {
            val b1 = buf.get(offset + 1).toInt() and 0xFF
            return ((b0 and 0x7F) shl 8) or b1
        }
        return b0
    }

    private fun parseResourceMap(chunkSize: Int) {
        val mapStart = buf.position() - 8
        val count = (chunkSize - 8) / 4
        resourceIds = IntArray(count)
        for (i in 0 until count) {
            resourceIds[i] = buf.int
        }
        buf.position(mapStart + chunkSize)
    }

    private fun parseStartElement(out: StringBuilder, indent: String): String {
        val chunkStart = buf.position() - 8
        buf.int // lineNumber
        buf.int // comment
        val nsIdx = buf.int
        val nameIdx = buf.int
        val attrStart = buf.short.toInt() and 0xFFFF
        val attrSize = buf.short.toInt() and 0xFFFF
        val attrCount = buf.short.toInt() and 0xFFFF
        buf.short // idIdx
        buf.short // classIdx
        buf.short // styleIdx

        val name = stringAt(nameIdx)
        out.append(indent).append("<").append(name)

        buf.position(chunkStart + 8 + 8 + attrStart)

        for (i in 0 until attrCount) {
            val aNs = buf.int
            val aName = buf.int
            val aRaw = buf.int
            buf.short // typeSize
            buf.get()   // res0
            val aDataType = buf.get()
            val aData = buf.int

            val attrName = stringAt(aName)
            val nsPrefix = resolveNsPrefix(aNs)

            out.append(" ")
            if (!nsPrefix.isNullOrEmpty()) {
                out.append(nsPrefix).append(":")
            }
            out.append(attrName).append("=\"")

            val value = resolveAttrValue(aDataType, aData, aRaw)
            out.append(escapeXml(value)).append("\"")
        }
        out.append(">\n")
        return "$indent    "
    }

    private fun parseEndElement(out: StringBuilder, indent: String): String {
        buf.int // lineNumber
        buf.int // comment
        buf.int // ns
        val nameIdx = buf.int
        val name = stringAt(nameIdx)

        val parentIndent = if (indent.length >= 4) indent.substring(0, indent.length - 4) else ""
        out.append(parentIndent).append("</").append(name).append(">\n")
        return parentIndent
    }

    private fun parseCData(out: StringBuilder, indent: String) {
        buf.int // lineNumber
        buf.int // comment
        val dataIdx = buf.int
        val cdata = stringAt(dataIdx)
        if (cdata.isNotEmpty()) {
            out.append(indent).append("<![CDATA[").append(cdata).append("]]>\n")
        }
    }

    private fun stringAt(idx: Int): String {
        if (idx < 0 || idx >= stringPool.size) return ""
        return stringPool[idx]
    }

    private fun resolveNsPrefix(nsIdx: Int): String? {
        if (nsIdx < 0 || nsIdx >= stringPool.size) return null
        val ns = stringPool[nsIdx]
        if (ns.isEmpty()) return null
        return when (ns) {
            "http://schemas.android.com/apk/res/android",
            "http://schemas.android.com/apk/prv/res/android" -> "android"
            "http://schemas.android.com/apk/res-auto" -> "app"
            "http://schemas.android.com/tools" -> "tools"
            else -> {
                val slash = ns.lastIndexOf('/')
                if (slash in 0 until ns.length - 1) ns.substring(slash + 1) else null
            }
        }
    }

    private fun resolveAttrValue(dataType: Byte, data: Int, rawValueIdx: Int): String {
        return when (dataType.toInt()) {
            0x00 -> "" // TYPE_NULL
            0x01 -> "@0x" + Integer.toHexString(data) // TYPE_REFERENCE
            0x02 -> "?0x" + Integer.toHexString(data) // TYPE_ATTRIBUTE
            0x03 -> stringAt(rawValueIdx) // TYPE_STRING
            0x04 -> java.lang.Float.intBitsToFloat(data).toString() // TYPE_FLOAT
            0x05 -> formatDimension(data) // TYPE_DIMENSION
            0x06 -> { // TYPE_FRACTION
                val f = ((data.toLong() and 0xFFFFFFFFL) / (1 shl (data ushr 28 and 0xF)).toFloat()) * 100
                "$f%"
            }
            0x10 -> data.toString() // TYPE_INT_DEC
            0x11 -> "0x" + Integer.toHexString(data) // TYPE_INT_HEX
            0x12 -> if (data == 0) "false" else "true" // TYPE_INT_BOOLEAN
            0x1c, 0x1d -> String.format("#%08x", data) // TYPE_INT_COLOR_ARGB8
            0x1e, 0x1f -> String.format("#%06x", data and 0xFFFFFF) // TYPE_INT_COLOR_RGB8
            else -> data.toString()
        }
    }

    private fun formatDimension(data: Int): String {
        val unit = data ushr 28 and 0xF
        val value = data and 0x07FFFFFF
        return when (unit) {
            0 -> "${value}px"
            1 -> "${value}dp"
            2 -> "${value}sp"
            3 -> "${value}pt"
            4 -> "${value}in"
            5 -> "${value}mm"
            else -> "$value (unit $unit)"
        }
    }

    private fun escapeXml(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length + 8)
        for (i in 0 until s.length) {
            when (val c = s[i]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
