package io.github.chains_project.aotp;

public final class ClassEntry {

    public final int layoutHelper;
    public final int kind;
    public final long miscFlags;
    public final int superCheckOffset;
    public final long _name; // symbol pointer (absolute address)
    public final long secondarySuperCache;
    public final long secondarySupers;
    public final long primarySupers;
    // TODO: more fields to come

    public ClassEntry(int layoutHelper, int kind, long miscFlags, int superCheckOffset,
                      long _name, long secondarySuperCache, long secondarySupers, long primarySupers) {
        this.layoutHelper = layoutHelper;
        this.kind = kind;
        this.miscFlags = miscFlags;
        this.superCheckOffset = superCheckOffset;
        this._name = _name;
        this.secondarySuperCache = secondarySuperCache;
        this.secondarySupers = secondarySupers;
        this.primarySupers = primarySupers;
    }

    /**
     * Parse a single ClassEntry from region bytes at {@code offset} (first field is layoutHelper).
     * Includes 4-byte padding before superCheckOffset.
     */
    public static ClassEntry parse(byte[] bytes, int offset) {
        int pos = offset;
        int layoutHelper = readIntLE(bytes, pos);  pos += 4;
        int kind = readIntLE(bytes, pos);         pos += 4;
        long miscFlags = readLongLE(bytes, pos);   pos += 8;
        pos += 4; // padding before superCheckOffset
        int superCheckOffset = readIntLE(bytes, pos); pos += 4;
        long name = readLongLE(bytes, pos);       pos += 8;
        long secondarySuperCache = readLongLE(bytes, pos); pos += 8;
        long secondarySupers = readLongLE(bytes, pos);     pos += 8;
        long primarySupers = readLongLE(bytes, pos);
        return new ClassEntry(layoutHelper, kind, miscFlags, superCheckOffset,
                name, secondarySuperCache, secondarySupers, primarySupers);
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
             | ((bytes[offset + 1] & 0xFF) << 8)
             | ((bytes[offset + 2] & 0xFF) << 16)
             | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLE(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFF)
             | (((long) bytes[offset + 1] & 0xFF) << 8)
             | (((long) bytes[offset + 2] & 0xFF) << 16)
             | (((long) bytes[offset + 3] & 0xFF) << 24)
             | (((long) bytes[offset + 4] & 0xFF) << 32)
             | (((long) bytes[offset + 5] & 0xFF) << 40)
             | (((long) bytes[offset + 6] & 0xFF) << 48)
             | (((long) bytes[offset + 7] & 0xFF) << 56);
    }
}

