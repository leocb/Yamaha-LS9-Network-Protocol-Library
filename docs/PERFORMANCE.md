# Performance work — ls9-java

This documents the library-side performance changes and the measurements that
drove them. The app/client report is
[`../../ls9-remote-android/docs/PERFORMANCE.md`](../../ls9-remote-android/docs/PERFORMANCE.md).

## Method

* Microbenchmarks compiled from the current sources, JDK 25, one thread,
  allocation from `com.sun.management.ThreadMXBean`.
* A synthetic but layout-accurate 17,548-byte scene image (20,684 fields).
* The app's web bridge against the virtual LS9 (`127.0.0.1:12300`), with JFR
  and `jstat` on the JVM process.
* `./test.sh` after each change; final **649 checks passed**.

Desktop numbers; Android ART will be slower. The relative reductions and
removed work are the useful part.

## Hot-path benchmark

Scene layout: 20,684 fields, 2 wire packets.

| Operation | Before | After |
|---|---:|---:|
| Full decode (old eager graph) | 1.399 ms, 5,751,424 B | — |
| Decode + one point `get` | (old decode) | **2.45 µs, 376 B** |
| Decode + `forEachParameter` | (old decode) | **95 µs, 165,416 B** |
| Decode + materialise `parameters()` | — | 382 µs, 1,917,337 B |
| `BulkScene.from` header view | 801 ns, 17,592 B | **86 ns, 24 B** |
| `Ls9BulkEntry.text` (name) | 25,583 ns | **422 ns** |
| `Ls9BulkData.get` ×64 | 1,475 ns, 4,096 B | 2,222 ns, 3,128 B |
| `BulkDumpAssembler` whole scene | 35,566 ns, 119,768 B | 22,776 ns, 119,768 B |
| `ParameterAddress.of` + hashCode | 11 ns, 32 B | **2 ns, 0 B** |

The old full decode built two 20,684-entry `LinkedHashMap`s, a 20,684-entry
`Value` list, an `Optional`/registry lookup per field and cloned the image
several times. JFR on the live app showed **99.32% of steady-state allocation
was `LinkedHashMap$Entry` from `Ls9BulkEntry` via the 1-second current-data
poll**.

## Changes

### 1. Cheap change detection

`BulkDump.contentEquals` compares the owned data arrays directly (no clone).
`Ls9Session.pollForConsoleChanges` keeps the last dump and returns before
decoding when the image is unchanged. That removes the 1.4 ms decode,
5.75 MB allocation and ~40,000 value-map writes per idle second.

When the image has changed, `Ls9BulkDecoder.diff` walks the layout fields and
emits only changed values; names are compared through the existing name path.
This keeps the safety net lossless while avoiding a full object graph for a
single missed fader.

### 2. Lazy/indexed bulk decoding

`Ls9BulkEntry` no longer materialises maps/lists/registry definitions in its
constructor. `parameters()` and `values()` are lazy; `get`, `getOrDefault` and
`text` use a per-layout address index (`Layout.fieldAt`, packed 14-bit key)
so name reads are O(1) instead of a 20k scan. `Ls9BulkData` delegates to the
entry and does not copy the map; `forEachParameter` iterates fields without
allocating `ParameterAddress`/`Long` for unchanged values. `BulkDump` exposes
an internal read-only data view so `BulkScene` and the decoder no longer clone
the 17.5 KB image for a header read. Public accessors still clone defensively.

`parameters()` materialisation remains available for external API users and
costs ~1.9 MB, but the app never calls it.

### 3. Cold resource loading

The generated layout CSV is parsed line-by-line into `Field` records instead
of `Csv.parse` → 45k row lists + ~850k temporary strings. Layout load dropped
from 111–124 ms to 59–64 ms on this host. The parameter registry, value
tables, fader table and meter table use streaming row parsers; the fader table
is lazy (no longer constructed by `Ls9Console`'s constructor) and the parameter
registry is warmed on a daemon thread after connect, so the first negative EQ
change cannot stall the reader thread for ~80 ms.

### 4. Transport

* Reverse connections are handled by a bounded daemon pool instead of a new
  `Thread` per meter frame. JFR thread starts went from **499/12 s to 1/12 s**.
* Reverse sockets no longer allocate 8 KB `BufferedInputStream` and
  `BufferedOutputStream` wrappers for a connection that usually carries one
  meter frame; they read/write the socket directly.
* The reverse path decodes an EtherMIDI packet once; the old
  `isMeterPacket`/`handlePacket` pair decoded and copied the payload twice.
* `MidiTransport.sendSysexBatch` writes a whole bulk block and flushes once
  instead of flushing per packet. `Ls9Console.sendBulkDump` uses it, which
  matters for backup restore and library upload.

### 5. Allocation clean-ups

* `ParameterAddress.hashCode` is a manual 31-hash; `Objects.hash` boxed four
  ints and allocated a varargs array per lookup.
* Registry lookups for negative parameters are warmed asynchronously instead
  of on the reader thread.
* `Ls9BulkData.create` selects the layout once and no longer copies the entry
  map when no extra parameters are supplied.
* `Ls9Console.faderTable()` is lazy.
* `Ls9SnapshotIO.save` writes each block through `BulkDump.writeDataTo` instead
  of cloning every 17 KB image first.
* `Ls9BulkLayout.warmUpAsync()` parses the 2.8 MB descriptor resource while
  the client is on its connect screen; connect improved from 143 ms to 87–94 ms on the test
  bridge.

### 6. Session/client delivery (library-adjacent)

The JVM web bridge's steady-state allocation fell from ~16–25 MB/s with a
young GC every 3–6 s to ~1 MB/s with no GC in a 7 s sample. The remaining
changes are in `Ls9Session` (app repo): batched meter/parameter events, manual
batch JSON, result-envelope concatenation, separate bulk executor, poll
backoff, 2 s outport timeout with retry backoff, and foreground pause.

## Decisions

* **Keep the public map/list APIs.** External users still get the complete
  `parameters()`/`values()` views; they are materialised on demand. The app
  uses the primitive iteration path.
* **Use first-wins address indexing.** The existing layouts have no duplicate
  published addresses (verified for the 20,684-field scene layout); `text()`
  previously returned the first match and the map the last. With no duplicates
  both semantics agree. Future generated layouts should preserve that invariant
  or the index must record first/last separately.
* **Do not change the wire format, addresses, checksums or bulk ordering.**
  `./test.sh` covers the protocol; the optimisations only change when and how
  objects are built.
* **Diff only same-module/same-number dumps.** Unknown layouts fall back to a
  full decode, preserving compatibility with setup/library blocks.
* **Byte-compare entire images.** The current-scene image includes the scene
  number and all scene fields; identical bytes mean no scene-data change. The
  poll still runs as a safety net, with backoff.

## Verification

```bash
cd ls9-java
./test.sh
# OK: 649 checks passed
```

Live virtual console measurements (app bridge process):

| Metric | Before | After |
|---|---:|---:|
| Idle allocation | ~16–25 MB/s | ~0.3–1 MB/s |
| Young GC in idle sample | every 3–6 s | none in 7 s |
| Reverse thread starts | 499 / 12 s | 1 / 12 s |
| Scene poll blocked by bulk | 4.16 s | 1.6 ms |
| Outport wait | 5.0 s, blocking queue | 2.0 s, on bulk executor |
| Scene layout cold load | 111–124 ms | 59–64 ms |
| Full decode | 1.4 ms / 5.75 MB | 0.095 ms / 0.165 MB (iterate) |

The app report contains the end-to-end render traces and frame-gap results.
