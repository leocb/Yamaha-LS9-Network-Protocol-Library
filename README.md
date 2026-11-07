# ls9-java

A dependency-free Java 17 library for controlling a **Yamaha LS9-16 / LS9-32**
digital mixing console over Ethernet.

It speaks the console's native Yamaha System Exclusive protocol and can carry it
over two transports:

1. **Direct Ethernet** (default): a clean-room implementation of Yamaha's
   `EtherMIDI` framing on TCP **12300**, reverse-engineered with REA from
   `nm200_mac.dmg` (Network-MIDI Driver 2.0.0, `EtherMIDIDriver-2.0.0f4`).
2. **OS MIDI**: `javax.sound.midi` through Yamaha's installed Network-MIDI
   Driver (`UNIT1 Port1`), for machines that already have the driver.

The library now covers the complete live Studio Manager surface: every
published parameter, all library/scene functions, bulk-dump synchronization,
level meters, MIDI/MMC and snapshot files. Server mode and hardware validation
are intentionally out of scope.

```java
try (Ls9Console ls9 = Ls9Console.connect("192.168.0.128")) {
    ls9.input(1).setFaderDb(0.0);     // INPUT 1 to 0 dB
    ls9.input(1).setOn(true);
    ls9.input(1).setPan(0.0);         // center

    ls9.input(1).setMixSendDb(1, -6.0); // INPUT 1 -> MIX 1 at -6 dB
    ls9.input(1).setMixSendOn(1, true);

    System.out.println(ls9.input(1).faderDb());
    ls9.scene().recall(12);
}
```

## Quick start

Requires JDK 17 or newer. No third-party runtime dependencies.

```bash
./build.sh
./test.sh          # 466 protocol/parameter/meter checks, including a fake LS9 over TCP

# compile and run the example
javac --release 17 -cp out -d out \
    examples/io/github/leocb/ls9/examples/Example.java
java -cp out io.github.leocb.ls9.examples.Example 192.168.0.128
```

With Maven:

```xml
<dependency>
  <groupId>io.github.leocb</groupId>
  <artifactId>ls9-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Console setup

On the LS9, open **SETUP → MISC SETUP**:

* Set a static IP address. The factory default for an LS9 is
  `192.168.0.128/24`.
* Open **MIDI SETUP** and turn on **PARAMETER CHANGE Rx** and **Tx**.
* Set **Rx CH / Tx CH** to the channel you pass as `deviceNumber`
  (MIDI channel 1 = `0`). The library defaults to `0`.
* If you want the console to push fader moves to your program, leave
  `PARAMETER CHANGE Tx` on.

The library does not change any console settings for you.

## Two transports

### Direct Ethernet (default)

```java
Ls9Console ls9 = Ls9Console.builder()
        .host("192.168.0.128")
        .tcpPort(12300)      // Yamaha EtherMIDI
        .portIndex(0)        // "Port1" is 0
        .deviceId(1)         // "Device Port No.": 1 for an LS9
        .deviceNumber(0)     // SysEx device number: MIDI ch 1
        .authMac("00:1A:2B:3C:4D:5E") // only if the console challenges
        .open();
```

If the console asks for MAC authentication and you did not supply a MAC, the
handshake fails with a clear message. You can copy the MAC from the console's
network page or from the Network-MIDI Driver preferences.

If the open handshake is not acknowledged, try `.portIndex(1)` and/or
`.modeBitmap(1)`. Those are the only two handshake fields whose defaults could
not be confirmed from the extracted driver (see `PROTOCOL.md`).

### Installed Yamaha Network-MIDI Driver

```java
JavaMidiTransport.listPorts().forEach(System.out::println);

try (Ls9Console ls9 = Ls9Console.builder()
        .transport(new JavaMidiTransport("UNIT1 Port1"))
        .open()) {
    ls9.input(1).setFaderDb(-3);
}
```

This path is useful to validate your console/MIDI settings and to compare
traffic with the direct implementation.

## High-level API

| Object | Methods |
|---|---|
| `Ls9Console` | `set`, `setSigned`, `get`, `sendSysex`, `sendShortMidi`, `addListener`, `input`, `stereoInput`, `mix`, `matrix`, `stereo`, `mono`, `scene`, `setMuteMaster` |
| `Ls9InputChannel` | `faderRaw/setFaderRaw`, `faderDb/setFaderDb`, `on/setOn`, `panRaw/setPanRaw`, `pan/setPan`, `mixSendRaw/setMixSendRaw`, `mixSendDb/setMixSendDb`, `mixSendOn/setMixSendOn`, `mixSendPre/setMixSendPre`, `mixSendPanRaw/setMixSendPanRaw`, `setMixSendPan`, `toStereo`, `toMono`, `phantom`, `haGainDb`, `attenuatorDb`, `name/setName` |
| `Ls9OutputChannel` | `faderRaw/setFaderRaw`, `faderDb/setFaderDb`, `on/setOn`, `balanceRaw/setBalanceRaw`, `balance/setBalance`, `attenuatorDb`, `name/setName` |
| `Ls9SceneControl` | `recall`, `store`, `store(scene, title)`, `setTitle`, `storeUndo`, `recallUndo`, `currentTitle`, `edit(scene, Ls9Edit)` |
| `Ls9Parameter` | Any generated parameter: `get/set`, `getRaw/setRaw`, `display`, `setDisplay`, `min`, `max`, `defaultValue` |
| `Ls9Library` | `store`, `recall`, `unknownStore`, `unknownRecall`, undo, copy/paste/clear/cut/insert/editUndo, attributes, titles, `exists`, `edit`, `collectionStore`, `moduleTrigger`, `channelPair` |
| `BulkDumpTransfer` | `currentData`, `scene`, `library`, `setup`, `downloadScenes`, `downloadLibrary`, `downloadSetup`, upload methods |
| `Ls9Sync` | `snapshot`, `fullSnapshot`, `upload`, `scenes`, `library`, `sceneInfo` |
| `Ls9MidiControl` | `programChange`, `controlChange`, `nrpn`, MMC stop/play/record/pause, reset, active sensing |
| `Ls9SnapshotIO` | `save`/`load` of a portable snapshot file |

### Raw parameter access

Every entry in Yamaha's official `ParamChangeList_LS9.xls` is available through
`ParameterAddress`:

```java
import io.github.leocb.ls9.sysex.ParameterAddress;

// kInputFader (0x0033), index kFader (0x0000), INPUT 5 (channel 4)
ParameterAddress a = ParameterAddress.of(0x0033, 0x0000, 4);
ls9.set(a, 823);          // 0 dB on the LS9 fader curve
long raw = ls9.get(a);
```

`Ls9ParameterRegistry` loads all 1,033 published entries:

```java
Ls9ParameterRegistry.get()
    .find("kInputFader", "kFader")
    .ifPresent(d -> System.out.println(d.template()));
```

### Every parameter, typed and generated

`Ls9Parameters` contains a generated `ParameterSpec` for every row in the
official list, grouped by element. Each is bound to a console and channel as an
`Ls9Parameter` with automatic display-table conversion:

```java
// Generated lookup
Ls9Parameter fader = ls9.parameter(Ls9Parameters.kInputFader.kFader, 4);
fader.set(823);
System.out.println(fader.getDisplay());       // "0.00dB"

// Any EQ/dynamics/patch/setup parameter works the same way
Ls9Parameter eq = ls9.parameter("kInputEQ", "kGain", 4);
System.out.println(eq.getDisplay());
eq.setDisplay("-3.0dB");
```

All 5,625 value/display pairs from the PRM TABLE sheets are included in
`Ls9ValueTableRegistry`, so enum and dB-style parameters can be read and written
by display name as well as raw number.

### Fader values

The LS9 fader is a 0..1023 parameter with a non-linear dB curve. The library
ships the official curve (`PRM TABLE #03`) and converts both ways:

```java
ls9.input(1).setFaderRaw(823);    // 0.00 dB
ls9.input(1).setFaderDb(-6.0);    // nearest raw step
double db = ls9.input(1).faderDb();
```

`raw 0` is `-Infinity`. Values are quantized to the console's 1024-step curve.

### Events

```java
ls9.addListener(new Ls9Listener() {
    @Override public void onParameterChange(Ls9ParameterEvent e) {
        System.out.println(e.address().hex() + " = " + e.value());
    }
    @Override public void onFunctionCall(Ls9SysexMessage m) {
        System.out.println(m.function() + " " + m.module() + " #" + m.number());
    }
});
```

## Scene memory

```java
ls9.scene().recall(12);
ls9.scene().store(13);
ls9.scene().store(14, "Choir alternate");
ls9.scene().recallUndo();
```

Scene titles are limited to 16 characters by the LS9. `store(scene, title)`
sends `LibStr__` and then four `LibAttrbt` title elements. Scene library calls
use channel 512 (`04 00`), matching Yamaha’s own AMX example.

## Synchronization and backup

```java
Ls9Snapshot snapshot = ls9.sync().snapshot();   // current + setup + scenes
Ls9SnapshotIO.save(snapshot, new File("show.ls9snap"));

Ls9Snapshot restored = Ls9SnapshotIO.load(new File("show.ls9snap"));
ls9.sync().upload(restored);
```

For a full backup including all library types:

```java
Ls9Snapshot full = ls9.sync().fullSnapshot();
```

Raw blocks are also available:

```java
BulkDump current = ls9.bulk().currentData();
BulkDump scene7  = ls9.bulk().scene(7);
BulkDump eqLib   = ls9.bulk().library(BulkDumpCodec.INPUT_EQ, 42);
byte[] payload   = current.data();          // decoded 8-bit block
```

## Library management

```java
ls9.library().recall(Ls9Sysex.MODULE_INPUT_EQ, 42, 0); // into INPUT 1
ls9.library().store(Ls9Sysex.MODULE_INPUT_EQ, 43, 0);
ls9.library().copy(Ls9Sysex.MODULE_INPUT_EQ, 42);
ls9.library().paste(Ls9Sysex.MODULE_INPUT_EQ, 44);
ls9.library().setSceneTitle(12, "Choir alternate");
Ls9LibraryRange range = ls9.library().exists(Ls9Sysex.MODULE_SCENE, 10);
```

## Level meters

```java
ls9.addListener(new Ls9Listener() {
    @Override public void onMeter(MeterFrame f) {
        if (f.group() == MeterGroup.INPUT) {
            double db = MeterScale.get().toDb(f.valueAt(0));
            System.out.println("INPUT 1: " + db + " dB");
        }
    }
});
ls9.subscribeMeters(MeterGroup.INPUT, MeterGroup.POINT_PRE_FADER, 0, 8);
// ...
ls9.stopMeters();
```

## MIDI and MMC

```java
ls9.midi().programChange(12);
ls9.midi().controlChange(7, 100);
ls9.midi().nrpn(0x0C32, 0x7F00);   // example: a stereo ON parameter
ls9.midi().mmcPlay();
ls9.midi().mmcStop();
```

## Coverage

All console-side Studio Manager operations are covered:

| Studio function | Library support |
|---|---|
| Every current-data parameter (channel strip, HA, EQ, dynamics, insert, sends, patch, rack, GEQ, effect, monitor, cue, MIDI setup, preferences, user keys, custom fader layer, …) | 1,033 generated `ParameterSpec`s + 5,625 display-table entries |
| Parameter readback | PARAMETER REQUEST |
| Parameter change notifications | listener (`onParameterChange`) |
| Scene store/recall/undo, titles, dates, protect, edit | `Ls9SceneControl`, `Ls9Library`, `Ls9Sync` |
| Library store/recall/undo, copy/paste/clear/cut/insert, titles, exist ranges | `Ls9Library` |
| Full synchronization / backup | `BulkDumpTransfer`, `Ls9Sync`, `Ls9SnapshotIO` |
| Bulk dump / request for every module | `BulkDumpCodec` |
| Level meters | `subscribeMeters`, `MeterFrame`, `MeterScale` |
| Program Change, Control Change, NRPN, MMC, reset, active sensing | `Ls9MidiControl` |
| Collection store, module/effect trigger, channel pair | `Ls9Library` |
| Snapshot save/load | `Ls9SnapshotIO` (portable format) |

Two deliberate boundaries:

* **Bulk blocks are transferred losslessly but not semantically decoded.**
  `BulkDump` gives you the complete raw block; `Ls9SnapshotIO` stores and
  restores it. To edit a stored scene or library, use the console workflow
  `scene().edit(n, ...)` / `library().edit(module, n, channel, ...)`
  (recall → live edits → store). Decoding every field of the packed memory
  image into individual parameters is not implemented; Yamaha has not
  published that packing.
* **Yamaha’s proprietary `.yse` file format is not read or written.**
  `Ls9SnapshotIO` is a documented, portable replacement for backup/restore.

Automatic MAC discovery is also not implemented; supply the console MAC if the
handshake challenges. Server mode and hardware validation are out of scope by
request.

## Bring-up checklist

Because no LS9 was attached during development, the direct transport was
validated against a byte-accurate fake console and against the disassembly of
Yamaha's driver. On a real console, check these in order:

1. TCP connect to port 12300 succeeds.
2. Handshake completes (no `auth` error). If it fails, capture the first 16
   bytes from the console and compare with `PROTOCOL.md`.
3. `ls9.input(1).setFaderDb(-6)` moves INPUT 1.
4. `ls9.input(1).faderRaw()` returns the value (requires PARAMETER CHANGE Rx/Tx).
5. `ls9.scene().recall(n)` recalls a scene.

If step 3 works but step 4 times out, the console is not sending parameter
changes: enable **PARAMETER CHANGE Tx** in MIDI SETUP.

If faders move but pans are one step off, see the pan encoding note in
`PROTOCOL.md`; use `setPanRaw` to calibrate.

## Android StageMix client

A faithful Android tablet/phone remote built on this library lives in
[`../ls9-stagemix-android`](../ls9-stagemix-android). It exposes the mixer,
layers, long faders, sends on faders, HA gain, PEQ/GEQ/dynamics editors, effect
rack, scenes, outports, utility and setup screens, and includes an offline demo
mode. See its README for build and connection instructions.

## Protocol documentation

See [`PROTOCOL.md`](PROTOCOL.md) for:

* the REA evidence and hashes,
* the exact EtherMIDI header layout and command values,
* the MAC challenge CRC,
* the LS9 SysEx format and function calls,
* the assumptions that still need hardware confirmation.

## Legal / interoperability note

This is an independent interoperability implementation. It was produced by
inspecting shipped software and published Yamaha protocol documents. No Yamaha
code, firmware or confidential material is included. "Yamaha" and "LS9" are
trademarks of Yamaha Corporation. Use at your own risk, especially on a live
show.
