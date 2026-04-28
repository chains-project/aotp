package io.github.chains_project.aotp.integrity;

import java.util.Set;

/**
 * A class whose constant pool differs between the JAR and the AOT cache.
 *
 * @param className      internal JVM class name (slash-separated)
 * @param missingSymbols Utf8 strings present in the JAR class but absent from the AOT cache (injected into JAR)
 * @param addedSymbols   Utf8 strings present in the AOT cache but absent from the JAR class (removed from JAR)
 */
public record ClassMismatch(String className, Set<String> missingSymbols, Set<String> addedSymbols)
        implements Comparable<ClassMismatch> {

    @Override
    public int compareTo(ClassMismatch other) {
        return this.className.compareTo(other.className);
    }
}