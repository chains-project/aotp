package io.github.chains_project.aotp.oops.klass;

/**
 * Abstract representation of a HotSpot {@code ArrayKlass} record.
 * https://github.com/openjdk/jdk/blob/jdk-27%2B7/src/hotspot/share/oops/arrayKlass.hpp
 *
 * Extends {@link ClassEntry} with 3 fields (24 bytes including 4-byte padding).
 */
public abstract class ArrayClass extends ClassEntry {

    public final int dimension;
    public final long higherDimension; // ObjArrayKlass* volatile
    public final long lowerDimension;  // ArrayKlass* volatile

    protected ArrayClass(long vTablePointer,
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
                         long lowerDimension) {
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
              jfrTrace);
        this.dimension = dimension;
        this.higherDimension = higherDimension;
        this.lowerDimension = lowerDimension;
    }

    @Override
    public int getSize() {
        return super.getSize() + 24;
    }
}