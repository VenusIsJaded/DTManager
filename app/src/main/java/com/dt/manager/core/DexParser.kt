package com.dt.manager.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal, high-performance DEX (Dalvik Executable) parser.
 * Reads the header, string table, type table, proto/method/field tables and class_def list
 * to build a package/class tree.
 *
 * Performance features:
 * - Direct Little-Endian ByteBuffer memory access
 * - Descriptor and prototype string caching
 * - Fast ULEB128 decoding
 * - Support for MultiDex tree merging
 *
 * Reference: https://source.android.com/devices/tech/dalvik/dex-format
 */
class DexParser : Closeable {

    private val raf: RandomAccessFile?
    private val raw: ByteArray
    private val buf: ByteBuffer

    // Header fields
    private var stringIdsSize = 0
    private var stringIdsOff = 0
    private var typeIdsSize = 0
    private var typeIdsOff = 0
    private var protoIdsSize = 0
    private var protoIdsOff = 0
    private var fieldIdsSize = 0
    private var fieldIdsOff = 0
    private var methodIdsSize = 0
    private var methodIdsOff = 0
    private var classDefsSize = 0
    private var classDefsOff = 0

    private var strings: Array<String> = emptyArray()
    private var typeIds: IntArray = IntArray(0)
    private var fieldIds: Array<FieldId> = emptyArray()
    private var methodIds: Array<MethodId> = emptyArray()
    private var protoIds: Array<ProtoId> = emptyArray()
    private var classDefs: Array<ClassDef> = emptyArray()

    private val descriptorCache = ConcurrentHashMap<Int, String>()
    private val classDefMap = ConcurrentHashMap<String, ClassDef>()

    @Throws(IOException::class)
    constructor(file: File) {
        this.raf = RandomAccessFile(file, "r")
        val data = ByteArray(file.length().toInt())
        raf.readFully(data)
        this.raw = data
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        init()
    }

    @Throws(IOException::class)
    constructor(data: ByteArray) {
        this.raf = null
        this.raw = data
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        init()
    }

    private fun init() {
        parseHeader()
        parseStrings()
        parseTypes()
        parseProtos()
        parseFields()
        parseMethods()
        parseClassDefs()
    }

    @Throws(IOException::class)
    private fun parseHeader() {
        if (raw.size < 0x70) {
            throw IOException("Not a valid DEX file (too small)")
        }
        val isDex = raw[0] == 'd'.code.toByte() && raw[1] == 'e'.code.toByte() && raw[2] == 'x'.code.toByte() && raw[3] == '\n'.code.toByte()
        val isCdex = raw[0] == 'c'.code.toByte() && raw[1] == 'd'.code.toByte() && raw[2] == 'e'.code.toByte() && raw[3] == 'x'.code.toByte()
        if (!isDex && !isCdex) {
            throw IOException("Not a valid DEX file (bad magic)")
        }
        buf.position(0x38)
        stringIdsSize = buf.int
        stringIdsOff = buf.int
        typeIdsSize = buf.int
        typeIdsOff = buf.int
        protoIdsSize = buf.int
        protoIdsOff = buf.int
        fieldIdsSize = buf.int
        fieldIdsOff = buf.int
        methodIdsSize = buf.int
        methodIdsOff = buf.int
        classDefsSize = buf.int
        classDefsOff = buf.int
    }

    private fun parseStrings() {
        strings = Array(stringIdsSize) { "" }
        for (i in 0 until stringIdsSize) {
            val off = readInt(stringIdsOff + i * 4)
            strings[i] = readStringData(off)
        }
    }

    private fun parseTypes() {
        typeIds = IntArray(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            typeIds[i] = readInt(typeIdsOff + i * 4)
        }
    }

    private fun parseProtos() {
        protoIds = Array(protoIdsSize) { i ->
            val base = protoIdsOff + i * 12
            val shortyIdx = readInt(base)
            val returnIdx = readInt(base + 4)
            val paramsOff = readInt(base + 8)
            ProtoId(shortyIdx, returnIdx, paramsOff)
        }
    }

    private fun parseFields() {
        fieldIds = Array(fieldIdsSize) { i ->
            val base = fieldIdsOff + i * 8
            val classIdx = readUShort(base)
            val typeIdx = readUShort(base + 2)
            val nameIdx = readInt(base + 4)
            FieldId(classIdx, typeIdx, nameIdx)
        }
    }

    private fun parseMethods() {
        methodIds = Array(methodIdsSize) { i ->
            val base = methodIdsOff + i * 8
            val classIdx = readUShort(base)
            val protoIdx = readUShort(base + 2)
            val nameIdx = readInt(base + 4)
            MethodId(classIdx, protoIdx, nameIdx)
        }
    }

    private fun parseClassDefs() {
        classDefs = Array(classDefsSize) { i ->
            val base = classDefsOff + i * 32
            val classIdx = readInt(base)
            val accessFlags = readInt(base + 4)
            val superclassIdx = readInt(base + 8)
            readInt(base + 12) // interfacesOff
            val sourceFileIdx = readInt(base + 16)
            readInt(base + 20) // annotationsOff
            val classDataOff = readInt(base + 24)
            readInt(base + 28) // staticValuesOff

            val cd = ClassDef(classIdx, accessFlags, superclassIdx, sourceFileIdx, classDataOff)
            val desc = typeDescriptor(classIdx)
            classDefMap[desc] = cd
            cd
        }
    }

    private fun readInt(off: Int): Int = buf.getInt(off)

    private fun readUShort(off: Int): Int = buf.getShort(off).toInt() and 0xFFFF

    private fun readStringData(off: Int): String {
        var pos = off
        while ((raw[pos].toInt() and 0x80) != 0) pos++
        pos++
        val start = pos
        while (pos < raw.size && raw[pos].toInt() != 0) pos++
        return String(raw, start, pos - start, StandardCharsets.UTF_8)
    }

    private fun readUleb128(pos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var cur = pos
        while (cur < raw.size) {
            val b = raw[cur++].toInt()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, cur)
    }

    fun stringAt(idx: Int): String {
        if (idx < 0 || idx >= strings.size) return "<invalid>"
        return strings[idx]
    }

    fun typeDescriptor(idx: Int): String {
        if (idx < 0 || idx >= typeIds.size) return "<invalid>"
        return descriptorCache.computeIfAbsent(idx) {
            strings[typeIds[it]]
        }
    }

    fun fieldName(fieldIdx: Int): String {
        if (fieldIdx < 0 || fieldIdx >= fieldIds.size) return "<invalid>"
        return strings[fieldIds[fieldIdx].nameIdx]
    }

    fun fieldTypeName(fieldIdx: Int): String {
        if (fieldIdx < 0 || fieldIdx >= fieldIds.size) return "<invalid>"
        return descriptorToName(typeDescriptor(fieldIds[fieldIdx].typeIdx))
    }

    fun methodName(methodIdx: Int): String {
        if (methodIdx < 0 || methodIdx >= methodIds.size) return "<invalid>"
        return strings[methodIds[methodIdx].nameIdx]
    }

    fun methodClassName(methodIdx: Int): String {
        if (methodIdx < 0 || methodIdx >= methodIds.size) return "<invalid>"
        return descriptorToName(typeDescriptor(methodIds[methodIdx].classIdx))
    }

    fun methodPrototype(methodIdx: Int): String {
        if (methodIdx < 0 || methodIdx >= methodIds.size) return "()"
        val m = methodIds[methodIdx]
        if (m.protoIdx < 0 || m.protoIdx >= protoIds.size) return "()"
        val p = protoIds[m.protoIdx]
        val returnDesc = typeDescriptor(p.returnTypeIdx)
        val params = StringBuilder()
        if (p.paramsOff != 0) {
            val count = readInt(p.paramsOff)
            for (i in 0 until count) {
                val t = readUShort(p.paramsOff + 4 + i * 2)
                if (i > 0) params.append(", ")
                params.append(descriptorToName(typeDescriptor(t)))
            }
        }
        return "($params) → ${descriptorToName(returnDesc)}"
    }

    fun buildTree(): Node {
        val root = Node("", "/", isPackage = true, depth = 0)
        for (cd in classDefs) {
            val desc = typeDescriptor(cd.classIdx)
            val full = descriptorToName(desc)
            insertClass(root, full)
        }
        root.sortChildren()
        return root
    }

    private fun insertClass(root: Node, fullClassName: String?) {
        if (fullClassName.isNullOrEmpty()) return
        val parts = fullClassName.split('.')
        var cur = root
        val path = StringBuilder()
        for (i in parts.indices) {
            val part = parts[i]
            val isPackage = i < parts.size - 1
            if (path.isNotEmpty()) path.append(".")
            path.append(part)
            var child = cur.findChild(part)
            if (child == null) {
                child = Node(part, path.toString(), isPackage, cur.depth + 1)
                cur.children.add(child)
            }
            cur = child
        }
    }

    fun extractStrings(): List<String> {
        val out = ArrayList<String>(strings.size)
        for (s in strings) {
            if (s.isEmpty() || !isPrintable(s)) continue
            out.add(s)
        }
        Collections.sort(out)
        return out
    }

    private fun isPrintable(s: String): Boolean {
        for (i in 0 until s.length) {
            val c = s[i]
            if (c < ' ' && c != '\t' && c != '\n' && c != '\r') return false
            if (c >= '\uFFFE') return false
        }
        return true
    }

    fun findClassDefByName(fullClassName: String?): ClassDef? {
        if (fullClassName == null) return null
        val desc = "L" + fullClassName.replace('.', '/') + ";"
        classDefMap[desc]?.let { return it }
        for (cd in classDefs) {
            if (typeDescriptor(cd.classIdx) == desc) return cd
        }
        return null
    }

    fun sourceFile(cd: ClassDef?): String {
        if (cd == null || cd.sourceFileIdx == -1 || cd.sourceFileIdx >= strings.size) return ""
        return strings[cd.sourceFileIdx]
    }

    fun superclass(cd: ClassDef?): String {
        if (cd == null || cd.superclassIdx == -1 || cd.superclassIdx >= typeIds.size) return ""
        return descriptorToName(typeDescriptor(cd.superclassIdx))
    }

    fun parseClassData(cd: ClassDef?): ClassData {
        if (cd == null || cd.classDataOff == 0) {
            return ClassData(emptyList(), emptyList())
        }
        var pos = cd.classDataOff
        val (staticFieldsCount, p1) = readUleb128(pos); pos = p1
        val (instanceFieldsCount, p2) = readUleb128(pos); pos = p2
        val (directMethodsCount, p3) = readUleb128(pos); pos = p3
        val (virtualMethodsCount, p4) = readUleb128(pos); pos = p4

        val fields = ArrayList<FieldInfo>(staticFieldsCount + instanceFieldsCount)
        var fieldIdx = 0
        for (i in 0 until staticFieldsCount) {
            val (diff, np1) = readUleb128(pos); pos = np1
            val (access, np2) = readUleb128(pos); pos = np2
            fieldIdx += diff
            fields.add(FieldInfo(fieldName(fieldIdx), fieldTypeName(fieldIdx), access, isStatic = true))
        }
        fieldIdx = 0
        for (i in 0 until instanceFieldsCount) {
            val (diff, np1) = readUleb128(pos); pos = np1
            val (access, np2) = readUleb128(pos); pos = np2
            fieldIdx += diff
            fields.add(FieldInfo(fieldName(fieldIdx), fieldTypeName(fieldIdx), access, isStatic = false))
        }

        val methods = ArrayList<MethodInfo>(directMethodsCount + virtualMethodsCount)
        var methodIdx = 0
        for (i in 0 until directMethodsCount) {
            val (diff, np1) = readUleb128(pos); pos = np1
            val (access, np2) = readUleb128(pos); pos = np2
            val (codeOff, np3) = readUleb128(pos); pos = np3
            methodIdx += diff
            methods.add(MethodInfo(methodName(methodIdx), methodPrototype(methodIdx), access, isDirect = true, codeOff = codeOff))
        }
        methodIdx = 0
        for (i in 0 until virtualMethodsCount) {
            val (diff, np1) = readUleb128(pos); pos = np1
            val (access, np2) = readUleb128(pos); pos = np2
            val (codeOff, np3) = readUleb128(pos); pos = np3
            methodIdx += diff
            methods.add(MethodInfo(methodName(methodIdx), methodPrototype(methodIdx), access, isDirect = false, codeOff = codeOff))
        }

        fields.sortWith(compareBy({ if (it.isStatic) 0 else 1 }, { it.name.lowercase() }))
        methods.sortWith(compareBy({ if (it.isDirect) 0 else 1 }, { it.name.lowercase() }))

        return ClassData(fields, methods)
    }

    override fun close() {
        raf?.close()
    }

    companion object {
        @JvmStatic
        fun descriptorToName(desc: String?): String {
            if (desc.isNullOrEmpty()) return desc ?: ""
            var current: String = desc
            var arrDim = 0
            while (current.startsWith("[")) {
                arrDim++
                current = current.substring(1)
            }
            val out = StringBuilder()
            if (current.startsWith("L") && current.endsWith(";")) {
                out.append(current.substring(1, current.length - 1).replace('/', '.'))
            } else if (current.length == 1) {
                out.append(primitiveName(current[0]))
            } else {
                out.append(current)
            }
            for (i in 0 until arrDim) out.append("[]")
            return out.toString()
        }

        private fun primitiveName(c: Char): String {
            return when (c) {
                'V' -> "void"
                'Z' -> "boolean"
                'B' -> "byte"
                'S' -> "short"
                'C' -> "char"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                else -> c.toString()
            }
        }
    }

    data class ClassDef(
        val classIdx: Int,
        val accessFlags: Int,
        val superclassIdx: Int,
        val sourceFileIdx: Int,
        val classDataOff: Int
    ) {
        val isInterface: Boolean get() = (accessFlags and 0x0200) != 0
        val isAbstract: Boolean get() = (accessFlags and 0x0400) != 0
        val isPublic: Boolean get() = (accessFlags and 0x0001) != 0
        val isFinal: Boolean get() = (accessFlags and 0x0010) != 0
    }

    data class FieldId(val classIdx: Int, val typeIdx: Int, val nameIdx: Int)
    data class MethodId(val classIdx: Int, val protoIdx: Int, val nameIdx: Int)
    data class ProtoId(val shortyIdx: Int, val returnTypeIdx: Int, val paramsOff: Int)

    data class FieldInfo(
        val name: String,
        val type: String,
        val accessFlags: Int,
        val isStatic: Boolean
    ) {
        fun modifierPrefix(): String {
            val sb = StringBuilder()
            if ((accessFlags and 0x0001) != 0) sb.append("public ")
            if ((accessFlags and 0x0002) != 0) sb.append("private ")
            if ((accessFlags and 0x0004) != 0) sb.append("protected ")
            if ((accessFlags and 0x0008) != 0) sb.append("static ")
            if ((accessFlags and 0x0010) != 0) sb.append("final ")
            if ((accessFlags and 0x0040) != 0) sb.append("volatile ")
            if ((accessFlags and 0x0080) != 0) sb.append("transient ")
            return sb.toString().trim()
        }
    }

    data class MethodInfo(
        val name: String,
        val prototype: String,
        val accessFlags: Int,
        val isDirect: Boolean,
        val codeOff: Int
    ) {
        fun modifierPrefix(): String {
            val sb = StringBuilder()
            if ((accessFlags and 0x0001) != 0) sb.append("public ")
            if ((accessFlags and 0x0002) != 0) sb.append("private ")
            if ((accessFlags and 0x0004) != 0) sb.append("protected ")
            if ((accessFlags and 0x0008) != 0) sb.append("static ")
            if ((accessFlags and 0x0010) != 0) sb.append("final ")
            if ((accessFlags and 0x0040) != 0) sb.append("synchronized ")
            if ((accessFlags and 0x0100) != 0) sb.append("native ")
            if ((accessFlags and 0x0400) != 0) sb.append("abstract ")
            return sb.toString().trim()
        }
    }

    data class ClassData(val fields: List<FieldInfo>, val methods: List<MethodInfo>)

    class Node(
        var name: String,
        var path: String,
        var isPackage: Boolean,
        var depth: Int
    ) {
        val children: MutableList<Node> = ArrayList()

        fun findChild(n: String): Node? {
            for (c in children) {
                if (c.name == n) return c
            }
            return null
        }

        fun hasChildren(): Boolean = children.isNotEmpty()

        fun sortChildren() {
            children.sortWith(compareBy({ if (it.isPackage) 0 else 1 }, { it.name.lowercase() }))
            for (c in children) c.sortChildren()
        }
    }
}
