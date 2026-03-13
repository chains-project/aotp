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

> [!WARNING]
> This may be wrong.
> See [#9](https://github.com/chains-project/aotp/issues/9).

```shell
java -jar aotp/target/aotp-0.0.1-SNAPSHOT.jar <cache.aot> --class-size "java/lang/String" "java/lang/Object"
```