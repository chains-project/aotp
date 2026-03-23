## Overview

This document describes how HotSpot's C++ `ConstantPool` structure
(from [`src/hotspot/share/oops/constantPool.hpp`](https://github.com/openjdk/jdk/blob/jdk-27%2B7/src/hotspot/share/oops/constantPool.hpp))
is parsed into the Java-side abstractions `ConstantPool` and `ConstantPoolEntry`
under `io.github.chains_project.aotp.oops.cp`.

Unless noted otherwise, all HotSpot pointer types (`Symbol*`, `Array*`, etc.)
are 64-bit absolute addresses resolved via `absoluteAddress - requestedBaseAddress`
to obtain the file offset, following the same convention as `InstanceClass` parsing.

The layout is based on
[OpenJDK 27+7](https://github.com/openjdk/jdk/blob/jdk-27%2B7/src/hotspot/share/oops/constantPool.hpp).

---

## `ConstantPool` struct → `ConstantPool` record

Source: `src/hotspot/share/oops/constantPool.hpp`, `class ConstantPool : public Metadata`

Java type: `io.github.chains_project.aotp.oops.cp.ConstantPool`

### Sizing

```cpp
// Words (1 word = 8 bytes on 64-bit)
static int header_size() { return align_up((int)sizeof(ConstantPool), wordSize) / wordSize; }
static int size(int length) { return align_metadata_size(header_size() + length); }
```

`sizeof(ConstantPool)` = **72 bytes** (9 words).
The 4 bytes of trailing struct padding after `_saved` bring the total from 68 to
72, satisfying the 8-byte alignment requirement of the largest member (pointer fields).

Total on-disk size of a ConstantPool with `_length` entries:
```
total_bytes = (9 + _length) * 8 = 72 + _length * 8
```

This can be verified against the `aot.map` file:
`_length = (map_size - 72) / 8`

### Field layout

All offsets are in bytes from the start of the `ConstantPool` object.

| Offset | C++ field (type)                          | Size | Notes / Java model                                    |
|--------|-------------------------------------------|------|-------------------------------------------------------|
| 0      | C++ vtable pointer (implicit, from `Metadata`) | 8 | Identifies the concrete type (ConstantPool vtable kind 0). Parsed/skipped; not exposed. |
| 8      | `Array<u1>* _tags`                        | 8    | Pointer to the tag array in the RO region. Read to decode each slot. |
| 16     | `ConstantPoolCache* _cache`               | 8    | Runtime cache; skipped.                               |
| 24     | `InstanceKlass* _pool_holder`             | 8    | Back-pointer to the owning class; skipped.            |
| 32     | `Array<u2>* _operands`                    | 8    | Bootstrap method operands for `InvokeDynamic`/`Dynamic`; skipped. |
| 40     | `Array<Klass*>* _resolved_klasses`        | 8    | Resolved klass array indexed by CPKlassSlot; skipped. |
| 48     | `u2 _major_version`                       | 2    | Class file major version; skipped.                    |
| 50     | `u2 _minor_version`                       | 2    | Class file minor version; skipped.                    |
| 52     | `u2 _generic_signature_index`             | 2    | CP index of generic signature Utf8, or 0; skipped.    |
| 54     | `u2 _source_file_name_index`              | 2    | CP index of source file name Utf8, or 0; skipped.     |
| 56     | `u2 _flags`                               | 2    | Internal flags (`_has_preresolution`, `_is_shared`, …); skipped. |
| 58     | *(padding)*                               | 2    | Compiler-inserted padding to 4-byte-align `_length`.  |
| 60     | `int _length`                             | 4    | Number of CP entries (indices 0 … `_length-1`). Index 0 is always unused/invalid. |
| 64     | `union { int _resolved_reference_length; int _version; } _saved` | 4 | Union for CDS/redefinition; skipped. |
| 68     | *(trailing struct padding)*               | 4    | C++ struct padding to reach `sizeof(ConstantPool) = 72`. |
| **72** | **inline CP data** `intptr_t[_length]`    | `_length × 8` | Each slot is one `intptr_t` (8 bytes). The accessor used — `obj_at_addr`, `int_at_addr`, `long_at_addr` — depends on the tag. |

---

## `Array<u1>` tag array → `byte[]`

The pointer at offset 8 (`_tags`) points to an `Array<u1>` in the RO region.

| Offset | C++ field         | Size          | Notes                                     |
|--------|-------------------|---------------|-------------------------------------------|
| 0      | `int _length`     | 4             | Must equal `ConstantPool::_length`.       |
| 4      | `u1 _data[_length]` | `_length`   | One tag byte per CP index; index 0 = `JVM_CONSTANT_Invalid` (0). |

File offset formula:
```
tagsFileOffset = _tags - requestedBaseAddress
```

---

## CP slot encoding by tag

Each slot in the inline data array is 8 bytes wide (`intptr_t`). The used portion
depends on the tag as follows (derived from the `*_at_put` methods in
`constantPool.hpp`).

| Tag (numeric) | Tag name                  | Accessor         | Slot encoding                                                                   |
|---------------|---------------------------|------------------|---------------------------------------------------------------------------------|
| 0             | `Invalid`                 | —                | Unused; index 0 always has this tag.                                            |
| 1             | `Utf8`                    | `obj_at_addr`    | Full 8 bytes = `Symbol*`. Dereference to read the symbol body.                  |
| 3             | `Integer`                 | `int_at_addr`    | Low 4 bytes = `jint` value (little-endian).                                     |
| 4             | `Float`                   | `float_at_addr`  | Low 4 bytes = `jfloat` (IEEE 754 single, little-endian).                        |
| 5             | `Long`                    | `long_at_addr`   | Full 8 bytes = `jlong` value. **Occupies two consecutive CP indices.**           |
| 6             | `Double`                  | `double_at_addr` | Full 8 bytes = `jdouble` (IEEE 754 double). **Occupies two consecutive CP indices.** |
| 7             | `Class`                   | `int_at_addr`    | Low 4 bytes packed via `build_int_from_shorts(resolved_klass_index, name_index)`: bits 0–15 = `resolved_klass_index` into `_resolved_klasses`, bits 16–31 = `name_index` (CP index of the Utf8 class name). |
| 8             | `String`                  | `obj_at_addr`    | Full 8 bytes = `Symbol*` (unresolved string). Lowest bit may be set as a pseudo-string marker and must be masked out before dereferencing: `symbolAddr = slot & ~1L`. |
| 9             | `Fieldref`                | `int_at_addr`    | Low 4 bytes: bits 0–15 = `class_index`, bits 16–31 = `name_and_type_index`.    |
| 10            | `Methodref`               | `int_at_addr`    | Same encoding as `Fieldref`.                                                    |
| 11            | `InterfaceMethodref`      | `int_at_addr`    | Same encoding as `Fieldref`.                                                    |
| 12            | `NameAndType`             | `int_at_addr`    | Low 4 bytes: bits 0–15 = `name_index`, bits 16–31 = `signature_index`.         |
| 15            | `MethodHandle`            | `int_at_addr`    | Low 4 bytes: bits 0–15 = `ref_kind` (1–9), bits 16–31 = `ref_index`.           |
| 16            | `MethodType`              | `int_at_addr`    | Low 4 bytes = `ref_index` (CP index of the descriptor `Utf8`).                 |
| 17            | `Dynamic`                 | `int_at_addr`    | Low 4 bytes: bits 0–15 = `bsms_attribute_index`, bits 16–31 = `name_and_type_index`. |
| 18            | `InvokeDynamic`           | `int_at_addr`    | Same encoding as `Dynamic`.                                                     |
| 100           | `UnresolvedClass`         | `obj_at_addr`    | Full 8 bytes = `Symbol*` (class name before resolution).                        |
| 101           | `ClassIndex`              | `int_at_addr`    | Temporary tag during class parsing; low 4 bytes = string_index.                 |
| 102           | `StringIndex`             | `int_at_addr`    | Temporary tag during class parsing; low 4 bytes = string_index.                 |
| 103           | `UnresolvedClassInError`  | `obj_at_addr`    | `Symbol*`; resolution failed.                                                   |
| 104           | `MethodHandleInError`     | —                | Error state; slot value unused.                                                 |
| 105           | `MethodTypeInError`       | —                | Error state; slot value unused.                                                 |

---

## `Symbol` layout

`Utf8`, `String`, `UnresolvedClass`, and `UnresolvedClassInError` slots store a
`Symbol*`. The body is read from the file at `symbolFileOffset = symbolAddr - requestedBaseAddress`.

| Offset | C++ field                   | Size       | Notes                                      |
|--------|-----------------------------|------------|--------------------------------------------|
| 0      | `unsigned int _hash_and_refcount` | 4    | Packed hash + refcount; skipped.           |
| 4      | `u2 _length`                | 2          | Byte length of the UTF-8 body.             |
| 6      | `char _body[_length]`       | `_length`  | Modified UTF-8 bytes (same as `CONSTANT_Utf8_info` body in the class file). |

---

## Java model

`io.github.chains_project.aotp.oops.cp.ConstantPool`
```java
record ConstantPool(String className, List<ConstantPoolEntry> entries)
```

- `className` — taken from the owning `InstanceClass.getName()` (HotSpot `/`-separated binary name).
- `entries` — one entry per valid CP index (1 … `_length - 1`), in order. The pair of indices for `Long`/`Double` produces only one entry (the second index is silently skipped, consistent with the JVM spec).

`io.github.chains_project.aotp.oops.cp.ConstantPoolEntry`
```java
record ConstantPoolEntry(int index, int tag, String tagName, String value)
```

| Field     | Description                                                              |
|-----------|--------------------------------------------------------------------------|
| `index`   | 1-based CP index.                                                        |
| `tag`     | Raw tag byte from `_tags`.                                               |
| `tagName` | Human-readable tag name (e.g. `"Utf8"`, `"Methodref"`).                 |
| `value`   | Decoded slot value as a string. For `Symbol*` entries the symbol body is read and returned as a UTF-8 string. For packed-int entries the constituent indices are shown as `"key=N ..."`. |