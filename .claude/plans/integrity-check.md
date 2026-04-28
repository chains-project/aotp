# Integrity Check: JAR Classpath vs AOT Cache

## Goal

Given a set of JAR files and a merged AOT cache, verify the AOT cache was built from those exact JARs (not a different version of any library). Detects stale caches after a dependency upgrade.

---

## What already works on the AOT side

`AotpApi.listConstantPools()` is already implemented and returns `ConstantPool` records per class, each with typed `ConstantPoolEntry` items including tag=1 (`Utf8`) entries. Those Utf8 entries are the class's symbol table — method names, field names, type descriptors, string literals, class references.

---

## JAR side: use ASM, not javap

`javap` is too fragile for programmatic use (text parsing, subprocess). **ASM's `ClassReader`** is the right tool:

- `ClassReader.getItemCount()` + `ClassReader.readConst(cpIndex, buf)` lets you iterate the raw constant pool of a `.class` file, including all Utf8 entries
- This gives you the exact same semantic domain as what `aotp` reads from the AOT cache
- Dependency: `org.ow2.asm:asm:9.9.1`

---

## Comparison strategy

The comparison is **Utf8 set per class**: for every `InstanceClass` in the AOT cache, find the corresponding `.class` file in the JAR files and compare the sets of Utf8 constant pool strings.

**Important caveat**: HotSpot deduplicates `Symbol*` objects globally across the archive. A Utf8 entry from class A might physically live only once in the RO region and be referenced by class B's CP too — which means class B's own CP may appear to have fewer Utf8 entries than its `.class` file. To handle this, the comparison should be:

> **JAR's Utf8 set ⊆ AOT-wide Utf8 set** (union across all classes), not per-class equality.

---

## Why not opcode comparison?

The AOT cache stores HotSpot's internal `Method` representation with bytecodes rewritten by the bytecode verifier (e.g., `invokespecial` → `fast_invokespecial`). The raw JVM bytecode from the JAR is not directly comparable. Constant pool Utf8 comparison is semantically equivalent for version-detection — if a method signature changed, the descriptor Utf8 changes, which is caught here.

---

## Implementation plan

### 1. Dependency

Add to `aotp/pom.xml`:

```xml
<dependency>
  <groupId>org.ow2.asm</groupId>
  <artifactId>asm</artifactId>
  <version>9.9.1</version>
</dependency>
```

### 2. `JarClasspathReader`

New class in `io.github.chains_project.aotp.integrity`.

- Accepts a list of JAR file paths
- Iterates entries in each JAR, reads `.class` file bytes
- For each class: uses `ClassReader.getItemCount()` + `ClassReader.readConst(cpIndex, buf)` to collect all Utf8 CP entries
- Returns `Map<String, Set<String>>` — class name (slash-form) → set of Utf8 strings

### 3. `AotpApi.getGlobalUtf8Set(filePath)`

New method on `AotpApi`.

- Calls `listConstantPools(filePath)`
- Unions all tag=1 (`JVM_CONSTANT_Utf8`) entry values across all classes
- Returns `Set<String>`

### 4. `IntegrityChecker`

New class in `io.github.chains_project.aotp.integrity`.

- Inputs: list of JAR paths + AOT cache path
- Calls `JarClasspathReader` and `AotpApi.getGlobalUtf8Set()`
- For each class in the JAR:
  - If class is missing from AOT cache class list → report as **missing**
  - If any Utf8 from the JAR class is absent from the AOT-wide Utf8 set → report as **version mismatch**
- For each class in AOT cache not found in any JAR → report as **stale entry**
- Returns a structured `IntegrityReport` (or prints to a `PrintStream`)

---

## Report structure

```
IntegrityReport
  missingFromCache:   List<String>   // in JAR but not in AOT cache
  staleInCache:       List<String>   // in AOT cache but not in any JAR
  mismatchedClasses:  List<ClassMismatch>
    className:        String
    missingSymbols:   Set<String>    // in JAR CP but absent from AOT-wide symbols
```

---

## Comparison summary

| Concern | Tool |
|---|---|
| Read JAR CP | ASM `ClassReader.readConst()` |
| Read AOT cache CP | `AotpApi.listConstantPools()` (already works) |
| Comparison unit | Utf8 entry values |
| Comparison mode | JAR Utf8 per class ⊆ AOT-wide Utf8 union |
| False-positive risk | Low — only triggered if a method/field name or descriptor actually differs |
