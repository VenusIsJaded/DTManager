package com.dt.manager.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Patches an Android binary XML (AXML) file by updating its string
 * pool with new values. This preserves the original structure, resource
 * map, attribute types, and everything else — only string values change.
 *
 * This is the correct way to round-trip binary XML: instead of decoding
 * to text and re-encoding (which loses resource IDs and type info), we
 * keep the original binary bytes and only swap out changed strings.
 */
object BinaryXmlPatcher {

    private val ATTR_PATTERN: Pattern = Pattern.compile("(\\w+(?::\\w+)?)=\"([^\"]*)\"")

    @JvmStatic
    fun patch(originalBinary: ByteArray, originalText: String, editedText: String): ByteArray? {
        return try {
            val poolInfo = extractStringPool(originalBinary) ?: return null
            val changes = diffXml(originalText, editedText)
            if (changes.isEmpty()) {
                return originalBinary
            }

            val updatedStrings = ArrayList(poolInfo.strings)
            for ((oldVal, newVal) in changes) {
                for (i in updatedStrings.indices) {
                    if (updatedStrings[i] == oldVal) {
                        updatedStrings[i] = newVal
                    }
                }
            }

            val newPoolBytes = encodeStringPool(updatedStrings, poolInfo.utf8)
            rebuildFile(originalBinary, poolInfo, newPoolBytes)
        } catch (_: Exception) {
            null
        }
    }

    private class StringPoolInfo {
        var chunkOffset: Int = 0
        var chunkSize: Int = 0
        var stringCount: Int = 0
        var flags: Int = 0
        var stringsStart: Int = 0
        var utf8: Boolean = false
        var strings: List<String> = emptyList()
    }

    private fun extractStringPool(data: ByteArray): StringPoolInfo? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 8) return null
        buf.position(8)

        while (buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            buf.short // headerSize
            val chunkSize = buf.int
            if (chunkSize < 8) break

            if (chunkType == 0x0001) { // STRING_POOL
                val info = StringPoolInfo()
                info.chunkOffset = chunkStart
                info.chunkSize = chunkSize
                info.stringCount = buf.int
                buf.int // styleCount
                info.flags = buf.int
                info.stringsStart = buf.int
                buf.int // stylesStart
                info.utf8 = (info.flags and 0x100) != 0

                val offsets = IntArray(info.stringCount)
                for (i in 0 until info.stringCount) {
                    offsets[i] = buf.int
                }

                val stringsBase = chunkStart + info.stringsStart
                val list = ArrayList<String>(info.stringCount)
                for (i in 0 until info.stringCount) {
                    list.add(readString(data, stringsBase + offsets[i], info.utf8))
                }
                info.strings = list
                return info
            }
            buf.position(chunkStart + chunkSize)
        }
        return null
    }

    private fun readString(data: ByteArray, pos: Int, utf8: Boolean): String {
        return if (utf8) {
            var p = pos
            val u16len = decodeLength8(data, p)
            p += if (u16len > 0x7F) 2 else 1
            val u8len = decodeLength8(data, p)
            p += if (u8len > 0x7F) 2 else 1
            val start = p
            val len = Math.min(u8len, data.size - start).coerceAtLeast(0)
            String(data, start, len, StandardCharsets.UTF_8)
        } else {
            val s0 = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            var len = s0
            var charsStart = pos + 2
            if ((s0 and 0x8000) != 0) {
                val s1 = (data[pos + 2].toInt() and 0xFF) or ((data[pos + 3].toInt() and 0xFF) shl 8)
                len = ((s0 and 0x7FFF) shl 16) or s1
                charsStart = pos + 4
            }
            val byteLen = Math.min(len * 2, data.size - charsStart).coerceAtLeast(0)
            val chars = ByteArray(byteLen)
            System.arraycopy(data, charsStart, chars, 0, byteLen)
            String(chars, StandardCharsets.UTF_16LE)
        }
    }

    private fun decodeLength8(data: ByteArray, offset: Int): Int {
        val b0 = data[offset].toInt() and 0xFF
        if ((b0 and 0x80) != 0) {
            val b1 = data[offset + 1].toInt() and 0xFF
            return ((b0 and 0x7F) shl 8) or b1
        }
        return b0
    }

    private fun diffXml(originalText: String, editedText: String): Map<String, String> {
        val changes = LinkedHashMap<String, String>()
        val origMatcher = ATTR_PATTERN.matcher(originalText)
        val editedMatcher = ATTR_PATTERN.matcher(editedText)

        val origValues = ArrayList<String>()
        val editedValues = ArrayList<String>()
        while (origMatcher.find()) origValues.add(origMatcher.group(2) ?: "")
        while (editedMatcher.find()) editedValues.add(editedMatcher.group(2) ?: "")

        val count = Math.min(origValues.size, editedValues.size)
        for (i in 0 until count) {
            val origVal = origValues[i]
            val editedVal = editedValues[i]
            if (origVal != editedVal && origVal.isNotEmpty() && editedVal.isNotEmpty()) {
                if (!changes.containsKey(origVal)) {
                    changes[origVal] = editedVal
                }
            }
        }
        return changes
    }

    private fun encodeStringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val headerSize = 28
        val offsetsSize = strings.size * 4
        var stringsDataSize = 0

        for (s in strings) {
            if (utf8) {
                val byteLen = s.toByteArray(StandardCharsets.UTF_8).size
                stringsDataSize += length8Size(s.length) + length8Size(byteLen) + byteLen + 1
            } else {
                stringsDataSize += 2 + s.length * 2 + 2
            }
        }
        val stringsStart = headerSize + offsetsSize
        var chunkSize = stringsStart + stringsDataSize

        // 4-byte boundary alignment
        if (chunkSize % 4 != 0) {
            chunkSize += (4 - (chunkSize % 4))
        }

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001.toShort()) // type
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(if (utf8) 0x100 else 0) // flags
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart

        var currentOffset = 0
        for (s in strings) {
            buf.putInt(currentOffset)
            if (utf8) {
                val byteLen = s.toByteArray(StandardCharsets.UTF_8).size
                currentOffset += length8Size(s.length) + length8Size(byteLen) + byteLen + 1
            } else {
                currentOffset += 2 + s.length * 2 + 2
            }
        }

        for (s in strings) {
            if (utf8) {
                val strBytes = s.toByteArray(StandardCharsets.UTF_8)
                writeLength8(buf, s.length)
                writeLength8(buf, strBytes.size)
                buf.put(strBytes)
                buf.put(0.toByte())
            } else {
                buf.putShort(s.length.toShort())
                val strBytes = s.toByteArray(StandardCharsets.UTF_16LE)
                buf.put(strBytes)
                buf.putShort(0.toShort())
            }
        }

        return buf.array()
    }

    private fun length8Size(value: Int): Int = if (value > 0x7F) 2 else 1

    private fun writeLength8(buf: ByteBuffer, value: Int) {
        if (value > 0x7F) {
            buf.put((0x80 or ((value shr 8) and 0x7F)).toByte())
            buf.put((value and 0xFF).toByte())
        } else {
            buf.put(value.toByte())
        }
    }

    private fun rebuildFile(original: ByteArray, poolInfo: StringPoolInfo, newPoolBytes: ByteArray): ByteArray {
        val oldPoolEnd = poolInfo.chunkOffset + poolInfo.chunkSize
        val restStart = oldPoolEnd
        val restLength = original.size - restStart
        val newFileSize = 8 + newPoolBytes.size + restLength

        val out = ByteBuffer.allocate(newFileSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put(original, 0, 4) // magic
        out.putInt(newFileSize)
        out.put(newPoolBytes)
        if (restLength > 0) {
            out.put(original, restStart, restLength)
        }
        return out.array()
    }
}
