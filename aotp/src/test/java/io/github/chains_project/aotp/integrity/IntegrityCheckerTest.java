package io.github.chains_project.aotp.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

class IntegrityCheckerTest {

    private static final Path RESOURCE_DIR = Paths.get("src/test/resources");
    private static final String AOT_PATH = RESOURCE_DIR.resolve("hello.aot").toString();
    private static final Path CLEAN_JAR = RESOURCE_DIR.resolve("hello.jar");
    private static final Path TAMPERED_AOT = RESOURCE_DIR.resolve("hello-tampered.aot");

    @Test
    void cleanJarMatchesAot() throws IOException {
        IntegrityReport report = IntegrityChecker.check(List.of(CLEAN_JAR), AOT_PATH);

        assertEquals(1, report.matchedClasses().size());
        assertTrue(report.matchedClasses().contains("Hello"));
        assertTrue(report.mismatchedClasses().isEmpty());
        assertTrue(report.staleInCache().isEmpty());
    }

    @Test
    void tamperedAotIsDetectedAsMismatch() throws IOException {
        IntegrityReport report = IntegrityChecker.check(List.of(CLEAN_JAR), TAMPERED_AOT.toString());

        assertEquals(1, report.mismatchedClasses().size());
        ClassMismatch mismatch = report.mismatchedClasses().get(0);
        assertEquals("Hello", mismatch.className());
        assertTrue(mismatch.addedSymbols().contains("exec"));
        assertTrue(mismatch.addedSymbols().contains("java/lang/Runtime"));
        assertTrue(mismatch.addedSymbols().contains("echo injected"));
        assertTrue(mismatch.missingSymbols().isEmpty());
    }

    @Test
    void appClassIsStaleWhenJarIsAbsent() throws IOException {
        IntegrityReport report = IntegrityChecker.check(List.of(), AOT_PATH);

        assertTrue(report.staleInCache().contains("Hello"));
        assertTrue(report.mismatchedClasses().isEmpty());
    }
}
