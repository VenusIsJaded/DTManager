package com.dt.manager.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal DEX (Dalvik Executable) parser. Reads the header, string table,
 * type table, proto/method/field tables and class_def list to build a
 * package/class tree. This is a read-only inspector — it does NOT
 * decompile to smali.
 *
 * Reference: https://source.android.com/devices/tech/dalvik/dex-format
 */
public class DexParser implements Closeable {

    private final RandomAccessFile raf;
    private ByteBuffer buf;
    private byte[] raw;

    // Header fields
    private int stringIdsSize, stringIdsOff;
    private int typeIdsSize, typeIdsOff;
    private int protoIdsSize, protoIdsOff;
    private int fieldIdsSize, fieldIdsOff;
    private int methodIdsSize, methodIdsOff;
    private int classDefsSize, classDefsOff;

    private String[] strings;
    private int[] typeIds;
    private ClassDef[] classDefs;

    public DexParser(File file) throws IOException {
        this.raf = new RandomAccessFile(file, "r");
        this.raw = readAll(raf);
        this.buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        parseHeader();
        parseStrings();
        parseTypes();
        parseClassDefs();
    }

    public DexParser(byte[] data) throws IOException {
        this.raf = null;
        this.raw = data;
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        parseHeader();
        parseStrings();
        parseTypes();
        parseClassDefs();
    }

    private static byte[] readAll(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n;
        while ((n = raf.read(buf)) > 0) {
            out.write(buf, 0, (int) n);
        }
        return out.toByteArray();
    }

    private void parseHeader() throws IOException {
        // Magic: dex\n035\0 or dex\n036\0 ... dex\n039\0
        if (raw.length < 0x70) {
            throw new IOException("Not a valid DEX file (too small)");
        }
        if (!(raw[0] == 'd' && raw[1] == 'e' && raw[2] == 'x' && raw[3] == '\n')) {
            throw new IOException("Not a valid DEX file (bad magic)");
        }
        buf.position(0x38);
        stringIdsSize = buf.getInt();
        stringIdsOff  = buf.getInt();
        typeIdsSize   = buf.getInt();
        typeIdsOff    = buf.getInt();
        protoIdsSize  = buf.getInt();
        protoIdsOff   = buf.getInt();
        fieldIdsSize  = buf.getInt();
        fieldIdsOff   = buf.getInt();
        methodIdsSize = buf.getInt();
        methodIdsOff  = buf.getInt();
        classDefsSize = buf.getInt();
        classDefsOff  = buf.getInt();
    }

    private void parseStrings() throws IOException {
        strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            int off = readInt(stringIdsOff + i * 4);
            strings[i] = readStringData(off);
        }
    }

    private void parseTypes() throws IOException {
        typeIds = new int[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            typeIds[i] = readInt(typeIdsOff + i * 4);
        }
    }

    private void parseClassDefs() throws IOException {
        classDefs = new ClassDef[classDefsSize];
        for (int i = 0; i < classDefsSize; i++) {
            int base = classDefsOff + i * 32;
            int classIdx = readInt(base);
            int accessFlags = readInt(base + 4);
            int superclassIdx = readInt(base + 8);
            int interfacesOff = readInt(base + 12);
            int sourceFileIdx = readInt(base + 16);
            int annotationsOff = readInt(base + 20);
            int classDataOff = readInt(base + 24);
            int staticValuesOff = readInt(base + 28);

            classDefs[i] = new ClassDef(
                    classIdx,
                    accessFlags,
                    superclassIdx,
                    sourceFileIdx,
                    classDataOff
            );
        }
    }

    private int readInt(int off) {
        return buf.getInt(off);
    }

    private String readStringData(int off) {
        // ULEB128 length, then MUTF-8 bytes ending with NUL
        int pos = off;
        // skip length (varint)
        while ((raw[pos] & 0x80) != 0) pos++;
        pos++;
        int start = pos;
        while (raw[pos] != 0) pos++;
        return new String(raw, start, pos - start, StandardCharsets.UTF_8);
    }

    public String stringAt(int idx) {
        if (idx < 0 || idx >= strings.length) return "<invalid>";
        return strings[idx];
    }

    public String typeDescriptor(int idx) {
        if (idx < 0 || idx >= typeIds.length) return "<invalid>";
        return strings[typeIds[idx]];
    }

    /** Convert "Lcom/example/Foo;" → "com.example.Foo" */
    public static String descriptorToName(String desc) {
        if (desc == null || desc.isEmpty()) return desc;
        int arrDim = 0;
        while (desc.startsWith("[")) {
            arrDim++;
            desc = desc.substring(1);
        }
        StringBuilder out = new StringBuilder();
        if (desc.startsWith("L") && desc.endsWith(";")) {
            out.append(desc.substring(1, desc.length() - 1).replace('/', '.'));
        } else if (desc.length() == 1) {
            out.append(primitiveName(desc.charAt(0)));
        } else {
            out.append(desc);
        }
        for (int i = 0; i < arrDim; i++) out.append("[]");
        return out.toString();
    }

    private static String primitiveName(char c) {
        switch (c) {
            case 'V': return "void";
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'S': return "short";
            case 'C': return "char";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            case 'D': return "double";
            default: return String.valueOf(c);
        }
    }

    /** Build a tree of packages and classes from this DEX. */
    public Node buildTree() {
        Node root = new Node("", "/", true, 0);
        for (ClassDef cd : classDefs) {
            String desc = typeDescriptor(cd.classIdx);
            String full = descriptorToName(desc);
            insertClass(root, full);
        }
        // First pass: separate packages from classes
        root.sortChildren();
        return root;
    }

    private void insertClass(Node root, String fullClassName) {
        String[] parts = fullClassName.split("\\.");
        Node cur = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean isPackage = i < parts.length - 1;
            Node child = cur.findChild(part);
            if (child == null) {
                child = new Node(part,
                        isPackage ? part : part,
                        isPackage,
                        cur.depth + 1);
                cur.children.add(child);
            }
            cur = child;
        }
    }

    public List<String> extractStrings() {
        // Pull a list of unique printable strings (heuristic)
        List<String> out = new ArrayList<>(strings.length);
        for (String s : strings) {
            if (s == null || s.isEmpty()) continue;
            if (!isPrintable(s)) continue;
            out.add(s);
        }
        Collections.sort(out);
        return out;
    }

    private static boolean isPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') return false;
            if (c >= 0xFFFE) return false;
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        if (raf != null) raf.close();
    }

    /* ===== Data classes ===== */

    public static class ClassDef {
        public final int classIdx;
        public final int accessFlags;
        public final int superclassIdx;
        public final int sourceFileIdx;
        public final int classDataOff;

        public ClassDef(int classIdx, int accessFlags, int superclassIdx,
                        int sourceFileIdx, int classDataOff) {
            this.classIdx = classIdx;
            this.accessFlags = accessFlags;
            this.superclassIdx = superclassIdx;
            this.sourceFileIdx = sourceFileIdx;
            this.classDataOff = classDataOff;
        }

        public boolean isInterface() { return (accessFlags & 0x0200) != 0; }
        public boolean isAbstract()  { return (accessFlags & 0x0400) != 0; }
        public boolean isPublic()   { return (accessFlags & 0x0001) != 0; }
        public boolean isFinal()    { return (accessFlags & 0x0010) != 0; }
    }

    public static class Node {
        public String name;
        public String path;
        public boolean isPackage;
        public int depth;
        public final List<Node> children = new ArrayList<>();

        public Node(String name, String path, boolean isPackage, int depth) {
            this.name = name;
            this.path = path;
            this.isPackage = isPackage;
            this.depth = depth;
        }

        Node findChild(String n) {
            for (Node c : children) {
                if (c.name.equals(n)) return c;
            }
            return null;
        }

        public boolean hasChildren() { return !children.isEmpty(); }

        void sortChildren() {
            // Packages first, then classes; alphabetical within each group
            Collections.sort(children, Comparator
                    .comparingInt((Node n) -> n.isPackage ? 0 : 1)
                    .thenComparing(n -> n.name.toLowerCase()));
            for (Node c : children) c.sortChildren();
        }
    }
}
