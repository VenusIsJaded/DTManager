package com.dt.manager.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal DEX (Dalvik Executable) parser. Reads the header, string table,
 * type table, proto/method/field tables and class_def list to build a
 * package/class tree. For each class, also parses class_data_item to
 * expose its fields and methods (read-only — does not decompile to smali).
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
    private int[] typeIds;       // typeIds[i] -> string index of descriptor
    private FieldId[] fieldIds;  // field_ids table
    private MethodId[] methodIds;// method_ids table
    private ProtoId[] protoIds;  // proto_ids table
    private ClassDef[] classDefs;

    public DexParser(File file) throws IOException {
        this.raf = new RandomAccessFile(file, "r");
        this.raw = readAll(raf);
        this.buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        parseHeader();
        parseStrings();
        parseTypes();
        parseProtos();
        parseFields();
        parseMethods();
        parseClassDefs();
    }

    public DexParser(byte[] data) throws IOException {
        this.raf = null;
        this.raw = data;
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        parseHeader();
        parseStrings();
        parseTypes();
        parseProtos();
        parseFields();
        parseMethods();
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

    private void parseProtos() {
        protoIds = new ProtoId[protoIdsSize];
        for (int i = 0; i < protoIdsSize; i++) {
            int base = protoIdsOff + i * 12;
            int shortyIdx = readInt(base);
            int returnIdx = readInt(base + 4);
            int paramsOff = readInt(base + 8);
            protoIds[i] = new ProtoId(shortyIdx, returnIdx, paramsOff);
        }
    }

    private void parseFields() {
        fieldIds = new FieldId[fieldIdsSize];
        for (int i = 0; i < fieldIdsSize; i++) {
            int base = fieldIdsOff + i * 8;
            int classIdx = readUShort(base);
            int typeIdx  = readUShort(base + 2);
            int nameIdx  = readInt(base + 4);
            fieldIds[i] = new FieldId(classIdx, typeIdx, nameIdx);
        }
    }

    private void parseMethods() {
        methodIds = new MethodId[methodIdsSize];
        for (int i = 0; i < methodIdsSize; i++) {
            int base = methodIdsOff + i * 8;
            int classIdx = readUShort(base);
            int protoIdx = readUShort(base + 2);
            int nameIdx  = readInt(base + 4);
            methodIds[i] = new MethodId(classIdx, protoIdx, nameIdx);
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

    private int readUShort(int off) {
        return buf.getShort(off) & 0xFFFF;
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

    /** Read a ULEB128 varint at the given offset, returning [value, nextPos]. */
    private int[] readUleb128(int pos) {
        int result = 0;
        int shift = 0;
        while (true) {
            byte b = raw[pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return new int[]{result, pos};
    }

    public String stringAt(int idx) {
        if (idx < 0 || idx >= strings.length) return "<invalid>";
        return strings[idx];
    }

    public String typeDescriptor(int idx) {
        if (idx < 0 || idx >= typeIds.length) return "<invalid>";
        return strings[typeIds[idx]];
    }

    public String fieldName(int fieldIdx) {
        if (fieldIdx < 0 || fieldIdx >= fieldIds.length) return "<invalid>";
        return strings[fieldIds[fieldIdx].nameIdx];
    }

    public String fieldTypeName(int fieldIdx) {
        if (fieldIdx < 0 || fieldIdx >= fieldIds.length) return "<invalid>";
        return descriptorToName(typeDescriptor(fieldIds[fieldIdx].typeIdx));
    }

    public String methodName(int methodIdx) {
        if (methodIdx < 0 || methodIdx >= methodIds.length) return "<invalid>";
        return strings[methodIds[methodIdx].nameIdx];
    }

    public String methodClassName(int methodIdx) {
        if (methodIdx < 0 || methodIdx >= methodIds.length) return "<invalid>";
        return descriptorToName(typeDescriptor(methodIds[methodIdx].classIdx));
    }

    public String methodPrototype(int methodIdx) {
        if (methodIdx < 0 || methodIdx >= methodIds.length) return "()";
        MethodId m = methodIds[methodIdx];
        if (m.protoIdx < 0 || m.protoIdx >= protoIds.length) return "()";
        ProtoId p = protoIds[m.protoIdx];
        String returnDesc = typeDescriptor(p.returnTypeIdx);
        StringBuilder params = new StringBuilder();
        if (p.paramsOff != 0) {
            // type_list: uint size, then size * uint type_idx
            int count = readInt(p.paramsOff);
            for (int i = 0; i < count; i++) {
                int t = readInt(p.paramsOff + 4 + i * 4);
                if (i > 0) params.append(", ");
                params.append(descriptorToName(typeDescriptor(t)));
            }
        }
        return "(" + params + ") → " + descriptorToName(returnDesc);
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
        root.sortChildren();
        return root;
    }

    private void insertClass(Node root, String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) return;
        String[] parts = fullClassName.split("\\.");
        Node cur = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean isPackage = i < parts.length - 1;
            Node child = cur.findChild(part);
            if (child == null) {
                child = new Node(part, part, isPackage, cur.depth + 1);
                cur.children.add(child);
            }
            cur = child;
        }
    }

    public List<String> extractStrings() {
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

    /** Look up the ClassDef whose type matches the given full class name (e.g. "com.example.Foo"). */
    public ClassDef findClassDefByName(String fullClassName) {
        if (fullClassName == null) return null;
        String desc = "L" + fullClassName.replace('.', '/') + ";";
        for (ClassDef cd : classDefs) {
            if (typeDescriptor(cd.classIdx).equals(desc)) return cd;
        }
        return null;
    }

    public String sourceFile(ClassDef cd) {
        if (cd == null) return "";
        if (cd.sourceFileIdx == -1 || cd.sourceFileIdx >= strings.length) return "";
        return strings[cd.sourceFileIdx];
    }

    public String superclass(ClassDef cd) {
        if (cd == null) return "";
        if (cd.superclassIdx == -1 || cd.superclassIdx >= typeIds.length) return "";
        return descriptorToName(typeDescriptor(cd.superclassIdx));
    }

    /** Parse a class's class_data_item to get its fields and methods. */
    public ClassData parseClassData(ClassDef cd) {
        if (cd == null || cd.classDataOff == 0) {
            return new ClassData(Collections.emptyList(), Collections.emptyList());
        }
        int pos = cd.classDataOff;
        int[] r;
        r = readUleb128(pos); int staticFieldsCount = r[0]; pos = r[1];
        r = readUleb128(pos); int instanceFieldsCount = r[0]; pos = r[1];
        r = readUleb128(pos); int directMethodsCount = r[0]; pos = r[1];
        r = readUleb128(pos); int virtualMethodsCount = r[0]; pos = r[1];

        List<FieldInfo> fields = new ArrayList<>(staticFieldsCount + instanceFieldsCount);
        int fieldIdx = 0;
        for (int i = 0; i < staticFieldsCount; i++) {
            r = readUleb128(pos); int diff = r[0]; pos = r[1];
            r = readUleb128(pos); int access = r[0]; pos = r[1];
            fieldIdx += diff;
            fields.add(new FieldInfo(fieldName(fieldIdx), fieldTypeName(fieldIdx), access, true));
        }
        for (int i = 0; i < instanceFieldsCount; i++) {
            r = readUleb128(pos); int diff = r[0]; pos = r[1];
            r = readUleb128(pos); int access = r[0]; pos = r[1];
            fieldIdx += diff;
            fields.add(new FieldInfo(fieldName(fieldIdx), fieldTypeName(fieldIdx), access, false));
        }

        List<MethodInfo> methods = new ArrayList<>(directMethodsCount + virtualMethodsCount);
        int methodIdx = 0;
        for (int i = 0; i < directMethodsCount; i++) {
            r = readUleb128(pos); int diff = r[0]; pos = r[1];
            r = readUleb128(pos); int access = r[0]; pos = r[1];
            r = readUleb128(pos); int codeOff = r[0]; pos = r[1];
            methodIdx += diff;
            methods.add(new MethodInfo(methodName(methodIdx), methodPrototype(methodIdx), access, true, codeOff));
        }
        for (int i = 0; i < virtualMethodsCount; i++) {
            r = readUleb128(pos); int diff = r[0]; pos = r[1];
            r = readUleb128(pos); int access = r[0]; pos = r[1];
            r = readUleb128(pos); int codeOff = r[0]; pos = r[1];
            methodIdx += diff;
            methods.add(new MethodInfo(methodName(methodIdx), methodPrototype(methodIdx), access, false, codeOff));
        }

        Collections.sort(fields, Comparator
                .comparingInt((FieldInfo f) -> f.isStatic ? 0 : 1)
                .thenComparing(f -> f.name.toLowerCase()));
        Collections.sort(methods, Comparator
                .comparingInt((MethodInfo m) -> m.isDirect ? 0 : 1)
                .thenComparing(m -> m.name.toLowerCase()));

        return new ClassData(fields, methods);
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

    public static class FieldId {
        public final int classIdx, typeIdx, nameIdx;
        public FieldId(int classIdx, int typeIdx, int nameIdx) {
            this.classIdx = classIdx; this.typeIdx = typeIdx; this.nameIdx = nameIdx;
        }
    }

    public static class MethodId {
        public final int classIdx, protoIdx, nameIdx;
        public MethodId(int classIdx, int protoIdx, int nameIdx) {
            this.classIdx = classIdx; this.protoIdx = protoIdx; this.nameIdx = nameIdx;
        }
    }

    public static class ProtoId {
        public final int shortyIdx, returnTypeIdx, paramsOff;
        public ProtoId(int shortyIdx, int returnTypeIdx, int paramsOff) {
            this.shortyIdx = shortyIdx; this.returnTypeIdx = returnTypeIdx; this.paramsOff = paramsOff;
        }
    }

    public static class FieldInfo {
        public final String name;
        public final String type;
        public final int accessFlags;
        public final boolean isStatic;
        public FieldInfo(String name, String type, int accessFlags, boolean isStatic) {
            this.name = name; this.type = type; this.accessFlags = accessFlags; this.isStatic = isStatic;
        }
        public String modifierPrefix() {
            StringBuilder sb = new StringBuilder();
            if ((accessFlags & 0x0001) != 0) sb.append("public ");
            if ((accessFlags & 0x0002) != 0) sb.append("private ");
            if ((accessFlags & 0x0004) != 0) sb.append("protected ");
            if ((accessFlags & 0x0008) != 0) sb.append("static ");
            if ((accessFlags & 0x0010) != 0) sb.append("final ");
            if ((accessFlags & 0x0040) != 0) sb.append("volatile ");
            if ((accessFlags & 0x0080) != 0) sb.append("transient ");
            return sb.toString().trim();
        }
    }

    public static class MethodInfo {
        public final String name;
        public final String prototype;
        public final int accessFlags;
        public final boolean isDirect;
        public final int codeOff;
        public MethodInfo(String name, String prototype, int accessFlags, boolean isDirect, int codeOff) {
            this.name = name; this.prototype = prototype; this.accessFlags = accessFlags;
            this.isDirect = isDirect; this.codeOff = codeOff;
        }
        public String modifierPrefix() {
            StringBuilder sb = new StringBuilder();
            if ((accessFlags & 0x0001) != 0) sb.append("public ");
            if ((accessFlags & 0x0002) != 0) sb.append("private ");
            if ((accessFlags & 0x0004) != 0) sb.append("protected ");
            if ((accessFlags & 0x0008) != 0) sb.append("static ");
            if ((accessFlags & 0x0010) != 0) sb.append("final ");
            if ((accessFlags & 0x0040) != 0) sb.append("synchronized ");
            if ((accessFlags & 0x0100) != 0) sb.append("native ");
            if ((accessFlags & 0x0400) != 0) sb.append("abstract ");
            return sb.toString().trim();
        }
    }

    public static class ClassData {
        public final List<FieldInfo> fields;
        public final List<MethodInfo> methods;
        public ClassData(List<FieldInfo> fields, List<MethodInfo> methods) {
            this.fields = fields; this.methods = methods;
        }
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
            Collections.sort(children, Comparator
                    .comparingInt((Node n) -> n.isPackage ? 0 : 1)
                    .thenComparing(n -> n.name.toLowerCase()));
            for (Node c : children) c.sortChildren();
        }
    }
}
