package io.github.chains_project.aotp.oops.cp;

/**
 * A single entry in a HotSpot {@code ConstantPool}.
 *
 * @param index   constant pool index (1-based)
 * @param tag     raw JVM/HotSpot tag byte (see {@code ClassConstants})
 * @param tagName human-readable tag name
 * @param value   decoded value (symbol text, numeric literal, or index references)
 */
public record ConstantPoolEntry(int index, int tag, String tagName, String value) {}