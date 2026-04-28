package io.github.chains_project.aotp.integrity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.chains_project.aotp.AotpApi;
import io.github.chains_project.aotp.jar.JarConstantPoolReader;
import io.github.chains_project.aotp.oops.cp.ConstantPool;
import io.github.chains_project.aotp.oops.cp.ConstantPoolEntry;

/**
 * Verifies that an AOT cache was built from the exact set of JARs provided.
 */
public final class IntegrityChecker {

    private static final int JVM_CONSTANT_Utf8 = 1;

    private IntegrityChecker() {}

    /**
     * Checks integrity between the given JAR files and an AOT cache.
     *
     * @param jarPaths     JAR files that are claimed to be the source of the AOT cache
     * @param aotCachePath path to the AOT cache file
     * @return an {@link IntegrityReport} describing matches, mismatches, and stale/missing entries
     * @throws IOException if any file cannot be read
     */
    public static IntegrityReport check(List<Path> jarPaths, String aotCachePath) throws IOException {
        Map<String, Set<String>> jarClassUtf8 = new HashMap<>();
        for (Path jar : jarPaths) {
            Map<String, ConstantPool> cps = JarConstantPoolReader.readJar(jar);
            for (Map.Entry<String, ConstantPool> e : cps.entrySet()) {
                Set<String> utf8s = new LinkedHashSet<>();
                for (ConstantPoolEntry entry : e.getValue().entries()) {
                    if (entry.tag() == JVM_CONSTANT_Utf8) {
                        utf8s.add(entry.value());
                    }
                }
                jarClassUtf8.put(e.getKey(), utf8s);
            }
        }

        Map<String, Set<String>> aotClassUtf8 = new HashMap<>();
        for (ConstantPool cp : AotpApi.listConstantPools(aotCachePath)) {
            Set<String> utf8s = new LinkedHashSet<>();
            for (ConstantPoolEntry entry : cp.entries()) {
                if (entry.tag() == JVM_CONSTANT_Utf8) {
                    utf8s.add(entry.value());
                }
            }
            aotClassUtf8.put(cp.className(), utf8s);
        }

        Set<String> aotClassNames = aotClassUtf8.keySet();

        List<String> staleInCache = new ArrayList<>();
        List<ClassMismatch> mismatchedClasses = new ArrayList<>();
        List<String> matchedClasses = new ArrayList<>();

        for (Map.Entry<String, Set<String>> e : jarClassUtf8.entrySet()) {
            String className = e.getKey();
            if (aotClassNames.contains(className)) {
                Set<String> aotUtf8ForClass = aotClassUtf8.get(className);
                Set<String> missing = new LinkedHashSet<>();
                for (String sym : e.getValue()) {
                    if (!aotUtf8ForClass.contains(sym)) {
                        missing.add(sym);
                    }
                }
                Set<String> added = new LinkedHashSet<>();
                for (String sym : aotUtf8ForClass) {
                    if (!e.getValue().contains(sym)) {
                        added.add(sym);
                    }
                }
                if (missing.isEmpty() && added.isEmpty()) {
                    matchedClasses.add(className);
                } else {
                    mismatchedClasses.add(new ClassMismatch(className, missing, added));
                }
            }
        }

        for (String aotClass : aotClassNames) {
            if (!isAppClass(aotClass)) {
                continue;
            }
            String lookupName = stripArrayPrefix(aotClass);
            if (!jarClassUtf8.containsKey(lookupName)) {
                staleInCache.add(aotClass);
            }
        }

        Collections.sort(staleInCache);
        Collections.sort(matchedClasses);
        Collections.sort(mismatchedClasses);

        return new IntegrityReport(staleInCache, mismatchedClasses, matchedClasses);
    }

    /**
     * Returns true if {@code className} is an application class (not a JDK internal or hidden class).
     *
     * <p>Array types are classified by their base element type after stripping the array prefix.
     * Primitive arrays ({@code [B}, {@code [[I}, etc.) are always excluded.
     * JDK classes have base names starting with {@code java/}, {@code javax/}, {@code sun/},
     * {@code jdk/}, {@code com/sun/}, {@code org/xml/}, {@code org/w3c/}, or {@code org/ietf/}.
     * Hidden/generated classes contain {@code /0x}, {@code +0x}, or {@code $$Lambda}.
     */
    static boolean isAppClass(String className) {
        if (className.matches("\\[+[BCDFIJSZ]")) {
            return false; // primitive array
        }

        String base = stripArrayPrefix(className);

        if (base.startsWith("java/") || base.startsWith("javax/") || base.startsWith("sun/")
                || base.startsWith("jdk/") || base.startsWith("com/sun/")
                || base.startsWith("org/xml/") || base.startsWith("org/w3c/")
                || base.startsWith("org/ietf/") || base.startsWith("org/jcp/")) {
            return false;
        }

        if (className.contains("/0x") || className.contains("+0x") || className.contains("$$Lambda")) {
            return false;
        }

        return true;
    }

    /** Strips leading array brackets and the {@code L...;} object-type wrapper, returning the base class name. */
    static String stripArrayPrefix(String className) {
        if (className.startsWith("[")) {
            return className.replaceFirst("^\\[+L", "").replaceFirst(";$", "");
        }
        return className;
    }
}
