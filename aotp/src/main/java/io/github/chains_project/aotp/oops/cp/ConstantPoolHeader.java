package io.github.chains_project.aotp.oops.cp;

/**
 * Parsed metadata fields from the HotSpot {@code ConstantPool} header.
 */
public record ConstantPoolHeader(
        long tagsPointer,
        long cachePointer,
        long poolHolderPointer,
        long operandsPointer,
        long resolvedKlassesPointer,
        int majorVersion,
        int minorVersion,
        int genericSignatureIndex,
        int sourceFileNameIndex,
        int flags,
        int length,
        int saved) {}
