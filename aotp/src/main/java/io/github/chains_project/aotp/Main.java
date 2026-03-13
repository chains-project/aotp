package io.github.chains_project.aotp;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

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

    @Override
    public Integer call() {
        boolean listClasses = listClassesFilter != null;
        boolean anyFlag = header || listClasses
                || (classSizeClassNames != null && !classSizeClassNames.isEmpty())
                || printClassName != null;
        if (!anyFlag) {
            header = true;
            listClasses = true;
            listClassesFilter = "";
        }

        try {
            if (header) {
                AotpApi.printHeader(filePath, System.out);
                return 0;
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
