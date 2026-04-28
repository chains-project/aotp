package io.github.chains_project.aotp.integrity;

import java.util.List;

/**
 * Result of an integrity check between a set of JARs and an AOT cache.
 *
 * @param staleInCache      classes present in the AOT cache but absent from the JARs
 * @param mismatchedClasses classes whose JAR Utf8 symbols are not fully covered by the AOT cache
 * @param matchedClasses    classes present in both the JARs and the AOT cache with no symbol gaps
 */
public record IntegrityReport(
        List<String> staleInCache,
        List<ClassMismatch> mismatchedClasses,
        List<String> matchedClasses) {}
