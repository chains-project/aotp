package io.github.chains_project.aotp.oops.klass;

import io.github.chains_project.aotp.utils.ByteReader;

public class ClassNames extends ClassEntry {

    private String className;

    public ClassNames(long vTablePointer,
                       int layoutHelper,
                       short kind,
                       byte miscFlags,
                       int superCheckOffset,
                       long name) {
                        super(
                            vTablePointer,
                            layoutHelper,
                            kind,
                            miscFlags,
                            superCheckOffset,
                            name,
                        
                            0L,                 // secondarySuperCache
                            0L,                 // secondarySupers
                            new long[8],        // primarySupers (must be length 8!)
                            0L,                 // javaMirror
                            0L,                 // superKlass
                            0L,                 // subklass
                            0L,                 // nextSibling
                            0L,                 // nextLink
                            0L,                 // classLoaderData
                            0L,                 // prototypeHeader
                            0L,                 // secondarySupersBitmap
                            (byte) 0,           // hashSlot
                            (short) 0,          // sharedClassPathIndex
                            (short) 0,          // aotClassFlags
                            0,                  // vtableLen
                            0,                  // archivedMirrorIndex
                            0L                  // jfrTrace
                        );
    }

    public static ClassNames parse(byte[] bytes, int offset) {
        int pos = offset;

        // 32 bytes to classname
        pos += 32;

        long name = ByteReader.readLongLE(bytes, pos);

        return new ClassNames(0L,
                                0,
                                (short) 0,
                                (byte) 0,
                                0,
                                 name);
    }

    @Override
    public String getName() {
        return className;
    }

    @Override
    public void setName(String name) {
        this.className = name;
    }
}
