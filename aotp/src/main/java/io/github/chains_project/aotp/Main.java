package io.github.chains_project.aotp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import io.github.chains_project.aotp.integrity.ClassMismatch;
import io.github.chains_project.aotp.integrity.IntegrityChecker;
import io.github.chains_project.aotp.integrity.IntegrityReport;
import io.github.chains_project.aotp.oops.cp.ConstantPool;
import io.github.chains_project.aotp.oops.cp.ConstantPoolEntry;
import io.github.chains_project.aotp.oops.cp.ConstantPoolHeader;
import io.github.chains_project.aotp.oops.klass.ClassEntry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "aotp", description = "Tool to give insight into AOTCache files.")
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the AOT cache file.")
    String filePath;

    @Option(names = "--header", description = "Print the file map header.")
    boolean header;

    @Option(names = "--print-class",
            paramLabel = "CLASS",
            description = "Pretty print the fields of the specified class.")
    String printClassName;

    @Option(names = "--list-classes",
            paramLabel = "FILTER",
            description = "List classes found in the RW region. Optionally filter with key:value pairs "
                        + "(e.g. aotClassFlags:has_aot_initialized_mirror,has_archived_enum_objs).",
            arity = "0..1",
            fallbackValue = "")
    String listClassesFilter;

    @Option(names = "--class-size",
            paramLabel = "CLASS",
            description = "Print the size of the specified class.",
            arity = "1..*")
    List<String> classSizeClassNames;

    @Option(names = "--list-constant-pools",
            paramLabel = "CLASS",
            description = "Print constant pool entries for all classes, or only for the specified class.",
            arity = "0..1",
            fallbackValue = "")
    String listCpFilter;

    @Option(names = "--print-constant-pool",
            paramLabel = "CLASS",
            description = "Print constant pool entries for the specified class.")
    String printConstantPoolClassName;

    @Option(names = "--check-integrity",
            paramLabel = "JAR",
            description = "Verify the AOT cache was built from the given JAR files.",
            arity = "1..*")
    List<String> integrityJarPaths;

    @Override
    public Integer call() {
        boolean listClasses = listClassesFilter != null;
        boolean listCp = listCpFilter != null;
        boolean printSingleCp = printConstantPoolClassName != null;
        boolean checkIntegrity = integrityJarPaths != null && !integrityJarPaths.isEmpty();
        boolean anyFlag = header || listClasses
                || (classSizeClassNames != null && !classSizeClassNames.isEmpty())
                || printClassName != null || listCp || printSingleCp || checkIntegrity;
        if (!anyFlag) {
            header = true;
            listClasses = true;
            listClassesFilter = "";
        }

        try {
            if (header) {
                AotpApi.printHeader(filePath, System.out);
                if (header && !listClasses
                        && (classSizeClassNames == null || classSizeClassNames.isEmpty())
                        && printClassName == null
                        && !listCp
                        && !printSingleCp) {
                    return 0;
                }
            }

            if (listClasses) {
                List<ClassEntry> classes = AotpApi.listClasses(filePath);
                if (listClassesFilter != null && !listClassesFilter.isEmpty()) {
                    classes = applyFilter(classes, listClassesFilter);
                }
                for (ClassEntry entry : classes) {
                    System.out.println(entry.getName());
                }
            }

            if (classSizeClassNames != null && !classSizeClassNames.isEmpty()) {
                Map<ClassEntry, Integer> sizes = AotpApi.getClassSizes(filePath, classSizeClassNames);
                for (Map.Entry<ClassEntry, Integer> entry : sizes.entrySet()) {
                    System.out.println(entry.getKey().getName() + ": " + entry.getValue());
                }
            }

            if (printClassName != null) {
                if (!AotpApi.printClass(filePath, printClassName, System.out)) {
                    System.err.println("Class not found: " + printClassName);
                    return 1;
                }
            }

            if (listCp) {
                List<ConstantPool> cps = listCpFilter.isEmpty()
                        ? AotpApi.listConstantPools(filePath)
                        : AotpApi.listConstantPools(filePath, listCpFilter);
                printConstantPools(cps);
            }

            if (printSingleCp) {
                List<ConstantPool> cps = AotpApi.listConstantPools(filePath, printConstantPoolClassName);
                if (cps.isEmpty()) {
                    System.err.println("Class not found: " + printConstantPoolClassName);
                    return 1;
                }
                printConstantPools(cps);
            }

            if (checkIntegrity) {
                List<Path> jarPaths = integrityJarPaths.stream().map(Path::of).toList();
                IntegrityReport report = IntegrityChecker.check(jarPaths, filePath);
                printIntegrityReport(report);
            }

            return 0;
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Apply a filter string of the form {@code field:value1,value2} to a list of classes.
     * Currently supported fields:
     * <ul>
     *   <li>{@code aotClassFlags} — filter by flag names (e.g. {@code has_aot_initialized_mirror})</li>
     * </ul>
     */
    private static List<ClassEntry> applyFilter(List<ClassEntry> classes, String filterExpr) {
        int colonIdx = filterExpr.indexOf(':');
        if (colonIdx < 0) {
            System.err.println("Invalid filter syntax. Expected field:value (e.g. aotClassFlags:has_aot_initialized_mirror)");
            return classes;
        }
        String field = filterExpr.substring(0, colonIdx);
        String[] values = filterExpr.substring(colonIdx + 1).split(",");

        if ("aotClassFlags".equals(field)) {
            return classes.stream().filter(entry -> {
                String decoded = ClassEntry.formatAotClassFlags(entry.aotClassFlags);
                for (String v : values) {
                    if (!decoded.contains(v.trim())) {
                        return false;
                    }
                }
                return true;
            }).toList();
        }

        System.err.println("Unknown filter field: " + field + ". Supported: aotClassFlags");
        return classes;
    }

    private static void printConstantPools(List<ConstantPool> cps) {
        // Each `cp` is the entire constant pool structure of a class.
        for (ConstantPool cp : cps) {
            System.out.println("=== " + cp.className() + " ===");
            ConstantPoolHeader header = cp.header();
            if (header != null) {
                System.out.printf("  - tags:                  0x%x%n", header.tagsPointer());
                System.out.printf("  - cache:                 0x%x%n", header.cachePointer());
                System.out.printf("  - pool_holder:           0x%x%n", header.poolHolderPointer());
                System.out.printf("  - operands:              0x%x%n", header.operandsPointer());
                System.out.printf("  - resolved_klasses:      0x%x%n", header.resolvedKlassesPointer());
                System.out.printf("  - major_version:         %d%n", header.majorVersion());
                System.out.printf("  - minor_version:         %d%n", header.minorVersion());
                System.out.printf("  - generic_sig_index:     %d%n", header.genericSignatureIndex());
                System.out.printf("  - source_file_index:     %d%n", header.sourceFileNameIndex());
                System.out.printf("  - flags:                 0x%04x%n", header.flags());
                System.out.printf("  - length:                %d (constant_pool_count=%d, entries=%d)%n",
                        header.length(), header.length(), header.length() - 1);
                System.out.printf("  - saved:                 %d%n", header.saved());
            }
            System.out.printf("  [%4d] %-26s %s%n", 0, "Invalid", "Unused; index 0 always has this tag.");
            for (ConstantPoolEntry e : cp.entries()) {
                System.out.printf("  [%4d] %-26s %s%n", e.index(), e.tagName(), e.value());
            }
        }
    }

    private static void printIntegrityReport(IntegrityReport report) {
        System.out.printf("Matched:        %d class(es)%n", report.matchedClasses().size());
        System.out.printf("Stale in cache: %d class(es)%n", report.staleInCache().size());
        System.out.printf("Mismatched:     %d class(es)%n", report.mismatchedClasses().size());

        if (!report.staleInCache().isEmpty()) {
            System.out.println("\n--- Stale in cache (app classes in AOT but not in any JAR;"
                    + " java.*, javax.*, sun.*, jdk.*, com.sun.*, org.xml.*, org.w3c.*, org.ietf.*, org.jcp.* and hidden/lambda classes excluded) ---");
            for (String c : report.staleInCache()) {
                System.out.println("  " + c);
            }
        }

        if (!report.mismatchedClasses().isEmpty()) {
            System.out.println("\n--- Mismatched classes ---");
            for (ClassMismatch m : report.mismatchedClasses()) {
                System.out.println("  " + m.className()
                        + " (" + m.missingSymbols().size() + " injected, "
                        + m.addedSymbols().size() + " removed)");
                if (!m.missingSymbols().isEmpty()) {
                    System.out.println("    [injected into JAR, absent from AOT]");
                    for (String sym : m.missingSymbols()) {
                        System.out.println("      + " + sym);
                    }
                }
                if (!m.addedSymbols().isEmpty()) {
                    System.out.println("    [present in AOT, removed from JAR]");
                    for (String sym : m.addedSymbols()) {
                        System.out.println("      - " + sym);
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
