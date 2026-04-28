package io.github.chains_project.aotp.jar;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;

import io.github.chains_project.aotp.oops.cp.ConstantPool;
import io.github.chains_project.aotp.oops.cp.ConstantPoolEntry;

/**
 * Reads raw classfile constant pools from a jar.
 */
public final class JarConstantPoolReader {

    private static final int JVM_CONSTANT_Utf8 = 1;
    private static final int JVM_CONSTANT_Integer = 3;
    private static final int JVM_CONSTANT_Float = 4;
    private static final int JVM_CONSTANT_Long = 5;
    private static final int JVM_CONSTANT_Double = 6;
    private static final int JVM_CONSTANT_Class = 7;
    private static final int JVM_CONSTANT_String = 8;
    private static final int JVM_CONSTANT_Fieldref = 9;
    private static final int JVM_CONSTANT_Methodref = 10;
    private static final int JVM_CONSTANT_InterfaceMethodref = 11;
    private static final int JVM_CONSTANT_NameAndType = 12;
    private static final int JVM_CONSTANT_MethodHandle = 15;
    private static final int JVM_CONSTANT_MethodType = 16;
    private static final int JVM_CONSTANT_Dynamic = 17;
    private static final int JVM_CONSTANT_InvokeDynamic = 18;
    private static final int JVM_CONSTANT_Module = 19;
    private static final int JVM_CONSTANT_Package = 20;

    private JarConstantPoolReader() {}

    public static Map<String, ConstantPool> readJar(Path jarPath) throws IOException {
        return readJar(jarPath, null);
    }

    public static Map<String, ConstantPool> readJar(Path jarPath, String classNameFilter) throws IOException {
        Map<String, ConstantPool> constantPools = new LinkedHashMap<>();
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zipFile.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    ConstantPool cp = parseClass(bytes);
                    if (cp != null && (classNameFilter == null || classNameFilter.equals(cp.className()))) {
                        constantPools.put(cp.className(), cp);
                    }
                }
            }
        }
        return constantPools;
    }

    private static ConstantPool parseClass(byte[] classBytes) throws IOException {
        ClassReader classReader = new ClassReader(classBytes);
        String className = classReader.getClassName();
        if (className == null || "module-info".equals(className) || "package-info".equals(className)) {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Invalid classfile magic for " + className);
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major

            int cpCount = in.readUnsignedShort();
            List<ConstantPoolEntry> entries = new ArrayList<>();
            for (int index = 1; index < cpCount; index++) {
                int tag = in.readUnsignedByte();
                String value = switch (tag) {
                    case JVM_CONSTANT_Utf8 -> readUtf8(in);
                    case JVM_CONSTANT_Integer -> String.valueOf(in.readInt());
                    case JVM_CONSTANT_Float -> String.valueOf(Float.intBitsToFloat(in.readInt()));
                    case JVM_CONSTANT_Long -> {
                        long v = in.readLong();
                        index++;
                        yield String.valueOf(v);
                    }
                    case JVM_CONSTANT_Double -> {
                        double v = Double.longBitsToDouble(in.readLong());
                        index++;
                        yield String.valueOf(v);
                    }
                    case JVM_CONSTANT_Class -> "name_index=" + in.readUnsignedShort();
                    case JVM_CONSTANT_String -> "string_index=" + in.readUnsignedShort();
                    case JVM_CONSTANT_Fieldref, JVM_CONSTANT_Methodref,
                            JVM_CONSTANT_InterfaceMethodref -> {
                        int classIndex = in.readUnsignedShort();
                        int nameAndTypeIndex = in.readUnsignedShort();
                        yield "class_index=" + classIndex + " name_and_type_index=" + nameAndTypeIndex;
                    }
                    case JVM_CONSTANT_NameAndType -> {
                        int nameIndex = in.readUnsignedShort();
                        int descriptorIndex = in.readUnsignedShort();
                        yield "name_index=" + nameIndex + " descriptor_index=" + descriptorIndex;
                    }
                    case JVM_CONSTANT_MethodHandle -> {
                        int refKind = in.readUnsignedByte();
                        int refIndex = in.readUnsignedShort();
                        yield "ref_kind=" + refKind + " member_index=" + refIndex;
                    }
                    case JVM_CONSTANT_MethodType -> "descriptor_index=" + in.readUnsignedShort();
                    case JVM_CONSTANT_Dynamic, JVM_CONSTANT_InvokeDynamic -> {
                        int bsmIndex = in.readUnsignedShort();
                        int nameAndTypeIndex = in.readUnsignedShort();
                        yield "bsm_index=" + bsmIndex + " name_and_type_index=" + nameAndTypeIndex;
                    }
                    case JVM_CONSTANT_Module -> "name_index=" + in.readUnsignedShort();
                    case JVM_CONSTANT_Package -> "name_index=" + in.readUnsignedShort();
                    default -> throw new IOException("Unsupported constant pool tag " + tag + " in " + className);
                };
                entries.add(new ConstantPoolEntry(index, tag, cpTagName(tag), value));
            }
            return new ConstantPool(className, null, entries);
        }
    }

    private static String readUtf8(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String cpTagName(int tag) {
        return switch (tag) {
            case JVM_CONSTANT_Utf8 -> "Utf8";
            case JVM_CONSTANT_Integer -> "Integer";
            case JVM_CONSTANT_Float -> "Float";
            case JVM_CONSTANT_Long -> "Long";
            case JVM_CONSTANT_Double -> "Double";
            case JVM_CONSTANT_Class -> "Class";
            case JVM_CONSTANT_String -> "String";
            case JVM_CONSTANT_Fieldref -> "Fieldref";
            case JVM_CONSTANT_Methodref -> "Methodref";
            case JVM_CONSTANT_InterfaceMethodref -> "InterfaceMethodref";
            case JVM_CONSTANT_NameAndType -> "NameAndType";
            case JVM_CONSTANT_MethodHandle -> "MethodHandle";
            case JVM_CONSTANT_MethodType -> "MethodType";
            case JVM_CONSTANT_Dynamic -> "Dynamic";
            case JVM_CONSTANT_InvokeDynamic -> "InvokeDynamic";
            case JVM_CONSTANT_Module -> "Module";
            case JVM_CONSTANT_Package -> "Package";
            default -> "Unknown(" + tag + ")";
        };
    }
}
