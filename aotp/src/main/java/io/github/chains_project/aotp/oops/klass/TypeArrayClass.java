package io.github.chains_project.aotp.oops.klass;

import io.github.chains_project.aotp.utils.ByteReader;

/**
 * Concrete {@link ArrayClass} representing a {@code TypeArrayKlass}.
 * https://github.com/openjdk/jdk/blob/jdk-27%2B7/src/hotspot/share/oops/typeArrayKlass.hpp
 */
public final class TypeArrayClass extends ArrayClass {

    public final int maxLength; // jint — maximum number of elements allowed in an array

    private String classNameFromRoRegion;

    private TypeArrayClass(long vTablePointer,
                           int layoutHelper,
                           short kind,
                           byte miscFlags,
                           int superCheckOffset,
                           long name,
                           long secondarySuperCache,
                           long secondarySupers,
                           long[] primarySupers,
                           long javaMirror,
                           long superKlass,
                           long subklass,
                           long nextSibling,
                           long nextLink,
                           long classLoaderData,
                           long prototypeHeader,
                           long secondarySupersBitmap,
                           byte hashSlot,
                           short sharedClassPathIndex,
                           short aotClassFlags,
                           int vtableLen,
                           int archivedMirrorIndex,
                           long jfrTrace,
                           
                           int dimension,
                           long higherDimension,
                           long lowerDimension,
                           
                           int maxLength) {
        super(vTablePointer,
              layoutHelper,
              kind,
              miscFlags,
              superCheckOffset,
              name,
              secondarySuperCache,
              secondarySupers,
              primarySupers,
              javaMirror,
              superKlass,
              subklass,
              nextSibling,
              nextLink,
              classLoaderData,
              prototypeHeader,
              secondarySupersBitmap,
              hashSlot,
              sharedClassPathIndex,
              aotClassFlags,
              vtableLen,
              archivedMirrorIndex,
              jfrTrace,
              dimension,
              higherDimension,
              lowerDimension);
        this.maxLength = maxLength;
    }

    public static TypeArrayClass parse(byte[] bytes, int offset) {
        int pos = offset;

        long vTablePointer = ByteReader.readLongLE(bytes, pos);
        pos += 8;

        int layoutHelper = ByteReader.readIntLE(bytes, pos);
        pos += 4;
        short kind = ByteReader.readShortLE(bytes, pos);
        pos += 2;
        byte miscFlags = (byte) bytes[pos];
        pos += 1;

        pos += 1; // 1-byte padding

        int superCheckOffset = ByteReader.readIntLE(bytes, pos);
        pos += 4;
        pos += 4; // 4-byte padding

        long name = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long secondarySuperCache = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long secondarySupers = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long[] primarySupers = new long[8];
        for (int i = 0; i < 8; i++) {
            primarySupers[i] = ByteReader.readLongLE(bytes, pos);
            pos += 8;
        }
        long javaMirror = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long superKlass = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long subklass = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long nextSibling = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long nextLink = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long classLoaderData = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long prototypeHeader = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long secondarySupersBitmap = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        byte hashSlot = (byte) bytes[pos];
        pos += 1;

        pos += 1; // 1-byte padding

        short sharedClassPathIndex = ByteReader.readShortLE(bytes, pos);
        pos += 2;
        short aotClassFlags = ByteReader.readShortLE(bytes, pos);
        pos += 2;

        pos += 2; // 2-byte padding

        int vtableLen = ByteReader.readIntLE(bytes, pos);
        pos += 4;
        int archivedMirrorIndex = ByteReader.readIntLE(bytes, pos);
        pos += 4;
        long jfrTrace = ByteReader.readLongLE(bytes, pos);
        pos += 8;

        // ArrayKlass fields
        int dimension = ByteReader.readIntLE(bytes, pos);
        pos += 4;
        pos += 4; // 4-byte padding
        long higherDimension = ByteReader.readLongLE(bytes, pos);
        pos += 8;
        long lowerDimension = ByteReader.readLongLE(bytes, pos);
        pos += 8;

        // TypeArrayKlass fields
        int maxLength = ByteReader.readIntLE(bytes, pos);
        pos += 4;
        pos += 4; // 4-byte trailing struct padding

        // There is a lot of 0000000000000000 after this
        // padding to fill the rest of InstanceKlass::header_size(). TypeArrayKlass is much smaller than InstanceKlass,
        // but the space is reserved so that the vtable always starts at the same offset.
        // Finally, there is vtable

        return new TypeArrayClass(vTablePointer,
                                  layoutHelper,
                                  kind,
                                  miscFlags,
                                  superCheckOffset,
                                  name,
                                  secondarySuperCache,
                                  secondarySupers,
                                  primarySupers,
                                  javaMirror,
                                  superKlass,
                                  subklass,
                                  nextSibling,
                                  nextLink,
                                  classLoaderData,
                                  prototypeHeader,
                                  secondarySupersBitmap,
                                  hashSlot,
                                  sharedClassPathIndex,
                                  aotClassFlags,
                                  vtableLen,
                                  archivedMirrorIndex,
                                  jfrTrace,
                                  dimension,
                                  higherDimension,
                                  lowerDimension,
                                  maxLength);
    }

    @Override
    public String getName() {
        return classNameFromRoRegion;
    }

    @Override
    public void setName(String name) {
        this.classNameFromRoRegion = name;
    }

    @Override
    public int getSize() {
        return super.getSize() + 8;
    }
}