# aotp — AOT Cache Parser

A tool to inspect and analyze HotSpot AOTCache (`.aot`) files.

The format is adapted for JDK 27+7 but also works well for JDK 25.
Note that the value of `access_flags` is different and incorrect in JDK 25-created AOTCache files due to [8372098: Move AccessFlags to InstanceKlass](https://github.com/openjdk/jdk/commit/13e32bf1667a3be8492d1e4e3a273951202acd9c#diff-b4a457dba5fcd562206461941f548d043dae9c0033c94d6f50982a59cffd0457).

## Build

```shell
mvn package
```

## Usage

### Print file map header

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --header
```

### List instance classes in the AOTCache

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --list-classes
```

### List classes with a specific flag

For example, list classes that have had their `<clinit>` pre-executed:

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --list-classes=aotClassFlags:has_aot_initialized_mirror
```

Multiple flags can be required (all must match):

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --list-classes=aotClassFlags:has_aot_initialized_mirror,has_archived_enum_objs
```

### Print details of a class

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --print-class "java/lang/String"
```

### Print class sizes

> [!CAUTION]
> This may be wrong.
> See [#9](https://github.com/chains-project/aotp/issues/9).

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --class-size "java/lang/String" "java/lang/Object"
```

### Print constant pools

Print all constant pools:

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --list-constant-pools
```

Print one class's constant pool:

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --list-constant-pools="org/apache/pdfbox/tools/TextToPDF"
```

### Integrity check

Verify that an AOT cache was built from a specific set of JARs. Detects version mismatches (stale cache after a dependency upgrade) and tampered caches (injected symbols).

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --check-integrity lib1.jar lib2.jar ...
```

The report has three sections:

- **matched** — classes present in both the JARs and the AOT cache with identical constant pool symbols
- **mismatched** — classes whose constant pool differs; each entry shows `missingSymbols` (in JAR but absent from AOT) and `addedSymbols` (in AOT but absent from JAR, indicating injection or a version change)
- **stale** — application classes in the AOT cache with no corresponding class in the supplied JARs

The check uses constant pool Utf8 entries (method/field names, type descriptors, string literals) as the comparison unit.
If a method signature or class reference changed, the descriptor Utf8 changes and is caught here.

## Related Work

[Java AOT Cache Diagnostics Tool](https://github.com/Delawen/leyden-analyzer)
