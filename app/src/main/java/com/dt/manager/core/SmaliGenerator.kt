package com.dt.manager.core

/**
 * Generates a smali-style text representation of a DEX class. This is
 * a read-only structural view showing the class declaration, superclass, source file,
 * fields and methods with modifiers and prototypes.
 */
object SmaliGenerator {

    @JvmStatic
    fun generate(parser: DexParser, cd: DexParser.ClassDef?): String {
        if (cd == null) return "// class not found"
        val sb = StringBuilder()

        val classDescriptor = parser.typeDescriptor(cd.classIdx)
        val superclass = parser.superclass(cd)
        val sourceFile = parser.sourceFile(cd)

        // .class declaration with modifiers
        sb.append(".class ").append(modifiers(cd.accessFlags)).append(classDescriptor).append("\n")

        // .super
        if (superclass.isNotEmpty()) {
            val superDesc = "L" + superclass.replace('.', '/') + ";"
            sb.append(".super ").append(superDesc).append("\n")
        }

        // .source
        if (sourceFile.isNotEmpty()) {
            sb.append(".source \"").append(sourceFile).append("\"\n")
        }

        val data = parser.parseClassData(cd)

        // Static fields
        if (hasStaticFields(data)) {
            sb.append("\n# static fields\n")
            for (f in data.fields) {
                if (!f.isStatic) continue
                sb.append(".field ").append(f.modifierPrefix())
                    .append(" ").append(f.name)
                    .append(":").append(nameToDescriptor(f.type))
                    .append("\n")
            }
        }

        // Instance fields
        if (hasInstanceFields(data)) {
            sb.append("\n# instance fields\n")
            for (f in data.fields) {
                if (f.isStatic) continue
                sb.append(".field ").append(f.modifierPrefix())
                    .append(" ").append(f.name)
                    .append(":").append(nameToDescriptor(f.type)).append("\n")
            }
        }

        // Direct methods (constructor + private static etc.)
        if (hasDirectMethods(data)) {
            sb.append("\n# direct methods\n")
            for (m in data.methods) {
                if (!m.isDirect) continue
                appendMethod(sb, m)
            }
        }

        // Virtual methods
        if (hasVirtualMethods(data)) {
            sb.append("\n# virtual methods\n")
            for (m in data.methods) {
                if (m.isDirect) continue
                appendMethod(sb, m)
            }
        }

        return sb.toString()
    }

    private fun hasStaticFields(d: DexParser.ClassData): Boolean = d.fields.any { it.isStatic }
    private fun hasInstanceFields(d: DexParser.ClassData): Boolean = d.fields.any { !it.isStatic }
    private fun hasDirectMethods(d: DexParser.ClassData): Boolean = d.methods.any { it.isDirect }
    private fun hasVirtualMethods(d: DexParser.ClassData): Boolean = d.methods.any { !it.isDirect }

    private fun appendMethod(sb: StringBuilder, m: DexParser.MethodInfo) {
        sb.append(".method ").append(m.modifierPrefix())
            .append(" ").append(m.name)
            .append(smaliPrototype(m.prototype)).append("\n")
        sb.append(".end method\n\n")
    }

    /** Convert "(int, String) → void" to "(ILjava/lang/String;)V" */
    private fun smaliPrototype(proto: String?): String {
        if (proto == null) return "()V"
        val arrow = proto.indexOf('→')
        if (arrow < 0) return "()V"
        val closeParen = proto.indexOf(')')
        if (closeParen < 0) return "()V"
        val params = proto.substring(1, closeParen).trim()
        val returnType = proto.substring(arrow + 1).trim()
        val sb = StringBuilder("(")
        if (params.isNotEmpty()) {
            for (p in params.split(',')) {
                sb.append(nameToDescriptor(p.trim()))
            }
        }
        sb.append(")").append(nameToDescriptor(returnType))
        return sb.toString()
    }

    /** Convert "java.lang.String" → "Ljava/lang/String;", "int" → "I", etc. */
    private fun nameToDescriptor(name: String?): String {
        if (name.isNullOrEmpty()) return "V"
        var current: String = name
        var arr = 0
        while (current.endsWith("[]")) {
            arr++
            current = current.substring(0, current.length - 2)
        }
        val sb = StringBuilder()
        val primitive = primitiveDescriptor(current)
        if (primitive != null) {
            sb.append(primitive)
        } else if (current.startsWith("L") && current.endsWith(";")) {
            sb.append(current)
        } else {
            sb.append("L").append(current.replace('.', '/')).append(";")
        }
        for (i in 0 until arr) sb.insert(0, "[")
        return sb.toString()
    }

    private fun primitiveDescriptor(name: String): String? {
        return when (name) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "short" -> "S"
            "char" -> "C"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> null
        }
    }

    private fun modifiers(accessFlags: Int): String {
        val sb = StringBuilder()
        if ((accessFlags and 0x0001) != 0) sb.append("public ")
        if ((accessFlags and 0x0002) != 0) sb.append("private ")
        if ((accessFlags and 0x0004) != 0) sb.append("protected ")
        if ((accessFlags and 0x0008) != 0) sb.append("static ")
        if ((accessFlags and 0x0010) != 0) sb.append("final ")
        if ((accessFlags and 0x0200) != 0) sb.append("interface ")
        if ((accessFlags and 0x0400) != 0) sb.append("abstract ")
        if ((accessFlags and 0x00010000) != 0) sb.append("native ")
        if ((accessFlags and 0x04000000) != 0) sb.append("annotation ")
        if ((accessFlags and 0x20000000) != 0) sb.append("enum ")
        return sb.toString().trim() + if (sb.isNotEmpty()) " " else ""
    }
}
