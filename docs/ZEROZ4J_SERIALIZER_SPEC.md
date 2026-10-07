# ZeroZ Stack serializer extension — request spec (GRANTED, closed)

> **Closed 2026-08-27. Everything asked for here shipped, and SwarmCoder now runs on it.**
>
> | Asked for | Landed in | Wire tag |
> |---|---|---|
> | `UUID` on the wire | 0.4.0 | `TAG_UUID` `0x0F`, canonical string form — the TeaVM fix below was taken |
> | `Instant` on the wire | 0.4.0 | `TAG_INSTANT` `0x10` |
> | `enum` on the wire | 0.4.0 | `TAG_ENUM` `0x11`, plus the annotation processor's type-aware path |
> | `Set` on the wire (§4) | 0.4.0 | `TAG_SET` `0x12`, read back as `LinkedHashSet` |
> | Per-module serializer registrar | 0.4.0 | `BinaryPackableRegistrar_<suffix>` |
>
> Sixteen further types came with them, none of which were asked for: `BigDecimal`, `BigInteger`,
> `LocalDate`, `LocalTime`, `LocalDateTime`, `Duration`, `Optional`, every primitive array, and
> EclipseStore's `Lazy`.
>
> **What SwarmCoder did with it.** The `BacklogTask` projection is deleted and the domain `Task` —
> three `Set` fields and all — travels on the wire itself, which is the direct-model pattern this
> document existed to unblock. The framework was also renamed: it is ZeroZ Stack
> (`zerozstack-*` artifacts), and `zeroz4j` is now only the family name. The text below is kept
> unedited as the record of what was asked and why.

**For:** the zeroz4j maintainers
**From:** the SwarmCoder reference project
**Goal:** let `@DataModel` classes carry `java.util.UUID`, `java.time.Instant`, and Java `enum`
fields **directly on the wire**, so applications can put their domain model on shared signals,
server events, LiveSync, and RMI without hand-written `String`/`long` DTO mirrors.

## Motivation

SwarmCoder is being used as a zeroz4j reference project. Its domain objects (persisted in
EclipseStore) use `UUID` ids, `Instant` timestamps, and enums (e.g. `Priority`,
`RequirementRelation`). Today `BinarySerializer.writeValue(...)` supports primitives, `String`,
`List`, `Map`, `byte[]`, and `@DataModel`/`BinaryPackable` objects — but **throws
`"Unsupported binary type tag"` for `UUID`, `Instant`, and enums**. The only workaround is a
parallel DTO class per model (`String` id, `long` millis, `String` enum) plus a mapping layer.
That duplication is exactly what direct-model propagation is meant to remove.

The APT (`RmiAnnotationProcessor`) already emits **generic** `BinarySerializer.writeValue(field)`
/ `(Type) BinarySerializer.readValue()` for every non-`String` field, so once the serializer
understands these three types, existing `@DataModel` generation picks them up with **no APT
change** for UUID/Instant (enum needs a small addition — see §3).

## 1. `UUID` — native tag

- New tag `TAG_UUID = 0x0F`.
- **Write:** tag, then `getMostSignificantBits()` (long), then `getLeastSignificantBits()` (long).
- **Read:** `new UUID(hi, lo)`.
- Add the `instanceof UUID` branch to **both** `writeValue` overloads (`ByteBuffer` and
  `GrowableBuffer`) and the `readValue` switch. 16 bytes, no reflection — TeaVM-safe.

## 2. `Instant` — native tag

- New tag `TAG_INSTANT = 0x10`.
- **Write:** tag, then `getEpochSecond()` (long), then `getNano()` (int).
- **Read:** `Instant.ofEpochSecond(seconds, nanos)`.
- Same three insertion points. No reflection — TeaVM-safe.

## 3. `enum` — TeaVM-safe, no reflection

Generic enum handling via `Class.forName` + `Enum.valueOf` is **not** acceptable: it breaks on
TeaVM (the client tier has no runtime reflection). Two options, not mutually exclusive:

**3a. Preferred for scalar fields — APT special-case.** In `RmiAnnotationProcessor`, when a
field's declared type is an enum, emit type-aware code (the concrete enum type is known at
codegen):
```java
// write
BinarySerializer.writeString(buffer, field == null ? null : field.name());
// read
String raw = BinarySerializer.readString(buffer);
this.field = raw == null ? null : EnumType.valueOf(raw);
```
This needs no serializer change and no reflection. It covers scalar enum fields (SwarmCoder's
case: `priority`, `status`, `relation`).

**3b. For enums inside generic containers (`List<MyEnum>`, `Map<..,MyEnum>`) — registry tag.**
`writeValue` can't see the element type, so add `TAG_ENUM = 0x11`:
- **Write:** tag, enum's declaring-class FQCN (string), `name()` (string).
- **Read:** look up a resolver registered in `BinaryRegistry` by FQCN and apply it to the name —
  no reflection. The APT registers each enum type it encounters, mirroring how it already
  registers `@DataModel` classes:
  ```java
  BinaryRegistry.registerEnum("com.example.Priority", Priority::valueOf);
  ```
  `BinaryRegistry` gains `registerEnum(String fqcn, java.util.function.Function<String,Enum<?>> resolver)`
  and `resolveEnum(String fqcn, String name)`.

SwarmCoder only needs **3a** today; **3b** is worth doing for completeness so any `@DataModel`
is fully general.

## Constraints & compatibility

- **New tags only** (`0x0F`–`0x11`); existing tag values and the wire format for current types
  are unchanged — old payloads still read.
- **Both tiers:** the changes live in `zeroz4j-shared-api` (`BinarySerializer`, `BinaryRegistry`)
  which compiles into server and TeaVM client, plus the APT. No reflection anywhere on the
  read path (TeaVM constraint).
- **Nulls:** `TAG_NULL` already covers null fields; the enum APT path null-checks as shown.

## Suggested tests (`SignalsTest` / a new `BinarySerializerTypesTest`)

- Round-trip a `@DataModel` with `UUID`, `Instant`, and an enum field — assert equality.
- Round-trip a `List<MyEnum>` and a `Map<UUID, Instant>` (exercises 3b + collections).
- A pre-extension payload (only old tags) still deserializes (backward compat).
- A shared-signal round-trip and a LiveSync round-trip of such a model over the real transport.

## Status in 0.3.0 (verified against SwarmCoder)

0.3.0 implements all three types per this spec on the JVM. Two findings from wiring it into the
TeaVM client:

- **`Instant` and enum: TeaVM-safe as shipped.** ✅
- **`UUID`: breaks the TeaVM client build.** The write path uses
  `uuid.getMostSignificantBits()`/`getLeastSignificantBits()` and the read path uses
  `new UUID(long, long)` — **TeaVM does not emulate these** (`"Method java.util.UUID.getMostSignificantBits()J was not found"`, `"new UUID(JJ)"`). Because
  `BinarySerializer.writeValue` is reachable through every generated serializer, this breaks
  **any** TeaVM client on 0.3.0, not only apps that use `UUID` directly. The zeroz4j examples all
  use `long` ids, so this path was never exercised through TeaVM.
  - **Fix (applied locally to verify, please upstream):** serialize `UUID` via its canonical
    string form — `writeString(buffer, val.toString())` on write, `UUID.fromString(readString(buffer))`
    on read. `toString()`/`fromString()` ARE emulated by TeaVM, so the same code links on both
    tiers. (Alternative: ship a TeaVM stub for `UUID.getMostSignificantBits()`/`new UUID(JJ)`.)
    With this change SwarmCoder's TeaVM client compiles against 0.3.0.

- **Multi-module `@DataModel` registrar collision.** The APT emits the registrar with a fixed
  FQCN `com.zeroz4j.generated.BinaryPackableRegistrar`. When **two** modules on one classpath have
  `@DataModel` types (SwarmCoder: domain objects in `sc-domain`, view aggregates in
  `sc-console-api`), both emit that same class — they collide and ServiceLoader loads only one, so
  the other module's types never register and fail to (de)serialize at runtime (the browser shows
  an empty graph, not an error). The examples never hit this (single model module).
  - **Fix (applied locally to verify, please upstream):** give the generated registrar a
    unique-per-module class name — `BinaryPackableRegistrar_<hash>` where the hash is derived from
    this module's model/enum FQCN set (`RmiAnnotationProcessor.generateRegistrar`). Each module's
    `META-INF/services/com.zeroz4j.api.BinaryRegistrar` then lists a distinct class, so
    ServiceLoader loads them all. Verified: SwarmCoder's domain `Brd` (with UUID/Instant/enum
    fields) round-trips over the wire and the Requirements graph renders + edits in the browser.

(Separately, the 0.3.0 change "purge the client Thread anti-pattern" is relevant to consumers:
`new Thread(...)` around RMI calls is now redundant and harmful — client handlers run on
suspendable green threads and background-thread signal/DOM writes don't repaint until the next UI
event. Consumers should call RMI directly.)

## Impact once shipped

SwarmCoder deletes its Console DTO layer (`*Dto` classes + the `Dtos` mapper) and puts the
domain model directly on shared signals / RMI / LiveSync — the intended reference pattern.

---

## 4. `Set` — the remaining gap (requested 2026-07-25)

`BinarySerializer.writeValue` handles `List` (`TAG_LIST`) and `Map` (`TAG_MAP`) but has no case for
`Set`, so a `@DataModel` carrying one fails to serialize. That is not an exotic shape: it is the
natural type for "a collection with no duplicates and no meaningful order", which is exactly what
id-reference fields are.

**Concretely, this is what blocks SwarmCoder's `Task` from going on the wire as the domain model.**
`Task` holds three sets — `writeSet` and `readSet` (`Set<String>`, the file paths a worker may touch,
where the no-duplicates property is load-bearing for the disjointness check) and `requirementIds`
(`Set<UUID>`). Because of this, the backlog panel ships a hand-written `BacklogTask` projection
instead of the domain object, which is a deviation from the direct-model pattern this whole exercise
is meant to demonstrate.

Requested: a `TAG_SET` symmetric with `TAG_LIST`.

```java
} else if (val instanceof Set) {
    buffer.put(TAG_SET);
    Set<?> set = (Set<?>) val;
    buffer.putInt(set.size());
    for (Object item : set) { writeValue(buffer, item, mapper); }
}
```

Read side materialises a `LinkedHashSet` (insertion-ordered, so a round trip is stable and tests can
assert on it; a plain `HashSet` would make the wire form non-deterministic and the APT-generated
serializers would produce diff-noisy output).

**Notes**
- The APT already emits `obj.setX((java.util.List) BinarySerializer.readValue(...))`-style casts, so
  it needs the matching `Set` branch in its type mapping too.
- `LinkedHashSet` is TeaVM-emulated, so the client half is fine.
- Deliberately NOT requested: `Optional`, arrays other than `byte[]`, or nested generics beyond what
  `List`/`Map`/`Set` already cover. Those have workarounds; `Set` does not.
