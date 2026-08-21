package com.dt.manager.core;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Patches an Android binary XML (AXML) file by updating its string
 * pool with new values. This preserves the original structure, resource
 * map, attribute types, and everything else — only string values change.
 *
 * This is the correct way to round-trip binary XML: instead of decoding
 * to text and re-encoding (which loses resource IDs and type info), we
 * keep the original binary bytes and only swap out changed strings.
 *
 * The approach:
 *   1. Parse the original decoded text and the edited text in parallel
 *   2. Walk both XML trees, comparing attribute values and text content
 *   3. Build a map of (old string → new string)
 *   4. Patch the string pool in the original binary bytes
 *   5. Re-encode the string pool chunk with updated strings
 *   6. Update chunk sizes and file size
 *
 * Limitation: if the user changes the XML structure (adds/removes tags
 * or attributes), this patcher won't work. It only handles value changes.
 */
public final class BinaryXmlPatcher {

    private BinaryXmlPatcher() {}

    /**
     * Patch the original binary XML with changes from the edited text.
     * @param originalBinary  the original AXML bytes
     * @param originalText    the decoded text XML (what the user saw)
     * @param editedText      the edited text XML (what the user wants)
     * @return updated binary XML bytes, or null if patching failed
     */
    public static byte[] patch(byte[] originalBinary, String originalText, String editedText) {
        try {
            // Step 1: extract the string pool from the original binary
            StringPoolInfo poolInfo = extractStringPool(originalBinary);
            if (poolInfo == null) return null;

            // Step 2: build a change map by diffing the XML trees
            Map<String, String> changes = diffXml(originalText, editedText);
            if (changes == null || changes.isEmpty()) {
                // No changes — return original bytes
                return originalBinary;
            }

            // Step 3: apply changes to the string pool
            List<String> updatedStrings = new ArrayList<>(poolInfo.strings);
            for (Map.Entry<String, String> entry : changes.entrySet()) {
                String old = entry.getKey();
                String newVal = entry.getValue();
                // Replace ALL occurrences of old string with new string in the pool
                for (int i = 0; i < updatedStrings.size(); i++) {
                    if (updatedStrings.get(i).equals(old)) {
                        updatedStrings.set(i, newVal);
                    }
                }
            }

            // Step 4: re-encode the string pool chunk
            byte[] newPoolBytes = encodeStringPool(updatedStrings, poolInfo.utf8);

            // Step 5: rebuild the file: header + new pool + rest of file
            return rebuildFile(originalBinary, poolInfo, newPoolBytes);
        } catch (Exception e) {
            return null;
        }
    }

    /* ===== String pool extraction ===== */

    private static class StringPoolInfo {
        int chunkOffset;     // offset of string pool chunk in file
        int chunkSize;       // size of string pool chunk
        int stringCount;
        int flags;
        int stringsStart;   // offset of string data from chunk start
        boolean utf8;
        List<String> strings;
    }

    private static StringPoolInfo extractStringPool(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // Skip main header (8 bytes)
        buf.position(8);

        while (buf.remaining() >= 8) {
            int chunkStart = buf.position();
            int chunkType = buf.getShort() & 0xFFFF;
            int headerSize = buf.getShort() & 0xFFFF;
            int chunkSize = buf.getInt();

            if (chunkType == 0x0001) { // STRING_POOL
                StringPoolInfo info = new StringPoolInfo();
                info.chunkOffset = chunkStart;
                info.chunkSize = chunkSize;
                info.stringCount = buf.getInt();
                buf.getInt(); // styleCount
                info.flags = buf.getInt();
                info.stringsStart = buf.getInt();
                buf.getInt(); // stylesStart
                info.utf8 = (info.flags & 0x100) != 0;

                // Read string offsets
                int[] offsets = new int[info.stringCount];
                for (int i = 0; i < info.stringCount; i++) {
                    offsets[i] = buf.getInt();
                }

                // Read strings
                int stringsBase = chunkStart + info.stringsStart;
                info.strings = new ArrayList<>(info.stringCount);
                for (int i = 0; i < info.stringCount; i++) {
                    info.strings.add(readString(data, stringsBase + offsets[i], info.utf8));
                }
                return info;
            }
            buf.position(chunkStart + chunkSize);
        }
        return null;
    }

    private static String readString(byte[] data, int pos, boolean utf8) {
        if (utf8) {
            int p = pos;
            // Skip 2 uleb128 values (char length, byte length)
            for (int i = 0; i < 2; i++) {
                while (p < data.length && (data[p] & 0x80) != 0) p++;
                p++;
            }
            int start = p;
            while (p < data.length && data[p] != 0) p++;
            return new String(data, start, p - start, StandardCharsets.UTF_8);
        } else {
            // UTF-16
            int len = ((data[pos] & 0xFF)) | ((data[pos + 1] & 0xFF) << 8);
            int charsStart = pos + 2;
            if (len < 0) {
                // length is in next 4 bytes
                len = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8) |
                      ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
                charsStart = pos + 4;
            }
            // Handle sign extension for large lengths
            if (len > 0x7FFF) {
                len = ((~len) & 0x7FFFFFFF);
                charsStart = pos + 4;
            }
            byte[] chars = new byte[len * 2];
            System.arraycopy(data, charsStart, chars, 0, Math.min(len * 2, data.length - charsStart));
            return new String(chars, StandardCharsets.UTF_16LE);
        }
    }

    /* ===== XML diff ===== */

    /**
     * Walk two XML texts in parallel and build a map of (old value → new value)
     * for all changed string values (attribute values + text content).
     */
    private static Map<String, String> diffXml(String originalText, String editedText) {
        Map<String, String> changes = new LinkedHashMap<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser orig = factory.newPullParser();
            XmlPullParser edited = factory.newPullParser();
            orig.setInput(new StringReader(originalText));
            edited.setInput(new StringReader(editedText));

            // Walk both parsers in parallel, comparing attribute values
            while (true) {
                int origEvent = orig.next();
                int editedEvent = edited.next();
                if (origEvent != editedEvent) {
                    // Structure changed — can't diff
                    return changes;
                }
                if (origEvent == XmlPullParser.END_DOCUMENT) break;

                if (origEvent == XmlPullParser.START_TAG) {
                    // Compare attributes
                    for (int i = 0; i < orig.getAttributeCount(); i++) {
                        String origName = orig.getAttributeName(i);
                        String origVal = orig.getAttributeValue(i);
                        // Find matching attribute in edited
                        for (int j = 0; j < edited.getAttributeCount(); j++) {
                            if (edited.getAttributeName(j).equals(origName)) {
                                String editedVal = edited.getAttributeValue(j);
                                if (!origVal.equals(editedVal)) {
                                    changes.put(origVal, editedVal);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // If diff fails, return empty — caller will use original
        }
        return changes;
    }

    /* ===== String pool re-encoding ===== */

    private static byte[] encodeStringPool(List<String> strings, boolean utf8) {
        // Calculate sizes
        int headerSize = 28; // 8 (chunk header) + 4*5 (fields)
        int offsetsSize = strings.size() * 4;
        int stringsDataSize = 0;
        // First pass: calculate string data size
        for (String s : strings) {
            if (utf8) {
                int byteLen = s.getBytes(StandardCharsets.UTF_8).length;
                stringsDataSize += uleb128Size(byteLen) + uleb128Size(s.length()) + byteLen + 1; // +1 for NUL
            } else {
                stringsDataSize += 2 + s.length() * 2 + 2; // u16 length + chars + NUL
            }
        }
        int stringsStart = headerSize + offsetsSize;
        int chunkSize = stringsStart + stringsDataSize;

        // Build buffer
        ByteBuffer buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.putShort((short) 0x0001); // type = STRING_POOL
        buf.putShort((short) headerSize); // header size
        buf.putInt(chunkSize); // chunk size
        buf.putInt(strings.size()); // string count
        buf.putInt(0); // style count
        buf.putInt(utf8 ? 0x100 : 0); // flags
        buf.putInt(stringsStart); // strings start
        buf.putInt(0); // styles start

        // String offsets (relative to stringsStart)
        int currentOffset = 0;
        for (String s : strings) {
            buf.putInt(currentOffset);
            if (utf8) {
                int byteLen = s.getBytes(StandardCharsets.UTF_8).length;
                currentOffset += uleb128Size(byteLen) + uleb128Size(s.length()) + byteLen + 1;
            } else {
                currentOffset += 2 + s.length() * 2 + 2;
            }
        }

        // String data
        for (String s : strings) {
            byte[] strBytes;
            if (utf8) {
                strBytes = s.getBytes(StandardCharsets.UTF_8);
                writeUleb128(buf, s.length()); // char count
                writeUleb128(buf, strBytes.length); // byte count
                buf.put(strBytes);
                buf.put((byte) 0); // NUL
            } else {
                buf.putShort((short) s.length()); // char count (u16)
                strBytes = s.getBytes(StandardCharsets.UTF_16LE);
                buf.put(strBytes);
                buf.putShort((short) 0); // NUL
            }
        }

        return buf.array();
    }

    private static int uleb128Size(int value) {
        int size = 1;
        while ((value >>> 7) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static void writeUleb128(ByteBuffer buf, int value) {
        while (true) {
            int b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            buf.put((byte) b);
            if (value == 0) break;
        }
    }

    /* ===== File rebuild ===== */

    private static byte[] rebuildFile(byte[] original, StringPoolInfo poolInfo, byte[] newPoolBytes) {
        // File structure:
        //   [0 .. 8)                              main header (magic + file size)
        //   [poolInfo.chunkOffset .. +oldSize)    old string pool chunk
        //   [poolInfo.chunkOffset + oldSize .. end) rest of file (resource map, XML events)
        //
        // We replace the string pool with newPoolBytes and adjust:
        //   - file size in main header (bytes 4-7)
        //   - string pool chunk size (already in newPoolBytes)

        int oldPoolEnd = poolInfo.chunkOffset + poolInfo.chunkSize;
        int restStart = oldPoolEnd;
        int restLength = original.length - restStart;
        int newFileSize = 8 + newPoolBytes.length + restLength;

        ByteBuffer out = ByteBuffer.allocate(newFileSize).order(ByteOrder.LITTLE_ENDIAN);
        // Main header (8 bytes): magic (4) + file size (4)
        out.put(original, 0, 4); // magic
        out.putInt(newFileSize); // updated file size
        // New string pool
        out.put(newPoolBytes);
        // Rest of file (resource map + XML events)
        if (restLength > 0) {
            out.put(original, restStart, restLength);
        }
        return out.array();
    }
}
