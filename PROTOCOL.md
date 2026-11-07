# LS9 protocol notes

This document records how the library was derived. It separates **observed**
facts (bytes, offsets, strings, official documents) from **inferred** behaviour
that still needs confirmation on real hardware.

## Artifacts analysed

| Artifact | SHA-256 / identity | How |
|---|---|---|
| `Install LS9 Editor V2.2.1.pkg` | `935e319b8310b7e037014d8ae3ec00581edbe22a3fc904f09aa031c95d82312f` | REA `open_binary` / `inspect_artifact`, then `xar` + `gunzip` + `cpio` |
| `LS9 Editor` main Mach-O | `74ba4a90eee9ff9857e0ff2e720e0c0f2a45d0c75460a43b085154c99dde0b9f` (x86_64) | REA `inspect_macho` (12,655 exports), symbol/class inventory |
| `nm200_mac.dmg` | Apple HFS disk image | `hdiutil` |
| `Yamaha Network-MIDI Driver V2.0.0.pkg` | extracted from the DMG | `xar` + `gunzip` + `cpio` |
| `YamahaNetworkMIDI.plugin` | `2e24642baff50b9d459dbd7c9527c3fa788a8ee1b950d03542ed2c48c4c73744` (universal, arm64 slice at 0x2C000) | REA `inspect_macho`, `llvm-objdump`, `otool -Iv` |
| `ParamChangeList_LS9.xls` | official Yamaha download (`ls9_midiptcl101.zip`) | parsed with `xlrd`, converted to resources |
| `YamahaCA_AMX_ExampleV1.0_Programming.zip` | Yamaha AMX example programs | `Yamaha LS9 Comms.axs` text source used to confirm parameter, scene and meter framing |
| `YamahaCA_Crestron_ExampleV1.0_Programming.zip` | Yamaha Crestron example programs | inspected for additional bulk/scene examples (none found) |

The driver's own build string is:

```
@(#)PROGRAM:YamahaNetworkMIDI  PROJECT:EtherMIDIDriver-2.0.0f4
```

Its C++ RTTI names (visible in `__cstring`/typeinfo) describe the protocol
stack: `YEMDriver`, `YEMDevice`, `YEMRemDev`, `YEMRemDevMgr`, `YEMService`,
`YEMSocket`, `YEMConfig`, `MIDIParser`. The UI strings include
`com.yamaha.ethermidi.%p`, `com.yamaha.NetworkMIDIDriver`, `DeviceInfoList`,
`IPAddr4`, `PortNo`, `RxNo`, `RxDev`, `ModeBitmap`, `MACAddress`, `ListenPort`
and `ConnectTimeout`.

REA's `inspect_macho` was used for the load commands, imports and exports.
Deep disassembly/decompilation providers were not available in this environment,
so the arm64 slice was thinned with `lipo` and read with `llvm-objdump`; the
function boundaries and constants below are from that listing.

## 1. EtherMIDI transport

### 1.1 TCP port and timeouts

The driver's `YEMConfig` constructor writes:

```
mov w8, #0x300c        ; 12300
strh w8, [x0, #0x28]   ; ListenPort
mov w8, #0x1388        ; 5000
str  w8, [x0, #0x2c]   ; ConnectTimeout (ms)
```

at file offsets `0x10514` and `0x1051c` (and the same defaults at `0x10544`,
`0x10dfc`). Yamaha's DME-N/Network driver documentation also states that
DME/M7CL/LS9 traffic uses TCP **12300**.

The driver also creates a listening TCP socket on the configured
`ListenPort` (`CFSocketCreate` with `PF_INET`, `SOCK_STREAM`, `IPPROTO_TCP`,
`SO_REUSEADDR`, bind to `0.0.0.0:ListenPort`). The client path connects to the
device with `CFStreamCreatePairWithSocketToCFHost`.

### 1.2 Device information structure

The prefpane binary contains an Objective-C type encoding:

```
[256{YEMDevInfo="addr"I"port"S"rxdev"C"modebitmap"I"authdata"[6C]"devname"[33c]}]
```

So each configured device is:

| offset | type | meaning |
|---|---|---|
| 0 | `uint32` | IPv4 address |
| 4 | `uint16` | TCP port (12300) |
| 6 | `uint8` | `rxdev` / device number (1 for LS9) |
| 8 | `uint32` | mode bitmap |
| 12 | 6 bytes | `authdata` (the console MAC) |
| 18 | 33 bytes | display name, e.g. `UNIT1` |

The `YEMDevice` constructor copies this structure (see `0x5004`) and stores the
MIDI port index separately at object offset `0x30`. `YEMDevice::connect`
(`0x0b4bc`) copies it to `0x30` and uses:

* `[x20+6]` = `rxdev`
* `[x20+8]` = `modebitmap`
* `[x20+0xc]` = the six auth bytes

### 1.3 Packet framing

All integers are big-endian on the wire. The first 32-bit word is the total
packet length. The 16-byte header is:

| offset | size | meaning |
|---|---|---|
| 0 | 4 | total length, including header |
| 4 | 1 | command |
| 5 | 1 | zero |
| 6 | 1 | MIDI port index (`Port1` = 0) |
| 7 | 1 | device number (`rxdev`, 1 for LS9) |
| 8 | 4 | command-specific |
| 12 | 4 | command-specific |

Maximum packet length is **5000** (`mov w8, #0x1388` at `0x0af84`).

Commands observed in the driver:

| command | direction | meaning |
|---|---|---|
| `0x11` | both | 1-byte short MIDI message |
| `0x12` | both | 2-byte short MIDI message |
| `0x13` | both | 3-byte short MIDI message |
| `0x16` | both | System Exclusive |
| `0x20` | client → console | open / connect request |
| `0x21` | console → client | open acknowledgement |
| `0x23` | client → console | MAC challenge response |
| `0x24` | console → client | challenge accepted |
| `0x30` | both | close session |
| `0x40` | both | reset |

### 1.4 Short MIDI packets

`YEMDevice::buildShortMidi` at `0x06230`:

```
header[0..3] = 16
header[4]    = 0x10 + len       (len = 1..3)
header[6]    = port
header[7]    = rxdev
header[8..10]= MIDI bytes
header[12..15] = 0xFFFFFFFF
```

The receive path (`0x0af10..0x0af38`) reads `header[4]`, subtracts `0x10` to get
the length (1..3) and delivers `header[8..]` directly to the MIDI callback.

### 1.5 System Exclusive packets

Outgoing builder at `0x06340`:

```
header[0..3]  = 20 + n
header[4]     = 0x16
header[6]     = port
header[7]     = rxdev
header[8..11] = n
header[12..15]= 0xFFFFFFFF
header[16..19]= n
payload[0..]  = F0 ... F7          (n bytes)
```

The receive state machine (`0x0ad10`) reads the 16-byte header, computes
`remaining = total_length - 16`, reads that many bytes into a buffer, then
checks `buffer[4] == 0xF0` and `buffer[3 + n] == 0xF7`, where `n` is
`header[8..11]`. In other words, the four bytes at `buffer[0..3]` are the
duplicated SysEx length and the SysEx starts at `buffer[4]`. This matches the
20-byte outgoing "header".

The maximum SysEx length is therefore `5000 - 20 = 4980` bytes.

### 1.6 Open handshake

`YEMDevice::connect` at `0x0b4bc`:

```
header[0..3]  = 16
header[4]     = 0x20
header[6]     = port index (0 for Port1)
header[7]     = rxdev (1 for LS9)
header[8..9]  = 01 00
header[12..15]= modeBitmap | 0x20000000
```

Then it sends 16 bytes and reads a 16-byte reply. It requires `reply[4] ==
0x21`. It stores the big-endian 32-bit value from `reply[12..15]` as the
capability word. If bit 31 is set, it performs the MAC challenge:

```
seed  = reply[10] & 0xFF
crc   = YemAuth.checksum(seed, authdata)
header[0..3]  = 16
header[4]     = 0x23
header[6]     = port
header[7]     = rxdev
header[8..9]  = crc, big-endian
header[12..15]= 0
```

and then requires the next reply to be `0x24` (or another `0x21` in the
driver's code). The driver passes `reply[10]` as the first byte to the CRC
routine at `0x0a8dc`.

### 1.7 MAC challenge CRC

`0x0a8dc` is a standard MSB-first CRC-32 with polynomial `0x04C11DB7`
(`table[1] == 0x04C11DB7` at file offset `0x12448`). It is initialised as:

```
crc = table[(~seed) & 0xFF] ^ 0xFFFFFF00
```

then updated for each byte with:

```
idx  = ((crc >>> 24) ^ byte) & 0xFF
last = table[idx]
crc  = last ^ (crc << 8)
```

The bytes fed are:

```
authdata[0..5]                 (6 bytes, the MAC)
"COPYRIGHT (C) YAMAHA Corp."   (ASCII, 26 bytes)
```

and the 16-bit result is folded as:

```
low  = (last & 0xFF) << 8
high = table[(crc >>> 24) & 0xFF] & 0xFFFF
return (high ^ low) & 0xFFFF
```

This is implemented in `transport/YemAuth.java`.

### 1.8 Close and reset

`YEMDevice::disconnect` at `0x0b738` sends a 16-byte `0x30` packet with
`header[6]=port`, `header[7]=rxdev`, `header[8..11]=0`, `header[12..15]=
0xFFFFFFFF`, then sleeps 10 ms and closes the socket.

`0x0bcc0` builds a `0x40` reset packet with the same fields and
`header[12..15]=0xFFFFFFFF`.

### 1.9 Receive loop

The receive state machine (`0x0ae10`) runs a small state machine:

* state 0: read 16-byte header.
* command `0x16`: state 1; read `remaining = total_length - 16` bytes;
  deliver a SysEx if `buffer[4] == F0` and `buffer[3+n] == F7`.
* commands `0x11..0x13`: deliver 1..3 bytes from `header[8..]`.
* command `0x20`: reply with `0x21`, copying the request and masking the
  capability word with `0x03000020`.
* commands `0x30`/`0x40`: session close/reset, no application response.

## 2. LS9 System Exclusive protocol

The LS9 MIDI implementation is published in the LS9 Owner's Manual, appendix
"MIDI Data Format" (pages 260–267 of the PDF) and in the separate
`ParamChangeList_LS9.xls`. The library uses the standard current-data parameter
format:

```
F0 43 1n 3E 12
   01                       data category: current scene/setup
   ee ee                    element number
   ii ii                    index number
   cc cc                    channel number
   dd dd dd dd dd           value (five 7-bit bytes, big-endian)
F7
```

`n` is the device number (0 = MIDI channel 1). A PARAMETER REQUEST is identical
but uses sub-status `3n` and has no value bytes.

Example (from Yamaha's documentation and user reports): INPUT 56 fader to 0 dB:

```
F0 43 10 3E 12 01 00 33 00 00 00 37 00 00 00 06 37 F7
```

`00 33` = `kInputFader`, index `00 00` = `kFader`, channel `00 37` = 55
(INPUT 56), value `00 00 00 06 37` = 823.

Function calls use data category `00`:

```
F0 43 1n 3E 12 00
   "LibRcl__"               function name, 8 ASCII bytes
   "SCENE___"               module name, 8 ASCII bytes
   00 0C                    number (scene 12)
   04 00                    channel (512 for the scene library)
F7
```

The 512 channel value is confirmed by Yamaha's own AMX LS9 module, which sends
`LibRcl__SCENE___` followed by `04 00`.

Scene/library functions from the manual:

| function | meaning |
|---|---|
| `LibStr__` | store |
| `LibRcl__` | recall |
| `LibUnStr` | unknown-factor store |
| `LibUnRcl` | unknown-factor recall |
| `LibStrUd` | store undo (scene only) |
| `LibRclUd` | recall undo (scene only) |
| `LibAttrbt` | library attribute/title |
| `LibCpy__` / `LibPst__` / `LibClr__` / `LibCut__` / `LibIns__` / `LibEdtUd` | library edit |
| `LibExist` | exist-library range |
| `ColUnStr` | collection store |
| `ModFxTrg` | module/effect trigger |
| `ChlPirCp` (function) / `PAIRONCP` / `PAIRONRS` / `PAIROFF_` (modules) | channel pair operations |

For `LibAttrbt`, the payload is:

```
number (14-bit)
element (14-bit)
index (14-bit)
channel (14-bit)
value (five 7-bit bytes)
```

A `LibAttrbt` request uses sub-status `3n` and carries only number, element,
index and channel. `LibExist` requests use sub-status `3n` with module and
requested number; the reply payload is:

```
status (0 = invalid, 1 = valid)
requested number (14-bit)
top number (14-bit)
end number (14-bit)
```

### 2.1 Bulk dump

Request format, confirmed by a real LS9 example (scene 45):

```
F0 43 20 3E 12 "SCENE___" 00 2D F7
```

Data format:

```
F0 43 0n 3E  cc cc  12  "SCENE___"  dd dd  <encoded data>  ee  F7
```

* `cc cc` = byte count, placed **before** the model ID.
* The count covers the model byte, module name, number and encoded data.
* `ee` = `(-sum(bytes from the model byte through the last data byte)) & 0x7F`.
* Encoded data converts seven 8-bit words to eight 7-bit words:

```
b[0] = 0;
for i in 0..6:
    if d[i] & 0x80: b[0] |= 1 << (6 - i)
    b[i+1] = d[i] & 0x7F
```

The inverse shifts `b[0]` left each iteration and tests bit 7. A final partial
group of k raw bytes is encoded as 1+k bytes.

`BulkDumpCodec` implements this and round-trips all lengths 0..100 in the test
suite. Scene number 512 is current data; 768 is current data with recall safe.

### 2.2 Level meters

Request (sub-status `3n`, data category `21`):

```
F0 43 3n 3E 12 21  UL LU LL  countH countL  F7
```

Response:

```
F0 43 1n 3E 12 21  UL LU LL  data...  F7
```

`UL` selects the group (0 = input, 1 = mix, 2 = matrix, 3 = stereo, 4 = monitor,
5 = cue, 6 = oscillator, 7 = talkback, 8 = HA in, 9 = MS in), `LU` selects the
measurement point, `LL` is the first channel. `UL = 0x7F` stops all meter
transmission. `MeterCodec` and `MeterFrame` implement this.

### 2.3 MIDI channel messages and MMC

Program Change uses `Cn pp`; Control Change uses `Bn cc vv`; NRPN uses
CC 99/98 for the number and 6/38 for the value. MMC is
`F0 7F dd 06 cmd F7` with stop `01`, play `02`, deferred play `03`, record
strobe `06`, pause `09`. `Ls9MidiControl` implements these.

## 3. Parameter map and value conventions

`ParamChangeList_LS9.xls` contains 1,033 parameter-change rows. The build
script `tools/generate_resources.py` converts them to
`src/main/resources/io/github/leocb/ls9/ls9-parameters.csv`. It also extracts:

* `fader-table.csv` — the 1,024-entry fader curve from `PRM TABLE #03`
* `value-tables.csv` — 5,625 value/display pairs from all PRM TABLEs
* `meter-table.csv` — the 128-step meter-to-dB table

`tools/generate_java_parameters.py` turns the parameter CSV into
`Ls9Parameters.java`, with one nested class per element and one `ParameterSpec`
per row. `Ls9ValueTableRegistry` exposes the display tables, and
`Ls9Parameter` binds a spec to a console and channel.

Channel tables used by the high-level API:

| table | range | meaning |
|---|---|---|
| `#01` | 0..63 / 64..71 | INPUT 1..64 / ST IN 1L..4R |
| `#02` | 0..15 | MIX 1..16 |
| `#03` | 0..7 | MATRIX 1..8 |
| `#04` | 0..2 | STEREO L, STEREO R, MONO(C) |
| `#07` | 0..7 | DCA 1..8 |

Useful elements:

| element | index | meaning |
|---|---|---|
| `0x0031` | 0 | input ON |
| `0x0032` | 1 | input pan |
| `0x0033` | 0 | input fader |
| `0x0034` | 1 | input attenuator |
| `0x003F` | 0..7 | input mute group |
| `0x0041` | 0 | input to STEREO |
| `0x0042` | 0 | input to MONO |
| `0x0043` | 3 + 3*(mix-1) + field | input to MIX (on / pre-post / level) |
| `0x0044` | (mix-1)/2 | input to MIX pan (pair) |
| `0x0114` | 0/1 | input name, 4 chars each |
| `0x004C` / `0x004D` / `0x004E` / `0x004F` | 0/0/0/1 | MIX on / balance / fader / attenuator |
| `0x005D` / `0x005E` / `0x005F` | 0/0/0 | MATRIX on / balance / fader |
| `0x006B` / `0x006C` / `0x006D` / `0x006E` | 0/0/0/1 | STEREO on / balance / fader / attenuator |
| `0x011B` / `0x011C` / `0x011D` | 0/1 | MIX / MATRIX / STEREO names |
| `0x0021` | 0 | mute master (DCA 1..8) |
| `0x0001` | 0..3 | scene comment, 4 chars each |

### 3.1 Fader curve

`PRM TABLE #03` maps raw 0..1023 to dB. It is monotonic, with raw 0 =
`-Infinity`, raw 823 = `0.00 dB`, raw 1023 = `+10.00 dB`. The library uses the
exact table, not a formula.

### 3.2 Pan/balance encoding (inferred)

The spreadsheet lists pan/balance as `-63..63` with display `L63..C..R63`.
The 7-bit wire value is **assumed** to be `value + 64` (1..127, center 64),
following Yamaha's long-standing 7-bit pan convention. If a real console is
off by one, use `setPanRaw` / `setBalanceRaw` to calibrate. This is one of the
few value conventions that could not be confirmed from the extracted code
because the editor communicates through its own memory API.

### 3.3 Attenuator encoding (inferred)

Attenuator is listed as `-960..240` (0.1 dB units, -96.0..+24.0 dB). The
library transmits `dB*10 + 960` (0..1200), which is the natural 11-bit
non-negative encoding. Again, raw access is available if calibration is
needed.

### 3.4 HA gain (observed mapping)

The spreadsheet lists `kHAGain` min 0, max 72 and the comment
`10 - -62dB, 1dB step`. So raw 10 = 0 dB, raw 72 = -62 dB, raw 0 = +10 dB.
The library maps `raw = 10 - dB`.

### 3.5 Name packing (inferred)

Names are transmitted as two parameters, each holding four 7-bit characters
(`0 - '~~~~'`). The library packs character 0 into bits 27..21, character 1
into 20..14, etc. (MSB-first). If names appear scrambled on hardware, swap the
packing in `Ls9Sysex.packName4`.

## 4. What remains uncertain

1. **Port index.** The driver stores `YEMDevice` pointers in a 256-entry array
   indexed by the port number (`str x22, [x8, #0x98]` where `x8 = base +
   port*8`). The UI calls the first port "Port1". The library therefore defaults
   to `portIndex = 0`. If the console ignores the session, try
   `portIndex(1)`.
2. **`rxdev`.** The manual says "Device Port No. ... should be set to 1 for
   LS9". The library defaults `deviceId = 1`.
3. **MAC authentication.** The challenge is understood and implemented, but
   whether a given LS9 firmware sets capability bit 31 is not known. If it
   does and the MAC is wrong, the `0x23` reply is rejected. The driver's
   prefpane treats the MAC as mandatory security data.
4. **Connection direction.** The driver both listens on 12300 and connects to
   the device's 12300. The library implements the client direction only.
   Server mode is intentionally excluded.
5. **Bulk-block packing.** The complete block is transferred, checksummed,
   7-bit decoded and stored losslessly, but the internal packing of the memory
   image into individual parameters is not published. Live editing and
   recall-edit-store cover the console-side workflows; offline field-level
   editing of a stored block is the only remaining functional gap.
6. **`.yse` files.** Yamaha's proprietary project file format is not
   implemented; `Ls9SnapshotIO` provides a portable lossless snapshot instead.

## 5. Validation performed

* Packet encoders and decoders are unit-tested against the exact byte
  sequences above.
* Bulk-dump encode/parse is round-tripped for every data length 0..100, and a
  real LS9 scene-45 request vector is checked byte-for-byte.
* Meter request/response and the generated parameter/value-table registries are
  checked.
* A fake LS9 TCP server performs both the unauthenticated and authenticated
  handshakes, receives a fader change, answers a parameter request, serves a
  bulk dump and verifies the exchange (`test.sh`, 466 checks).
* The CRC is implemented from the driver's instruction sequence and checked
  against the same routine in the test server; this is self-consistent rather
  than independently validated on hardware.

The next step is a capture from a real LS9. A useful first capture is the
16-byte `0x20` request and the console's `0x21` reply; that resolves the port
index, the mode bitmap and whether the auth challenge is used.

## 6. Android StageMix client notes

The Android UI in `../ls9-stagemix-android` uses this library directly through
a `@JavascriptInterface` bridge, so it exercises the same EtherMIDI and SysEx
code paths described above. Two client-side behaviours are worth recording:

* **Port retry.** `Ls9Bridge.connect()` first opens with `portIndex(0)`. If
  that open fails, it retries once with `portIndex(1)`. This trades a little
  connection latency for compatibility with firmware that labels the first
  session "Port1" but expects the 1-based index in the handshake.
* **No server mode.** The client only connects out to TCP 12300; it never
  listens for a console-initiated session, matching the stated scope.

Scene and library operations use the documented function calls. The app does
not attempt to decode the packed bulk-dump image; scene/library editing follows
recall → live parameter edits → store, and the current scene title is read with
`currentTitle` / `LibAttrbt`. The current scene *number* is not exposed as a
public parameter, so in real mode the UI shows `---` until a scene is recalled
from the app.

## 7. Official StageMix IPA analysis

The decrypted iPad IPA (`com.yamaha.stagemix-ls9-32_6.0.7_und3fined.ipa`,
arm64 Mach-O, SHA-256
`fae33a6774e32a5443754e57bbd7d36be22158adeaef784e1b63d300695afbd4`)
was inspected statically. Relevant findings for this library:

* The app maps LS9 element/index pairs exactly as in `ParamChangeList_LS9.xls`.
  Scene and library calls use the same `04 00` channel observed in Yamaha's
  AMX example.
* `M7CL_EBEffectsDescription.plist` contains 54 effect definitions with
  `pIndex` values 11..42. These are the absolute indices of
  `kEffectParam1..32` inside element `0x0100`, i.e. `paramIndex = pIndex - 11`.
  For REV-X Hall: Reverb Time 11, Initial Delay 12, High Ratio 13,
  Low Ratio 14, Diffusion 15, Room Size 16, Decay 17, Low Frequency 18,
  HPF 19, LPF 20.
* Effect type (`kEffectType`, index `0x000A`) uses the same numeric indices as
  the plist's effect `index` field (for example 58 = REV-X Hall).
* The binary includes shared StageMix code for DCA, Surround, Recorder,
  Transport and Solo A/B used by other Yamaha consoles. The LS9 parameter
  list has no DCA fader, surround, recorder or transport parameters, and the
  LS9 StageMix manual does not document those screens, so the Android client
  does not expose them.

The Android client ships no IPA code or bitmap assets. Effect icons, knobs and
graphs are original SVG/CSS/canvas drawings derived from the published
parameter behaviour.
