package io.github.chains_project.aotp;

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.chains_project.aotp.header.CDSFileMapRegion;
import io.github.chains_project.aotp.header.FileMapHeader;
import io.github.chains_project.aotp.header.GenericHeader;
import io.github.chains_project.aotp.header.RegionData;
import io.github.chains_project.aotp.oops.cp.ConstantPool;
import io.github.chains_project.aotp.oops.cp.ConstantPoolEntry;
import io.github.chains_project.aotp.oops.klass.ClassEntry;
import io.github.chains_project.aotp.oops.klass.InstanceClass;
import io.github.chains_project.aotp.oops.klass.ObjArrayClass;
import io.github.chains_project.aotp.oops.klass.TypeArrayClass;
import io.github.chains_project.aotp.utils.ByteReader;
import io.github.chains_project.aotp.utils.LittleEndianRandomAccessFile;

/**
 * Programmatic API for reading AOT cache files. Use this from code or unit tests
 * instead of invoking the CLI.
 */
public final class AotpApi {

    // Magic number for AOTCache files
    // https://github.com/openjdk/jdk/blob/6f6966b28b2c5a18b001be49f5db429c667d7a8f/src/hotspot/share/include/cds.h#L39
    private static final int AOT_MAGIC = 0xf00baba2;

    private static final int AOTCONFIG_MAGIC = 0xcafea07c;

    // ConstantPool header size in bytes (64-bit layout):
    //   vtable(8) + _tags(8) + _cache(8) + _pool_holder(8) + _operands(8) + _resolved_klasses(8)
    //   + _major_version(2) + _minor_version(2) + _generic_signature_index(2)
    //   + _source_file_name_index(2) + _flags(2) + padding(2) + _length(4) + _saved(4)
    //   + trailing struct padding(4) = 72 bytes
    //
    // C++ uses sizeof(ConstantPool) = 72 because the struct alignment (largest member = 8 bytes)
    // requires 4 bytes of trailing padding after _saved to reach the next 8-byte boundary.
    // The inline CP data array starts at this->base() = (char*)this + sizeof(ConstantPool) = +72.
    private static final int CP_HEADER_SIZE = 72;

    // CP slot size on 64-bit (intptr_t)
    private static final int CP_SLOT_SIZE = 8;

    // JVM constant pool tag values (JVM spec + HotSpot internal)
    private static final int JVM_CONSTANT_Utf8               = 1;
    private static final int JVM_CONSTANT_Integer            = 3;
    private static final int JVM_CONSTANT_Float              = 4;
    private static final int JVM_CONSTANT_Long               = 5;
    private static final int JVM_CONSTANT_Double             = 6;
    private static final int JVM_CONSTANT_Class              = 7;
    private static final int JVM_CONSTANT_String             = 8;
    private static final int JVM_CONSTANT_Fieldref           = 9;
    private static final int JVM_CONSTANT_Methodref          = 10;
    private static final int JVM_CONSTANT_InterfaceMethodref = 11;
    private static final int JVM_CONSTANT_NameAndType        = 12;
    private static final int JVM_CONSTANT_MethodHandle       = 15;
    private static final int JVM_CONSTANT_MethodType         = 16;
    private static final int JVM_CONSTANT_Dynamic            = 17;
    private static final int JVM_CONSTANT_InvokeDynamic      = 18;
    // HotSpot-internal tags
    private static final int JVM_CONSTANT_Invalid                = 0;
    private static final int JVM_CONSTANT_UnresolvedClass        = 100;
    private static final int JVM_CONSTANT_ClassIndex             = 101;
    private static final int JVM_CONSTANT_StringIndex            = 102;
    private static final int JVM_CONSTANT_UnresolvedClassInError = 103;
    private static final int JVM_CONSTANT_MethodHandleInError    = 104;
    private static final int JVM_CONSTANT_MethodTypeInError      = 105;

    // Number of cloned C++ vtable types in the archive, defined by CPP_VTABLE_TYPES_DO in cppVtables.cpp.
    // Order: ConstantPool, InstanceKlass, InstanceClassLoaderKlass, InstanceMirrorKlass,
    //        InstanceRefKlass, InstanceStackChunkKlass, Method, ObjArrayKlass, TypeArrayKlass
    private static final int NUM_CLONED_VTABLE_KINDS = 9;

    // Indices into the cloned vtable array (matching ClonedVtableKind enum in cppVtables.cpp)
    private static final int INSTANCE_KLASS_KIND = 1;
    private static final int INSTANCE_CLASSLOADER_KLASS_KIND = 2;
    private static final int INSTANCE_MIRROR_KLASS_KIND = 3;
    private static final int INSTANCE_REF_KLASS_KIND = 4;
    private static final int INSTANCE_STACKCHUNK_KLASS_KIND = 5;
    private static final int OBJ_ARRAY_KLASS_KIND = 7;
    private static final int TYPE_ARRAY_KLASS_KIND = 8;

    private AotpApi() {}

    /**
     * Reads the cloned C++ vtable pointers from the start of the RW region.
     * The CppVtableInfo structures are laid out sequentially, each containing:
     *   - vtable_size (8 bytes, intptr_t)
     *   - cloned_vtable[vtable_size] (vtable_size * 8 bytes)
     */
    private static long[] readClonedVtablePointers(byte[] rwBytes, long rwMappingOffset, long baseAddress) {
        long[] vtablePointers = new long[NUM_CLONED_VTABLE_KINDS];
        int offset = 0;
        for (int i = 0; i < NUM_CLONED_VTABLE_KINDS; i++) {
            if (offset + 8 > rwBytes.length) {
                break;
            }
            long vtableSize = ByteReader.readLongLE(rwBytes, offset);
            // Skip past vtable_size field (8 bytes)
            offset += 8;
            // The vtable pointer = base address + mapping offset of RW region + offset of cloned_vtable field
            vtablePointers[i] = baseAddress + rwMappingOffset + offset;
            // Skip past the vtable entries (vtableSize * 8 bytes)
            offset += (int)(vtableSize * 8);
        }
        return vtablePointers;
    }

    private static List<Long> getPatternsForClasses(long[] vtablePointers) {
        // Match all klass kinds that are stored in the RW region. The vtable pointer
        // at the start of each entry determines which concrete parse method is called:
        // ObjArrayKlass → ObjArrayClass.parse(), TypeArrayKlass → TypeArrayClass.parse(),
        // all InstanceKlass variants → InstanceClass.parse().
        return List.of(
            vtablePointers[INSTANCE_KLASS_KIND],
            vtablePointers[INSTANCE_CLASSLOADER_KLASS_KIND],
            vtablePointers[INSTANCE_MIRROR_KLASS_KIND],
            vtablePointers[INSTANCE_REF_KLASS_KIND],
            vtablePointers[INSTANCE_STACKCHUNK_KLASS_KIND],
            vtablePointers[OBJ_ARRAY_KLASS_KIND],
            vtablePointers[TYPE_ARRAY_KLASS_KIND]
        );
    }

    private static List<ClassEntry> loadClasses(LittleEndianRandomAccessFile file,
            RegionData rwRegionData,
            long requestedBaseAddress) throws IOException {
        byte[] bytes = rwRegionData.bytes();
        if (bytes.length == 0) {
            return List.of();
        }

        long rwMappingOffset = rwRegionData.region().mappingOffset;
        long[] vtablePointers = readClonedVtablePointers(bytes, rwMappingOffset, requestedBaseAddress);
        List<Long> patterns = getPatternsForClasses(vtablePointers);
        long objArrayVtable = vtablePointers[OBJ_ARRAY_KLASS_KIND];
        long typeArrayVtable = vtablePointers[TYPE_ARRAY_KLASS_KIND];
        List<ClassEntry> entries = new ArrayList<>();
        final int len = bytes.length;

        for (int offset = 0; offset + 8 <= len; offset += 8) {
            long value = ByteReader.readLongLE(bytes, offset);
            if (!patterns.contains(value)) {
                continue;
            }
            int entryStart = offset;
            ClassEntry parsed;
            if (value == objArrayVtable) {
                parsed = ObjArrayClass.parse(bytes, entryStart);
            } else if (value == typeArrayVtable) {
                parsed = TypeArrayClass.parse(bytes, entryStart);
            } else {
                parsed = InstanceClass.parse(bytes, entryStart);
            }
            String className = readSymbolName(file, parsed.namePointer(), requestedBaseAddress);
            if (className != null) {
                parsed.setName(className);
                entries.add(parsed);
            }
        }

        return entries;
    }

    /**
     * Reads a symbol name from the ro region using an absolute address.
     * Symbol format: hash_and_refcount (4 bytes), length (2 bytes), body[length] (UTF-8)
     */
    private static String readSymbolName(LittleEndianRandomAccessFile file,
            long symbolAbsoluteAddress, long requestedBaseAddress) throws IOException {
        long symbolOffset = symbolAbsoluteAddress - requestedBaseAddress;

        if (symbolOffset < 0 || symbolOffset >= file.length()) {
            return null;
        }

        long currentPos = file.getFilePointer();
        try {
            file.seek(symbolOffset);
            file.skipBytes(4);
            int length = file.readShort() & 0xFFFF;
            if (length < 0 || length > 65535) {
                return null;
            }
            byte[] nameBytes = new byte[length];
            file.readFully(nameBytes);
            return new String(nameBytes, StandardCharsets.UTF_8);
        } finally {
            file.seek(currentPos);
        }
    }

    private static void validateMagic(GenericHeader genericHeader) throws IOException {
        if (genericHeader.magic() != AOT_MAGIC && genericHeader.magic() != AOTCONFIG_MAGIC) {
            String actualMagic = String.format("%08x", genericHeader.magic());
            throw new IOException("I can only parse AOTCache and AOTConfiguration files (actual: " + actualMagic + ")");
        }
    }

    /**
     * Prints the file map header to the given appendable.
     *
     * @param filePath path to the AOT cache file
     * @param out      where to write the header
     * @throws IOException if the file cannot be read or is invalid
     */
    public static void printHeader(String filePath, Appendable out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            LittleEndianRandomAccessFile file = new LittleEndianRandomAccessFile(raf);
            GenericHeader genericHeader = new GenericHeader(file);
            CDSFileMapRegion[] regions = new CDSFileMapRegion[5];
            for (int i = 0; i < 5; i++) {
                regions[i] = new CDSFileMapRegion(file);
            }
            FileMapHeader fileMapHeader = new FileMapHeader(file);
            validateMagic(genericHeader);
            FileMapHeader.print(genericHeader, regions, fileMapHeader, out);
        } catch (EOFException e) {
            throw new IOException("Invalid AOTCache file: file too short", e);
        }
    }

    /**
     * Returns the list of class names found in the RW region.
     *
     * @param filePath path to the AOT cache file
     * @return list of class names (never null)
     * @throws IOException if the file cannot be read or is invalid
     */
    public static List<String> listClassNames(String filePath) throws IOException {
        return listClasses(filePath).stream().map(ClassEntry::getName).toList();
    }

    /**
     * Returns the list of classes found in the RW region.
     *
     * @param filePath path to the AOT cache file
     * @return list of ClassEntry (never null)
     * @throws IOException if the file cannot be read or is invalid
     */
    public static List<ClassEntry> listClasses(String filePath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            LittleEndianRandomAccessFile file = new LittleEndianRandomAccessFile(raf);
            GenericHeader genericHeader = new GenericHeader(file);
            CDSFileMapRegion[] regions = new CDSFileMapRegion[5];
            for (int i = 0; i < 5; i++) {
                regions[i] = new CDSFileMapRegion(file);
            }
            FileMapHeader fileMapHeader = new FileMapHeader(file);
            RegionData[] regionData = RegionData.loadAll(file, regions);
            validateMagic(genericHeader);
            RegionData rwRegionData = regionData[0];
            if (rwRegionData.bytes().length == 0) {
                return List.of();
            }
            return loadClasses(file, rwRegionData, fileMapHeader.requestedBaseAddress());
        } catch (EOFException e) {
            throw new IOException("Invalid AOTCache file: file too short", e);
        }
    }

    /**
     * Returns sizes for a batch of classes. Only classes that are present in the
     * AOT cache are included in the result.
     *
     * @param filePath   path to the AOT cache file
     * @param classNames list of fully qualified class names
     * @return map from class name to its size (only for found classes)
     * @throws IOException if the file cannot be read or is invalid
     */
    public static Map<ClassEntry, Integer> getClassSizes(String filePath, List<String> classNames) throws IOException {
        List<ClassEntry> classes = listClasses(filePath);
        Map<ClassEntry, Integer> sizeByClassEntry = new HashMap<>();
        for (ClassEntry entry : classes) {
            sizeByClassEntry.put(entry, entry.getSize());
        }
        Map<ClassEntry, Integer> result = new HashMap<>();
        for (ClassEntry entry : classes) {
            if (classNames.contains(entry.getName())) {
                result.put(entry, sizeByClassEntry.get(entry));
            }
        }
        return result;
    }

    /**
     * Pretty-prints the fields of the specified class to the given stream.
     *
     * @param filePath  path to the AOT cache file
     * @param className fully qualified class name
     * @param out       where to write the output
     * @return true if the class was found and printed, false if not found
     * @throws IOException if the file cannot be read or is invalid
     */
    public static boolean printClass(String filePath, String className, PrintStream out) throws IOException {
        List<ClassEntry> classes = listClasses(filePath);
        for (ClassEntry entry : classes) {
            if (entry.getName().equals(className)) {
                entry.print(out);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the constant pool entries for every {@code InstanceClass} found in the AOT cache.
     * Array classes ({@code ObjArrayClass}, {@code TypeArrayClass}) are excluded because they
     * do not have an associated {@code ConstantPool}.
     *
     * @param filePath path to the AOT cache file
     * @return list of {@link ConstantPool}, one per instance class (never null)
     * @throws IOException if the file cannot be read or is invalid
     */
    public static List<ConstantPool> listConstantPools(String filePath) throws IOException {
        return listConstantPools(filePath, null);
    }

    /**
     * Returns the constant pool entries for every {@code InstanceClass} found in the AOT cache,
     * optionally filtering to one exact class name.
     *
     * @param filePath path to the AOT cache file
     * @param className exact internal JVM class name, or null to return all constant pools
     * @return list of {@link ConstantPool}, one per matching instance class (never null)
     * @throws IOException if the file cannot be read or is invalid
     */
    public static List<ConstantPool> listConstantPools(String filePath, String className) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            LittleEndianRandomAccessFile file = new LittleEndianRandomAccessFile(raf);
            GenericHeader genericHeader = new GenericHeader(file);
            CDSFileMapRegion[] regions = new CDSFileMapRegion[5];
            for (int i = 0; i < 5; i++) {
                regions[i] = new CDSFileMapRegion(file);
            }
            FileMapHeader fileMapHeader = new FileMapHeader(file);
            RegionData[] regionData = RegionData.loadAll(file, regions);
            validateMagic(genericHeader);
            RegionData rwRegionData = regionData[0];
            if (rwRegionData.bytes().length == 0) {
                return List.of();
            }
            long requestedBaseAddress = fileMapHeader.requestedBaseAddress();
            List<ClassEntry> classes = loadClasses(file, rwRegionData, requestedBaseAddress);
            List<ConstantPool> result = new ArrayList<>();
            for (ClassEntry entry : classes) {
                if (className != null && !className.equals(entry.getName())) {
                    continue;
                }
                if (entry instanceof InstanceClass klass) {
                    result.add(loadConstantPool(file, klass, requestedBaseAddress));
                }
            }
            return result;
        } catch (EOFException e) {
            throw new IOException("Invalid AOTCache file: file too short", e);
        }
    }

    /**
     * Parses the {@code ConstantPool} for a single {@link InstanceClass}.
     *
     * Layout of the {@code ConstantPool} C++ struct (64-bit):
     * <pre>
     *  offset  0 : vtable pointer        (8 bytes)
     *  offset  8 : _tags pointer         (8 bytes)  → Array&lt;u1&gt; in RO region
     *  offset 16 : _cache pointer        (8 bytes)
     *  offset 24 : _pool_holder pointer  (8 bytes)
     *  offset 32 : _operands pointer     (8 bytes)
     *  offset 40 : _resolved_klasses ptr (8 bytes)
     *  offset 48 : _major_version  u2    (2 bytes)
     *  offset 50 : _minor_version  u2    (2 bytes)
     *  offset 52 : _generic_sig_index u2 (2 bytes)
     *  offset 54 : _source_file_index u2 (2 bytes)
     *  offset 56 : _flags           u2   (2 bytes)
     *  offset 58 : (padding)             (2 bytes)
     *  offset 60 : _length          int  (4 bytes)
     *  offset 64 : _saved union     int  (4 bytes)
     *  offset 68 : (trailing struct padding)  (4 bytes)
     *  offset 72 : inline CP data — one intptr_t (8 bytes) per slot
     * </pre>
     *
     * The {@code Array&lt;u1&gt;} pointed to by {@code _tags} has layout:
     * <pre>
     *  offset 0 : _length int  (4 bytes)
     *  offset 4 : data u1[]   (_length bytes)
     * </pre>
     *
     * File offsets are computed as {@code absoluteAddress - requestedBaseAddress},
     * following the same convention used by {@link #readSymbolName}.
     */
    private static ConstantPool loadConstantPool(LittleEndianRandomAccessFile file,
            InstanceClass klass, long requestedBaseAddress) throws IOException {
        long cpAbsAddr = klass.constants;
        if (cpAbsAddr == 0) {
            return new ConstantPool(klass.getName(), List.of());
        }
        long cpFileOffset = cpAbsAddr - requestedBaseAddress;
        long fileLen = file.length();
        if (cpFileOffset < 0 || cpFileOffset + CP_HEADER_SIZE > fileLen) {
            return new ConstantPool(klass.getName(), List.of());
        }

        long savedPos = file.getFilePointer();
        try {
            file.seek(cpFileOffset);
            file.skipBytes(8);                   // vtable pointer
            long tagsPointer = file.readLong();  // _tags
            file.skipBytes(8 * 4);               // _cache, _pool_holder, _operands, _resolved_klasses
            file.skipBytes(5 * 2);               // five u2 fields
            file.skipBytes(2);                   // padding between u2 block and _length
            int length = file.readInt();         // _length  (now at offset 64)
            file.skipBytes(4);                   // _saved   (now at offset 68)
            file.skipBytes(4);                   // trailing struct padding — sizeof(ConstantPool)=72

            if (length <= 0 || length > 100_000) {
                return new ConstantPool(klass.getName(), List.of());
            }

            byte[] tags = readTagsArray(file, tagsPointer, requestedBaseAddress, length);
            if (tags == null) {
                return new ConstantPool(klass.getName(), List.of());
            }

            long cpDataOffset = cpFileOffset + CP_HEADER_SIZE;
            List<ConstantPoolEntry> entries = new ArrayList<>();
            for (int i = 1; i < length; i++) {
                if (i >= tags.length) break;
                int tag = Byte.toUnsignedInt(tags[i]);
                long slotFileOffset = cpDataOffset + (long) i * CP_SLOT_SIZE;
                if (slotFileOffset + CP_SLOT_SIZE > fileLen) break;

                file.seek(slotFileOffset);
                long slot = file.readLong();

                String value = decodeSlot(file, tag, slot, requestedBaseAddress);
                entries.add(new ConstantPoolEntry(i, tag, cpTagName(tag), value));

                // Long and Double occupy two consecutive slots
                if (tag == JVM_CONSTANT_Long || tag == JVM_CONSTANT_Double) {
                    i++;
                }
            }
            return new ConstantPool(klass.getName(), entries);
        } finally {
            file.seek(savedPos);
        }
    }

    /**
     * Reads the tag bytes from an {@code Array<u1>} in the archive.
     * Returns {@code null} if the pointer is invalid or the length does not match.
     */
    private static byte[] readTagsArray(LittleEndianRandomAccessFile file, long tagsPointer,
            long requestedBaseAddress, int expectedLength) throws IOException {
        if (tagsPointer == 0) return null;
        long tagsFileOffset = tagsPointer - requestedBaseAddress;
        long fileLen = file.length();
        if (tagsFileOffset < 0 || tagsFileOffset + 4 > fileLen) return null;

        long savedPos = file.getFilePointer();
        try {
            file.seek(tagsFileOffset);
            int tagsLength = file.readInt();
            if (tagsLength != expectedLength || tagsLength <= 0
                    || tagsFileOffset + 4 + tagsLength > fileLen) {
                return null;
            }
            byte[] tags = new byte[tagsLength];
            file.readFully(tags);
            return tags;
        } finally {
            file.seek(savedPos);
        }
    }

    /**
     * Decodes a single 8-byte constant pool slot into a human-readable string.
     *
     * <p>Packed-int fields (Fieldref, NameAndType, etc.) occupy only the low 4 bytes of the
     * slot on little-endian. The high short/low short conventions follow HotSpot's
     * {@code extractHighShortFromInt} / {@code extractLowShortFromInt}:
     * <ul>
     *   <li>high short = {@code (packed >> 16) & 0xFFFF}
     *   <li>low  short = {@code packed & 0xFFFF}
     * </ul>
     */
    private static String decodeSlot(LittleEndianRandomAccessFile file, int tag, long slot,
            long requestedBaseAddress) throws IOException {
        return switch (tag) {
            case JVM_CONSTANT_Utf8 -> {
                String s = readSymbol(file, slot, requestedBaseAddress);
                yield s != null ? s : "0x" + Long.toHexString(slot);
            }
            case JVM_CONSTANT_Integer -> String.valueOf((int) slot);
            case JVM_CONSTANT_Float   -> String.valueOf(Float.intBitsToFloat((int) slot));
            case JVM_CONSTANT_Long    -> String.valueOf(slot);
            case JVM_CONSTANT_Double  -> String.valueOf(Double.longBitsToDouble(slot));
            case JVM_CONSTANT_Class   -> {
                // CPKlassSlot: high16 = name_index, low16 = resolved_klass_index
                int packed = (int) slot;
                yield "name_index=" + ((packed >> 16) & 0xFFFF)
                        + " resolved_klass_index=" + (packed & 0xFFFF);
            }
            case JVM_CONSTANT_String -> {
                // Unresolved string: Symbol* with the low bit set as a pseudo-string marker
                String s = readSymbol(file, slot & ~1L, requestedBaseAddress);
                yield s != null ? "\"" + s + "\"" : "0x" + Long.toHexString(slot);
            }
            case JVM_CONSTANT_UnresolvedClass, JVM_CONSTANT_UnresolvedClassInError -> {
                String s = readSymbol(file, slot, requestedBaseAddress);
                yield s != null ? s : "0x" + Long.toHexString(slot);
            }
            case JVM_CONSTANT_Fieldref, JVM_CONSTANT_Methodref,
                    JVM_CONSTANT_InterfaceMethodref -> {
                // low16 = class_index, high16 = name_and_type_index
                int packed = (int) slot;
                yield "class_index=" + (packed & 0xFFFF)
                        + " name_and_type_index=" + ((packed >> 16) & 0xFFFF);
            }
            case JVM_CONSTANT_NameAndType -> {
                // low16 = name_index, high16 = descriptor_index
                int packed = (int) slot;
                yield "name_index=" + (packed & 0xFFFF)
                        + " descriptor_index=" + ((packed >> 16) & 0xFFFF);
            }
            case JVM_CONSTANT_MethodHandle -> {
                // low16 = ref_kind, high16 = member_index
                int packed = (int) slot;
                yield "ref_kind=" + (packed & 0xFFFF)
                        + " member_index=" + ((packed >> 16) & 0xFFFF);
            }
            case JVM_CONSTANT_MethodType -> {
                yield "descriptor_index=" + ((int) slot & 0xFFFF);
            }
            case JVM_CONSTANT_Dynamic, JVM_CONSTANT_InvokeDynamic -> {
                // low16 = bootstrap_method_attr_index, high16 = name_and_type_index
                int packed = (int) slot;
                yield "bsm_index=" + (packed & 0xFFFF)
                        + " name_and_type_index=" + ((packed >> 16) & 0xFFFF);
            }
            default -> "0x" + Long.toHexString(slot);
        };
    }

    /**
     * Reads a HotSpot {@code Symbol} from the archive at the given absolute address.
     *
     * Symbol layout:
     * <pre>
     *  offset 0 : hash_and_refcount  (4 bytes, skipped)
     *  offset 4 : length             (2 bytes, unsigned short)
     *  offset 6 : body[length]       (UTF-8 bytes)
     * </pre>
     *
     * Returns {@code null} if the address is out of range or the read fails.
     */
    private static String readSymbol(LittleEndianRandomAccessFile file, long symbolAbsAddr,
            long requestedBaseAddress) throws IOException {
        if (symbolAbsAddr == 0) return null;
        long symbolFileOffset = symbolAbsAddr - requestedBaseAddress;
        long fileLen = file.length();
        if (symbolFileOffset < 0 || symbolFileOffset + 6 > fileLen) return null;

        long savedPos = file.getFilePointer();
        try {
            file.seek(symbolFileOffset);
            file.skipBytes(4);                         // hash_and_refcount
            int len = file.readShort() & 0xFFFF;
            if (symbolFileOffset + 6 + len > fileLen) return null;
            byte[] nameBytes = new byte[len];
            file.readFully(nameBytes);
            return new String(nameBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            file.seek(savedPos);
        }
    }

    /** Returns a human-readable name for a JVM/HotSpot constant pool tag byte. */
    private static String cpTagName(int tag) {
        return switch (tag) {
            case JVM_CONSTANT_Invalid               -> "Invalid";
            case JVM_CONSTANT_Utf8                  -> "Utf8";
            case JVM_CONSTANT_Integer               -> "Integer";
            case JVM_CONSTANT_Float                 -> "Float";
            case JVM_CONSTANT_Long                  -> "Long";
            case JVM_CONSTANT_Double                -> "Double";
            case JVM_CONSTANT_Class                 -> "Class";
            case JVM_CONSTANT_String                -> "String";
            case JVM_CONSTANT_Fieldref              -> "Fieldref";
            case JVM_CONSTANT_Methodref             -> "Methodref";
            case JVM_CONSTANT_InterfaceMethodref    -> "InterfaceMethodref";
            case JVM_CONSTANT_NameAndType           -> "NameAndType";
            case JVM_CONSTANT_MethodHandle          -> "MethodHandle";
            case JVM_CONSTANT_MethodType            -> "MethodType";
            case JVM_CONSTANT_Dynamic               -> "Dynamic";
            case JVM_CONSTANT_InvokeDynamic         -> "InvokeDynamic";
            case JVM_CONSTANT_UnresolvedClass       -> "UnresolvedClass";
            case JVM_CONSTANT_ClassIndex            -> "ClassIndex";
            case JVM_CONSTANT_StringIndex           -> "StringIndex";
            case JVM_CONSTANT_UnresolvedClassInError -> "UnresolvedClassInError";
            case JVM_CONSTANT_MethodHandleInError   -> "MethodHandleInError";
            case JVM_CONSTANT_MethodTypeInError     -> "MethodTypeInError";
            default                                 -> "Unknown(" + tag + ")";
        };
    }
}
