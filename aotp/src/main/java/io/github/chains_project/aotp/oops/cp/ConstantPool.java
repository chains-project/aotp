package io.github.chains_project.aotp.oops.cp;

import java.util.List;

/**
 * The constant pool of a single class as stored in the AOT cache.
 *
 * @param className fully-qualified class name (e.g. {@code java/lang/String})
 * @param entries   ordered list of CP entries, starting at index 1
 */
public record ConstantPool(String className, List<ConstantPoolEntry> entries) {}