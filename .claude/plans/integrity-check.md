# Integrity Check: JAR Classpath vs AOT Cache

## Goal

Given a set of JAR files and a merged AOT cache, verify the AOT cache was built from those exact JARs (not a different version of any library). Detects stale caches after a dependency upgrade and tampered caches that contain injected symbols.

---

## What already works on the AOT side

`AotpApi.listConstantPools()` is implemented and returns `ConstantPool` records per class, each with typed `ConstantPoolEntry` items including tag=1 (`Utf8`) entries. It also accepts an optional class name filter via the overload `listConstantPools(filePath, className)`.

---

## Status: COMPLETE

All components are implemented, tested, and passing.

---

## Implemented architecture

### Dependency

`org.ow2.asm:asm` (version pinned in pom.xml) is used in `JarConstantPoolReader`.

### `JarConstantPoolReader` — `io.github.chains_project.aotp.jar`

- Accepts a `Path` to a JAR file (and an optional class name filter)
- Iterates `.class` entries, reads bytes, parses the raw classfile constant pool directly via a `DataInputStream` (not via ASM's high-level API)
- Handles all standard JVMS tags (Utf8, Integer, Float, Long, Double, Class, String, Fieldref, Methodref, InterfaceMethodref, NameAndType, MethodHandle, MethodType, Dynamic, InvokeDynamic, Module, Package)
- Returns `Map<String, ConstantPool>` — class name (slash-form) → `ConstantPool` domain object (reusing the same record used for AOT cache CPs)
- Skips `module-info` and `package-info` pseudo-classes

> **Note:** The plan originally called this class `JarClasspathReader` and placed it in the `integrity` package. It was renamed to `JarConstantPoolReader` and placed in `io.github.chains_project.aotp.jar` to reflect that it is a general-purpose constant pool reader, not integrity-specific.

### `IntegrityChecker` — `io.github.chains_project.aotp.integrity`

Entry point: `IntegrityChecker.check(List<Path> jarPaths, String aotCachePath) → IntegrityReport`

**Comparison strategy (per-class exact set diff, not global Utf8 union):**

> The plan originally proposed "JAR Utf8 ⊆ AOT-wide Utf8 union" to handle HotSpot's global Symbol* deduplication. The actual implementation uses **per-class exact set equality** instead. This works in practice because the test fixtures (hello.jar / hello.aot) show clean per-class matches. If deduplication causes false positives in real-world archives, revisit the comparison mode.

For each class present in both the JAR and the AOT cache:
- Compute `missingSymbols` = Utf8 strings in JAR class CP but absent from AOT class CP
- Compute `addedSymbols` = Utf8 strings in AOT class CP but absent from JAR class CP
- If both sets are empty → `matchedClasses`
- Otherwise → `mismatchedClasses` with a `ClassMismatch` record

For each class in the AOT cache that passes `isAppClass()`:
- Strip array prefix via `stripArrayPrefix()`
- If not present in the JAR map → `staleInCache`

> **Note:** Classes present in the JAR but absent from the AOT cache are silently ignored (no `missingFromCache` list). The plan included a `missingFromCache` field; it was dropped in the implementation.

**Helper methods:**
- `isAppClass(String)` — filters out JDK internals (`java/`, `javax/`, `sun/`, `jdk/`, `com/sun/`, `org/xml/`, `org/w3c/`, `org/ietf/`, `org/jcp/`), primitive arrays, and hidden/lambda classes (`/0x`, `+0x`, `$$Lambda`)
- `stripArrayPrefix(String)` — strips leading `[+L` and trailing `;` to get the base class name

### `IntegrityReport` — `io.github.chains_project.aotp.integrity`

```java
record IntegrityReport(
    List<String> staleInCache,        // in AOT cache but not in any JAR
    List<ClassMismatch> mismatchedClasses,
    List<String> matchedClasses)      // present in both with no symbol diff
```

> **Differs from plan:** `missingFromCache` was removed. `matchedClasses` was added.

### `ClassMismatch` — `io.github.chains_project.aotp.integrity`

```java
record ClassMismatch(
    String className,
    Set<String> missingSymbols,  // in JAR CP but absent from AOT cache CP
    Set<String> addedSymbols)    // in AOT cache CP but absent from JAR CP
implements Comparable<ClassMismatch>
```

> **Differs from plan:** `addedSymbols` was added (plan only had `missingSymbols`). `addedSymbols` is the key field for detecting tampered caches that inject new symbols.

---

## Tests — `IntegrityCheckerTest`

| Test | What it verifies |
|---|---|
| `cleanJarMatchesAot` | `Hello` class matches cleanly (1 matched, 0 mismatches, 0 stale) |
| `tamperedAotIsDetectedAsMismatch` | `hello-tampered.aot` has `exec`, `java/lang/Runtime`, `echo injected` in `addedSymbols` |
| `appClassIsStaleWhenJarIsAbsent` | With an empty JAR list, `Hello` appears in `staleInCache` |

Test resources: `src/test/resources/hello.jar`, `hello.aot`, `hello-tampered.aot`

---

## Comparison summary

| Concern | Tool |
|---|---|
| Read JAR CP | `JarConstantPoolReader` (raw DataInputStream parse, ASM for class name only) |
| Read AOT cache CP | `AotpApi.listConstantPools()` |
| Comparison unit | Utf8 entry values |
| Comparison mode | Per-class exact set diff (not global union) |
| Detects missing symbols | `missingSymbols` field on `ClassMismatch` |
| Detects injected symbols | `addedSymbols` field on `ClassMismatch` |
| Detects stale AOT entries | `staleInCache` list (app classes only) |