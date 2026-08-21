package com.dt.manager.core;

/**
 * Generates a smali-style text representation of a DEX class. This is
 * a read-only view — not actual smali (no bytecode body), but it
 * shows the class structure in the same format the user would see
 * in a real smali file:
 *
 *   .class public Lcom/example/Foo;
 *   .super Ljava/lang/Object;
 *   .source "Foo.java"
 *
 *   # static fields
 *   .field public static final BAR:I = 42
 *
 *   # instance fields
 *   .field private mName:Ljava/lang/String;
 *
 *   # direct methods
 *   .method public constructor <init>()V
 *       .registers 1
 *   .end method
 *
 *   # virtual methods
 *   .method public toString()Ljava/lang/String;
 *       .registers 2
 *   .end method
 */
public class SmaliGenerator {

    public static String generate(DexParser parser, DexParser.ClassDef cd) {
        if (cd == null) return "// class not found";
        StringBuilder sb = new StringBuilder();

        String fullClassName = DexParser.descriptorToName(parser.typeDescriptor(cd.classIdx));
        String classDescriptor = parser.typeDescriptor(cd.classIdx);
        String superclass = parser.superclass(cd);
        String sourceFile = parser.sourceFile(cd);

        // .class declaration with modifiers
        sb.append(".class ").append(modifiers(cd.accessFlags)).append(classDescriptor).append("\n");

        // .super
        if (!superclass.isEmpty()) {
            String superDesc = "L" + superclass.replace('.', '/') + ";";
            sb.append(".super ").append(superDesc).append("\n");
        }

        // .source
        if (!sourceFile.isEmpty()) {
            sb.append(".source \"").append(sourceFile).append("\"\n");
        }

        DexParser.ClassData data = parser.parseClassData(cd);

        // Static fields
        if (hasStaticFields(data)) {
            sb.append("\n# static fields\n");
            for (DexParser.FieldInfo f : data.fields) {
                if (!f.isStatic) continue;
                sb.append(".field ").append(f.modifierPrefix())
                        .append(" ").append(nameToDescriptor(f.type))
                        .append(":").append(f.name);
                // We don't have the static initial value parsed, so just emit a comment
                sb.append("\n");
            }
        }

        // Instance fields
        if (hasInstanceFields(data)) {
            sb.append("\n# instance fields\n");
            for (DexParser.FieldInfo f : data.fields) {
                if (f.isStatic) continue;
                sb.append(".field ").append(f.modifierPrefix())
                        .append(" ").append(nameToDescriptor(f.type))
                        .append(":").append(f.name).append("\n");
            }
        }

        // Direct methods (constructor + private static etc.)
        if (hasDirectMethods(data)) {
            sb.append("\n# direct methods\n");
            for (DexParser.MethodInfo m : data.methods) {
                if (!m.isDirect) continue;
                appendMethod(sb, m);
            }
        }

        // Virtual methods
        if (hasVirtualMethods(data)) {
            sb.append("\n# virtual methods\n");
            for (DexParser.MethodInfo m : data.methods) {
                if (m.isDirect) continue;
                appendMethod(sb, m);
            }
        }

        return sb.toString();
    }

    private static boolean hasStaticFields(DexParser.ClassData d) {
        for (DexParser.FieldInfo f : d.fields) if (f.isStatic) return true;
        return false;
    }
    private static boolean hasInstanceFields(DexParser.ClassData d) {
        for (DexParser.FieldInfo f : d.fields) if (!f.isStatic) return true;
        return false;
    }
    private static boolean hasDirectMethods(DexParser.ClassData d) {
        for (DexParser.MethodInfo m : d.methods) if (m.isDirect) return true;
        return false;
    }
    private static boolean hasVirtualMethods(DexParser.ClassData d) {
        for (DexParser.MethodInfo m : d.methods) if (!m.isDirect) return true;
        return false;
    }

    private static void appendMethod(StringBuilder sb, DexParser.MethodInfo m) {
        sb.append(".method ").append(m.modifierPrefix())
                .append(" ").append(m.name)
                .append(smaliPrototype(m.prototype)).append("\n");
        sb.append(".end method\n\n");
    }

    /** Convert "(int, String) → void" to "(ILjava/lang/String;)V" */
    private static String smaliPrototype(String proto) {
        if (proto == null) return "()V";
        // proto looks like "(int, String) → void"
        int arrow = proto.indexOf('→');
        if (arrow < 0) return "()V";
        String params = proto.substring(1, proto.indexOf(')')).trim();
        String returnType = proto.substring(arrow + 1).trim();
        StringBuilder sb = new StringBuilder("(");
        if (!params.isEmpty()) {
            for (String p : params.split(",")) {
                sb.append(nameToDescriptor(p.trim()));
            }
        }
        sb.append(")").append(nameToDescriptor(returnType));
        return sb.toString();
    }

    /** Convert "java.lang.String" → "Ljava/lang/String;", "int" → "I", etc. */
    private static String nameToDescriptor(String name) {
        if (name == null || name.isEmpty()) return "V";
        int arr = 0;
        while (name.endsWith("[]")) {
            arr++;
            name = name.substring(0, name.length() - 2);
        }
        StringBuilder sb = new StringBuilder();
        String primitive = primitiveDescriptor(name);
        if (primitive != null) {
            sb.append(primitive);
        } else if (name.startsWith("L") && name.endsWith(";")) {
            // Already a descriptor
            sb.append(name);
        } else {
            sb.append("L").append(name.replace('.', '/')).append(";");
        }
        for (int i = 0; i < arr; i++) sb.insert(0, "[");
        return sb.toString();
    }

    private static String primitiveDescriptor(String name) {
        switch (name) {
            case "void": return "V";
            case "boolean": return "Z";
            case "byte": return "B";
            case "short": return "S";
            case "char": return "C";
            case "int": return "I";
            case "long": return "J";
            case "float": return "F";
            case "double": return "D";
            default: return null;
        }
    }

    private static String modifiers(int accessFlags) {
        StringBuilder sb = new StringBuilder();
        if ((accessFlags & 0x0001) != 0) sb.append("public ");
        if ((accessFlags & 0x0002) != 0) sb.append("private ");
        if ((accessFlags & 0x0004) != 0) sb.append("protected ");
        if ((accessFlags & 0x0008) != 0) sb.append("static ");
        if ((accessFlags & 0x0010) != 0) sb.append("final ");
        if ((accessFlags & 0x0200) != 0) sb.append("interface ");
        if ((accessFlags & 0x0400) != 0) sb.append("abstract ");
        if ((accessFlags & 0x00010000) != 0) sb.append("native ");
        if ((accessFlags & 0x04000000) != 0) sb.append("annotation ");
        if ((accessFlags & 0x20000000) != 0) sb.append("enum ");
        return sb.toString().trim() + (sb.length() > 0 ? " " : "");
    }
}
