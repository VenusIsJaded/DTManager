package com.dt.manager.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Decodes Android binary XML (AXML) files into human-readable text XML.
 *
 * Binary XML is the format used for AndroidManifest.xml and other XML
 * resources inside an APK — they're not stored as text. This parser
 * walks the chunks (string pool, resource map, XML namespace/element
 * events) and reconstructs the original XML text.
 *
 * Reference: AOSP frameworks/base/include/androidfw/ResourceTypes.h
 */
public final class BinaryXmlDecoder {

    private ByteBuffer buf;
    private String[] stringPool;
    private int[] resourceIds;

    private BinaryXmlDecoder(byte[] data) {
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static boolean isBinaryXml(byte[] data) {
        if (data == null || data.length < 8) return false;
        // RES_XML_TYPE = 0x0003, header type 0x0008 → first int LE = 0x00080003
        int magic = (data[3] << 24) | ((data[2] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        return magic == 0x00080003;
    }

    public static String decode(byte[] data) {
        BinaryXmlDecoder d = new BinaryXmlDecoder(data);
        return d.decodeInternal();
    }

    private String decodeInternal() {
        StringBuilder out = new StringBuilder();
        // Header: magic(4), file_size(4)
        buf.position(8);

        String currentIndent = "";
        boolean firstElement = true;

        while (buf.remaining() >= 8) {
            int chunkType = buf.getShort() & 0xFFFF;
            int chunkHeader = buf.getShort() & 0xFFFF;
            int chunkSize = buf.getInt();
            int startPos = buf.position() - 8;

            switch (chunkType) {
                case 0x0001: // RES_STRING_POOL_TYPE
                    parseStringPool(chunkSize);
                    break;
                case 0x0180: // RES_XML_RESOURCE_MAP_TYPE
                    parseResourceMap(chunkSize);
                    break;
                case 0x0100: // RES_XML_START_NAMESPACE_TYPE
                    // skip header fields
                    buf.position(startPos + chunkSize);
                    break;
                case 0x0101: // RES_XML_END_NAMESPACE_TYPE
                    buf.position(startPos + chunkSize);
                    break;
                case 0x0102: // RES_XML_START_ELEMENT_TYPE
                    currentIndent = parseStartElement(out, currentIndent);
                    break;
                case 0x0103: // RES_XML_END_ELEMENT_TYPE
                    currentIndent = parseEndElement(out, currentIndent);
                    break;
                default:
                    // Unknown chunk — skip
                    buf.position(startPos + chunkSize);
                    break;
            }
        }

        return out.toString();
    }

    private void parseStringPool(int chunkSize) {
        int poolStart = buf.position() - 8;
        // header: type(2)+headerSize(2)+size(4) [already read]
        int stringCount = buf.getInt();
        int styleCount = buf.getInt();
        int flags = buf.getInt();
        int stringsStart = buf.getInt();
        int stylesStart = buf.getInt();

        boolean utf8 = (flags & 0x100) != 0;

        // Read string offsets
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = buf.getInt();
        }

        stringPool = new String[stringCount];
        int stringsBase = poolStart + stringsStart;
        for (int i = 0; i < stringCount; i++) {
            int strPos = stringsBase + offsets[i];
            stringPool[i] = readString(strPos, utf8);
        }

        // Position to end of chunk
        buf.position(poolStart + chunkSize);
    }

    private String readString(int pos, boolean utf8) {
        int oldPos = buf.position();
        buf.position(pos);
        try {
            if (utf8) {
                // uleb128 length, then uleb128 byte length, then bytes, then NUL
                int[] len = readUleb128(pos);
                int[] byteLen = readUleb128(len[1]);
                int start = byteLen[1];
                int end = start;
                while (end < buf.limit() && buf.get(end) != 0) end++;
                byte[] data = new byte[end - start];
                buf.position(start);
                buf.get(data);
                return new String(data, StandardCharsets.UTF_8);
            } else {
                // UTF-16: u16 length, u16 chars, u16 NUL
                int len = buf.getShort() & 0xFFFF;
                // Some files use the high bit as a flag for "length is in next int"
                if ((len & 0x80000000) != 0) {
                    // Not possible for u16
                }
                // Android uses a slightly different scheme: if the high bit of the
                // u16 length is set, the real length is a following u32.
                // Per docs: if length has its high bit set, the next int has the
                // actual length. Handle the simpler case here.
                char[] chars = new char[len];
                for (int i = 0; i < len; i++) {
                    chars[i] = buf.getChar();
                }
                buf.getShort(); // NUL terminator
                return new String(chars);
            }
        } finally {
            buf.position(oldPos);
        }
    }

    private int[] readUleb128(int pos) {
        int result = 0;
        int shift = 0;
        int p = pos;
        while (true) {
            byte b = buf.get(p++);
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return new int[]{result, p};
    }

    private void parseResourceMap(int chunkSize) {
        int mapStart = buf.position() - 8;
        int count = (chunkSize - 8) / 4;
        resourceIds = new int[count];
        for (int i = 0; i < count; i++) {
            resourceIds[i] = buf.getInt();
        }
        buf.position(mapStart + chunkSize);
    }

    private String parseStartElement(StringBuilder out, String indent) {
        // header: type(2) headerSize(2) size(4) [read]
        // chunk: lineNumber(4), comment(4), ns(4), name(4), attrStart(2), attrSize(2), attrCount(2), idIdx(2), classIdx(2), styleIdx(2)
        int chunkStart = buf.position() - 8;
        buf.getInt(); // lineNumber (unused)
        buf.getInt(); // comment
        int nsIdx = buf.getInt();
        int nameIdx = buf.getInt();
        int attrStart = buf.getShort() & 0xFFFF;
        int attrSize = buf.getShort() & 0xFFFF;
        int attrCount = buf.getShort() & 0xFFFF;
        buf.getShort(); // idIdx
        buf.getShort(); // classIdx
        buf.getShort(); // styleIdx

        String name = stringAt(nameIdx);
        out.append(indent).append("<").append(name);

        // Seek explicitly to attribute start (attrStart is offset from ns field)
        buf.position(chunkStart + 8 + 8 + attrStart);

        // Each attribute: ns(4), name(4), rawValue(4), typeSize(2), res0(1), dataType(1), data(4)
        for (int i = 0; i < attrCount; i++) {
            int aNs = buf.getInt();
            int aName = buf.getInt();
            int aRaw = buf.getInt();
            buf.getShort(); // typeSize
            buf.get();      // res0
            byte aDataType = buf.get();
            int aData = buf.getInt();

            String attrName = stringAt(aName);
            String nsPrefix = resolveNsPrefix(aNs);

            out.append(" ");
            if (nsPrefix != null && !nsPrefix.isEmpty()) {
                out.append(nsPrefix).append(":");
            }
            out.append(attrName).append("=\"");

            String value = resolveAttrValue(aDataType, aData, aRaw);
            out.append(escapeXml(value)).append("\"");
        }
        out.append(">\n");
        return indent + "    ";
    }

    private String parseEndElement(StringBuilder out, String indent) {
        // header(8): type(2) headerSize(2) size(4) [read]
        // chunk: lineNumber(4), comment(4), ns(4), name(4)
        buf.getInt(); // lineNumber
        buf.getInt(); // comment
        buf.getInt(); // ns
        int nameIdx = buf.getInt();
        String name = stringAt(nameIdx);

        String parentIndent = indent.length() >= 4 ? indent.substring(0, indent.length() - 4) : "";
        out.append(parentIndent).append("</").append(name).append(">\n");
        return parentIndent;
    }

    private String stringAt(int idx) {
        if (idx < 0 || idx >= stringPool.length) return "";
        String s = stringPool[idx];
        return s != null ? s : "";
    }

    private String resolveNsPrefix(int nsIdx) {
        if (nsIdx < 0 || nsIdx >= stringPool.length) return null;
        String ns = stringPool[nsIdx];
        if (ns == null || ns.isEmpty()) return null;
        // Map common URIs to prefixes
        if (ns.equals("http://schemas.android.com/apk/res/android")) return "android";
        if (ns.equals("http://schemas.android.com/apk/res-auto")) return "app";
        if (ns.equals("http://schemas.android.com/tools")) return "tools";
        if (ns.equals("http://schemas.android.com/apk/prv/res/android")) return "android";
        // Generic fallback: take the last path segment
        int slash = ns.lastIndexOf('/');
        if (slash >= 0 && slash < ns.length() - 1) return ns.substring(slash + 1);
        return null;
    }

    private String resolveAttrValue(byte dataType, int data, int rawValueIdx) {
        // Reference: Res_value data types
        switch (dataType) {
            case 0x00: // TYPE_NULL
                return "";
            case 0x01: // TYPE_REFERENCE (resource ID)
                return "@" + Integer.toHexString(data);
            case 0x02: // TYPE_ATTRIBUTE
                return "?" + Integer.toHexString(data);
            case 0x03: // TYPE_STRING
                return stringAt(rawValueIdx);
            case 0x04: // TYPE_FLOAT
                return Float.toString(Float.intBitsToFloat(data));
            case 0x05: { // TYPE_DIMENSION
                return formatDimension(data);
            }
            case 0x06: { // TYPE_FRACTION
                float f = ((data & 0xFFFFFFFFL) / (float) (1 << (data >>> 28 & 0xF))) * 100;
                return f + "%";
            }
            case 0x10: // TYPE_INT_DEC
                return Integer.toString(data);
            case 0x11: // TYPE_INT_HEX
                return "0x" + Integer.toHexString(data);
            case 0x12: // TYPE_INT_BOOLEAN
                return data == 0 ? "false" : "true";
            default:
                return Integer.toString(data);
        }
    }

    private String formatDimension(int data) {
        int unit = data >>> 28 & 0xF;
        int value = data & 0x07FFFFFF;
        switch (unit) {
            case 0: return value + "px";
            case 1: return value + "dip";
            case 2: return value + "sp";
            case 3: return value + "pt";
            case 4: return value + "in";
            case 5: return value + "mm";
            default: return value + " (unit " + unit + ")";
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
