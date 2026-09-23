# FlatBuffers-to-AdHoc — FlatBuffers schemas → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Translates [FlatBuffers](https://flatbuffers.dev/) schema files into [AdHoc](https://github.com/AdHoc-Protocol)
protocol-description `.cs` files, as a starting point for migrating a FlatBuffers-based protocol to AdHoc.
The project is self-contained: a single Java 17 class plus a local copy of the AdHoc emitter helpers.

## Links

| What                                   | Where                                                                                        |
|:---------------------------------------|:---------------------------------------------------------------------------------------------|
| FlatBuffers schema language (spec)     | https://flatbuffers.dev/schema/                                                              |
| FlatBuffers repository (`flatc`, tests) | https://github.com/google/flatbuffers                                                        |
| Sample schemas: google/flatbuffers     | https://github.com/google/flatbuffers/tree/master/samples , …/tree/master/tests , …/tree/master/reflection |
| Sample schemas: Apache Arrow IPC format | https://github.com/apache/arrow/tree/main/format (`Schema.fbs`, `Message.fbs`, `File.fbs`, `Tensor.fbs`, `SparseTensor.fbs`) |
| AdHoc protocol description format      | https://github.com/AdHoc-Protocol/AdHoc-protocol (README: *Protocol Description File Format*) |
| AdHocAgent (validation / code generation) | https://github.com/AdHoc-Protocol/AdHoc-protocol                                                     |

## Layout

| Path                                       | Contents                                                        |
|:-------------------------------------------|:----------------------------------------------------------------|
| `src/org/unirail/FlatBuffers2AdHoc.java`   | tokenizer, recursive-descent `.fbs` parser, AdHoc emitter        |
| `src/org/unirail/adhoc/AdHocWriter.java`   | local copy of the AdHoc emitter helpers (naming, docs, skeleton) |
| `src/org/unirail/adhoc/Json.java`          | local copy of the JSON reader (unused here, kept for uniformity) |
| `fetch-samples.sh`                         | downloads the sample schemas into `samples/` (upstream layout kept so `include` resolves) |
| `build.sh`                                 | compiles and converts `samples/` into `AdHoc/`                   |
| `validate.sh`                              | runs AdHocAgent in parse-only mode over `AdHoc/*.cs`             |
| `samples/`                                 | 13 upstream schemas (8 from google/flatbuffers, 5 from Apache Arrow) |
| `AdHoc/`                                   | generated descriptors + `*.branches.txt` dumps from validation   |

## Usage

```bash
./fetch-samples.sh                     # samples/flatbuffers/…, samples/arrow/…
./build.sh                             # javac + java … samples AdHoc
./validate.sh AdHoc                    # every file must print OK

# by hand:
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.FlatBuffers2AdHoc <file.fbs | folder> [output folder]
```

Every `.fbs` found under the input folder is a top-level schema: its `include`s are resolved (relative to the
including file, its ancestors, and finally any file under the input root with a matching path suffix, standing in
for `flatc -I`), merged, and one self-contained `<name>.cs` is written (`namespace org.flatbuffers`,
`interface <name>`). Output defaults to `<cwd>/AdHoc`.

## Before and after

Apache Arrow's IPC metadata schema, [`samples/arrow/Schema.fbs`](samples/arrow/Schema.fbs), 580 lines — the
largest real schema in `samples/`, and the fairest one to judge the output by because it exercises tables, enums,
unions, documentation and defaults at once. Result: [`AdHoc/Schema.cs`](AdHoc/Schema.cs).

```fbs
table Int {
  bitWidth: int; // restricted to 8, 16, 32, and 64 in v1
  is_signed: bool;
}

enum Precision:short {HALF, SINGLE, DOUBLE}

table FloatingPoint {
  precision: Precision;
}

/// Unicode with UTF-8 encoding
table Utf8 {
}

/// Opaque binary data
table Binary {
}
// ... 560 further lines
```

```csharp
public class Int {
    /**
    restricted to 8, 16, 32, and 64 in v1
    */
    // physics: an index or a small ordinal, floor at 0 -> consider [A], or [MinMax(a, b)] if you know a hard ceiling
    int bitWidth;
    bool is_signed;
}

/**
FlatBuffers base type: short
*/
public enum Precision {
    HALF = 0,
    SINGLE = 1,
    DOUBLE = 2,
}

public class FloatingPoint {
    org.apache.arrow.flatbuf.Precision precision;
}
// ... Utf8, Binary and the rest of the namespace, then the hosts and the connection
```

The trailing `//` comment became the field's documentation and the `///` comment the pack's; `Precision` kept its
declared order while its `short` base type moved into the doc, because AdHoc sizes an enum from its own values;
the field type is written as the full path `org.apache.arrow.flatbuf.Precision`, which is what lets Arrow's own
`Binary` and `Map` keep their names beside `org.unirail.Meta`'s; and `bitWidth` carries the one thing the schema
hints at but cannot state — see *Number encoding* below.

## Mapping

| FlatBuffers                               | AdHoc                                                                                   |
|:------------------------------------------|:----------------------------------------------------------------------------------------|
| `namespace a.b.c;`                        | nested non-transmittable `public struct a { public struct b { public struct c { … } } }` containers; types are referenced by full path, so equally named types of different namespaces coexist (`MyGame.Example.Monster` vs `MyGame.Example2.Monster`) |
| `table T { … }`                           | `public class T { … }` pack; non-scalar fields are optional by AdHoc's nature            |
| `struct S { … }`                          | `public class S { … }` pack, documented as fixed-layout; `(force_align: N)` → `[ForceAlign(N)]` |
| `enum E : t { … }`                        | `public enum E { … }` with explicit values; `: long` / `: ulong` only when values need it (the base type is documented in the doc comment, AdHoc sizes enums from their value range) |
| `enum E : t (bit_flags) { A = 0, B, C = 3 }` | `[Flags] enum E { A = 1, B = 2, C = 8 }` — bit indexes become bit values                |
| enum with < 2 values                      | `public struct E { public const int V = n; }` constants container (AdHoc rejects such enums); fields typed with it keep the primitive base type |
| `union U { A, alias: B, S: string }`      | `public class U { A A; B alias; string S; }` — one optional field per alternative, exactly one is expected to be set |
| scalars `bool byte ubyte short ushort int uint long ulong float double` (+ `int8`…`float64` aliases) | `bool sbyte byte short ushort int uint long ulong float double` |
| `string`                                  | `string`                                                                                |
| `[T]` vector                              | `T[,,]` (list, no `[D]`: the cap comes from the file's `_DefaultMaxLengthOf`); `[ubyte]` / `[uint8]` → `Binary[,,]` |
| `[T:N]` fixed array (structs)             | `[D(N)] T[]` — the only length a `.fbs` actually states                                  |
| field `= null` (optional scalar)          | `T?`                                                                                    |
| field `= value` (scalar / enum default)   | `[Default("value")]` (as written: `100`, `Blue`, `true`, `nan`, `+inf`)                   |
| `(id: N)` / `(deprecated)` / `(required)` / `(key)` | `[FieldId(N)]` / `[Deprecated]` + doc note / `[Required]` / `[SortKey]`           |
| any other attribute (`hash`, `nested_flatbuffer`, `flexbuffer`, `cpp_type`, user-declared …) | `[User("name", "value")]` (multiple allowed) |
| field typed with an empty table/struct    | `bool` presence flag (what AdHoc does anyway, done explicitly to avoid the agent's warning) |
| `rpc_service S { M(Req):Rsp (streaming: "server"); }` | `(L____________, Rsp) M(Req req);` inside the connection — Client calls, Server answers; the service name and `streaming`/`idempotent` metadata go to the method's doc |
| `root_type`, `file_identifier`, `file_extension` | `public struct FlatBufferFile { const string … }`                                   |
| `///` doc comments, `//` comments directly attached to a declaration | `/** … */` doc comments (XML-escaped)                            |

**Topology:** hosts `Client` and `Server`; `interface Connection : Connects<Client, Server>` holds the RPC methods
and one non-transitional `_____lr_____` state `Exchange` that lists every table not owned by an RPC method, so
either side may send it. Tables used as RPC request/response are deliberately kept out of `Exchange`: an
always-active state and an RPC actor claiming the same pack on the same host side would be an FSM ambiguity.

**Naming:** identifiers go through the agent's own keyword rule, so a field named `type` becomes `Type`; a
trailing underscore is dropped, so Arrow's `table Struct_` becomes `Struct`; a namespace container that would
collide with the project interface takes a numeric suffix (`optional_scalars` → `optional_scalars2`); and a
schema file named after an `org.unirail.Meta` type is written under a qualified name (`arrow/File.fbs` →
`arrow_File.cs`). Types that would shadow a Meta type are renamed only at the project's root scope — Arrow's
`Binary`, `Map` and `Duration` sit inside the `org.apache.arrow.flatbuf` container and are referenced by full
path, so they keep their own names.

## Lengths: one `_DefaultMaxLengthOf`, not an invented `[D(N)]` everywhere

FlatBuffers vectors and strings are unbounded. AdHoc caps collections at 255 items by default, which would
silently truncate real payloads, so every generated file opens with

```csharp
enum _DefaultMaxLengthOf { Arrays = 65_535, Maps = 65_535, Sets = 65_535, Strings = 65_535, }
```

and `[D(N)]` is emitted **only** for fixed arrays `[T:N]`, the one length the schema language really states
(11 occurrences across the 13 samples, all from `arrays_test.fbs`). Raising a ceiling the source never mentions
is honest; stamping a made-up per-field bound is not. Tighten individual fields by hand where you know the real
maximum — that is where AdHoc starts paying off.

## Number encoding: no attribute invented, but the question is never dropped

AdHoc's headline capability is declaring *where a number's values sit*, so the wire carries the distance from
that point instead of the magnitude. The converter emits no `[A]`/`[V]`/`[X]` — and the reason is narrower than
it first looks.

It is **not** that FlatBuffers stores scalars fixed-width. How the source framed its bytes says nothing about
what AdHoc should do: AdHoc lays out its own frame and is free to varint-encode a field FlatBuffers stored raw.
Confusing the input encoding with the output one would be the wrong argument.

The honest statement is about *claims*: **a `.fbs` carries no claim about where the values sit.** It offers no
`sint32`-versus-`fixed32` choice (which in protobuf really is the author saying "this clusters near zero"), no
declared range, no units, no invalid marker. So the physics of each number is yours to state, and stating it is
the single highest-value edit to make to a generated file.

A `.fbs` does, however, **hint**. Every FlatBuffers scalar has `0` as its default, explicit or implicit, so zero
is an ordinary value by the schema's own account; and names and `///` documentation usually say what the number
counts. Wherever such a hint exists and the field is an integer wider than one byte, the converter writes a
comment on the field naming the candidate and the reason — never the attribute:

```csharp
// physics: an element count, floor at 0, unbounded above -> consider [A]
int listSize;
// physics: a byte offset, floor at 0 but often huge -> [A] pays only while offsets stay under ~2 million
long offset;
// physics: identifier or digest, values spread across the whole range -> varint would enlarge every packet
long id;
```

90 such lines appear across the 13 samples: element counts and indexes (`[A]` candidates), byte offsets and
absolute times (flagged as likely losses at a zero base), hashes and identifiers (flagged as losses outright),
and a few fields whose prose documents non-negativity.

**The arithmetic, once.** Varint drops leading zero groups and spends one bit in eight on a continuation flag, so
everything turns on the distance from the base you declare. Against a fixed 4-byte field it wins while that
distance stays under roughly **2 000 000**, breaks even to 268 435 455, and **always loses beyond 268 435 455**.
That is why a Unix timestamp in seconds, a coordinate scaled by 1e7, a monotonic id past a quarter-billion and a
uniformly spread hash all cost *more* as varints at a zero base — and why moving the base under the values, or
using `[MinMax(a, b)]` for a hard range, is usually the real fix. A span narrower than one byte the generator
rejects outright; that field wants `[MinMax]`, which bit-packs below what varint can reach.

A `[Default("100")]` carried over from the schema is a hint about the *common* value, not a bound — but it is
often exactly the clue that tells you which attribute fits.

## Time: nothing here qualifies, and the reason is specific

FlatBuffers has no temporal type, so the only candidates are Arrow's `Timestamp`, `Date`, `Time`, `Duration`
and `Interval` tables in `Schema.fbs`. **None of them carries a time value**, and mapping them to AdHoc
`DateTime` would be a category error. They are logical-**type descriptors**: `Timestamp` holds a `unit` and a
`timezone` string, `Duration` holds only a `unit`. They describe how a *column* of an Arrow record batch is to
be read; the instants themselves live in Arrow's binary buffers, which never travel through this schema.

Even the resolution cannot be pinned at generation time: `unit: TimeUnit` is a runtime value, so there is no
constant to put in a `class X : Duration { precision; }` alias. (Contrast protobuf, where
`google.protobuf.Timestamp` *is* an instant and `Duration` *is* an elapsed span, both with fixed nanosecond
resolution — which is why the protobuf converter maps them to `DateTime` and a `Duration` alias.)

The remaining eight samples contain no time-valued field at all; the single time-ish name in the whole corpus is
Arrow's `timezone: string`, which is a zone name.

So each of the five Arrow tables is emitted as an ordinary pack **with a comment naming the AdHoc type its
described values would use** — `DateTime`, `DateTimeDef`, `TimeSpanDef`, `Duration` — so whoever models the
Arrow data itself is pointed straight at them:

```csharp
// Left as an ordinary pack on purpose: this table describes the logical time type of an Arrow column
// (its unit, its timezone) and never carries a time value — those live in Arrow's binary buffers,
// outside this schema. When you model the values themselves, a wall-clock instant is `DateTime`, or
// `class T : DateTimeDef { min; max; precision; }` when the epoch and resolution are pinned.
public class Timestamp { … }
```

## Validation

All 13 generated descriptors pass `./validate.sh AdHoc` (AdHocAgent `ADHOC_PARSE_ONLY=1`, no errors or warnings):

```
Message OK   Schema OK   SparseTensor OK   Tensor OK   arrays_test OK   arrow_File OK   include_test1 OK
include_test2 OK   monster OK   monster_test OK   optional_scalars OK   reflection OK   union_vector OK
```

`AdHoc/monster_test.branches.txt` shows the four `MonsterStorage` methods as separate Call/Return actors and the
seven remaining tables in `Exchange`.

Two agent behaviours shaped the output and are worth knowing: the agent's constant reader cannot narrow an
integer literal to `short`/`sbyte`, so enums are emitted without a narrow base type and single-value enums use
`const int`; and a class placed outside the project interface crashes the agent, so the custom attribute classes
live inside it and the exchange state lists its packs explicitly rather than using a recursive `@scope`.

## Limitations

- Collection caps are the file-wide 65_535 of `_DefaultMaxLengthOf`, not per-field truths; tighten the fields
  whose real maximum you know, which is also where `[MinMax]` bit-packing becomes available.
- No distribution attributes are emitted; hinted fields carry a `// physics:` comment instead — see *Number
  encoding* above. Acting on those comments is the single highest-value refinement of a generated file.
- A union is modelled as a pack of optional fields; nothing enforces "exactly one set" at the schema level.
- `nested_flatbuffer` / `flexbuffer` payloads stay opaque `Binary[,,]`; the attribute is preserved as `[User]`
  and a `DROPPED:` note is written into the field's doc comment naming what AdHoc would do instead.
- `rpc_service` streaming modes have no AdHoc RPC equivalent; they are recorded in the method's documentation.
- Field order and `(id: N)` slot order are kept as declared; FlatBuffers' vtable layout is not relevant to AdHoc.
- JSON objects embedded in a schema file are skipped.
