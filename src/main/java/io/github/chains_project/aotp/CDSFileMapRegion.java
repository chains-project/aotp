package io.github.chains_project.aotp;

import java.io.IOException;

import com.google.common.io.LittleEndianDataInputStream;

public class CDSFileMapRegion {
    int crc;
    int readOnly;
    int allowExec;
    int isHeapRegion;
    int isBitmapRegion;
    int mappedFromFile;
    long fileOffset;
    long mappingOffset;
    long used;
    long oopmapOffset;
    long oopmapSizeInBits;
    long ptrmapOffset;
    long ptrmapSizeInBits;
    // Note: mappedBase and inReservedSpace are NOT stored in the file
    // They are runtime-only fields, so we skip them

    public CDSFileMapRegion(LittleEndianDataInputStream dis) throws IOException {
        crc = dis.readInt();
        readOnly = dis.readInt();
        allowExec = dis.readInt();
        isHeapRegion = dis.readInt();
        isBitmapRegion = dis.readInt();
        mappedFromFile = dis.readInt();
        
        // Read size_t fields (8 bytes on 64-bit, 4 bytes on 32-bit)
        fileOffset = readSizeT(dis);
        mappingOffset = readSizeT(dis);
        used = readSizeT(dis);
        oopmapOffset = readSizeT(dis);
        oopmapSizeInBits = readSizeT(dis);
        ptrmapOffset = readSizeT(dis);
        ptrmapSizeInBits = readSizeT(dis);
        
        // Mapped base is a pointer so size on 64 bit is 8 bytes
        // _in_reserved_space is a boolean so size is 1 byte + 7 bytes for padding
        dis.skipBytes(8 + 1 + 7);
    }
    
    // AOT cache files are 64-bit only (size_t = 8 bytes)
    // TODO: For 32-bit support, modify this method and add a 4 byte version
    private static long readSizeT(LittleEndianDataInputStream dis) throws IOException {
        // size_t is unsigned, but Java long is signed
        // readLong() correctly reads the bytes, but values >= 2^63 will appear negative
        // Use Long.toUnsignedString() or Long.compareUnsigned() when working with these values
        return dis.readLong();
    }
}
