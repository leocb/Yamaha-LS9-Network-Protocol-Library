# Firmware ↔ library parity

Comparison of the console transport implemented in `LS9P1_35.PGM`
(firmware 1.350, console side) with what this library implements.

Evidence for the firmware column is in [`../PROTOCOL.md`](../PROTOCOL.md) §1.11
and was obtained by static analysis of the main image (loaded at
`0x0C000000`). The firmware binary itself is not part of this repository.

Legend:

* **yes** — implemented and exercised
* **partial** — works for the current flows but does not model the feature
* **missing** — not implemented
* **n/a** — not applicable to a controller; console-side only

## Transport and session

| Feature | Firmware | Library | Status | Notes |
|---|---|---|---|---|
| TCP client to 12300 | yes | `EtherMidiTransport` | yes | `DEFAULT_TCP_PORT` |
| Open request `0x20`, mode bitmap | yes | `EtherMidiPacket.openRequest` | yes | bitmap `3` (bits 0+1) |
| Open ack `0x21`, session port/rx/capability | yes | `handshake()` | yes | adopts `reply[6]`, `reply[7]`, `reply[12..15]` |
| Open error `0x22` | yes | `EtherMidiException.isOpenRefused` | yes | typed; one-shot retry with bit 0 when the bitmap lacks it |
| MAC challenge `0x23` | yes | `YemAuth` + `authRequest` | yes | CRC-32 poly `0x04C11DB7` |
| Attach ack `0x24` | yes | `handshake()` | yes | also accepts `0x21` |
| Attach error `0x25` | yes | `EtherMidiException.isAttachRejected` | yes | typed |
| Live poll `0x50` | yes | `probeLive()`, keepalive loop | yes | opt-in periodic polling |
| Live reply `0x51` (`RTNLIVE`) | yes | `markLive(true)` | yes | completes a probe, keeps the session live |
| Live error `0x52` (`ERRLIVE`) | yes | `markLive(false)` + `onLiveLost` | yes | |
| Live code `0x53` | yes | `CMD_LIVE_ALT` | yes | treated as a positive reply |
| Close `0x30` send/recv | yes | `close()`, `handlePacket` | yes | |
| Reset `0x40` | yes (both directions) | `reset()`, `EtherMidiPacket.reset` | yes | |
| Short MIDI `0x11..0x13` | yes | `shortMidi`, `decode` | yes | |
| SysEx `0x16`, 4980 max | yes | `sysex`, `MAX_PACKET_SIZE` | yes | |
| Reverse connection on 12300 | yes | `reverseListen` | yes | one packet per connection, meters reused |
| Console-initiated open (`0x20`) | yes | `autoReplyToOpen` | yes | replies `0x21`, masks capability |
| Console-initiated live (`0x50`) on reverse path | yes | `replyToLive` | yes | always answered, regardless of keepalive setting |
| Frame segmentation `NEXT`/`FIN` | yes | — | closed | GAP-4, no LS9 flow uses it |
| Receive priority classes (`GROUP`/`PC`) | yes | n/a | n/a | console-side scheduling |
| Two ports 12300 / 12301 | yes | 12300 only | closed | GAP-5, 12301 unused by LS9 clients |
| Port/device ids (`Port1=0`, `rxdev=1`) | yes | `portIndex`, `deviceId` | yes | |
| Session keepalive / idle revalidation | yes | `keepaliveIntervalMs`, `liveTimeoutMs` | yes | idle-based; fires `onLiveLost` |
| Connect timeout | yes | `connectTimeoutMs` | yes | default 5000 |

## Network services (console side)

| Service | Firmware | Library | Status |
|---|---|---|---|
| DHCP / auto-IP | yes | n/a | n/a |
| Duplicate-IP detection and network restart | yes | n/a | n/a |
| UDP transport modes (`US`/`UC`) | yes | — | out of scope |
| Zone / device-group checks | yes | — | out of scope |
| Cascade link (`CASCADE IN/OUT`) | yes | — | out of scope |
| Virtual COM (`VCOMAPI`) | yes | — | out of scope |
| Discovery | no broadcast; client probes TCP 12300 | `Ls9Discovery` TCP probe | yes |

## Application layer (unchanged by the firmware map)

| Feature | Library | Status |
|---|---|---|
| Parameter change / request | `Ls9Sysex` | yes |
| Bulk dump request + decode | `BulkDumpCodec`, `Ls9BulkDecoder` | yes |
| Scene store/recall/attributes | `Ls9SceneControl` | yes |
| Level meters (reverse SysEx `0x21`) | `EtherMidiTransport.isMeterPacket` | yes |
| MIDI channel messages / MMC | `Ls9MidiControl` | yes |

### Bulk coding verification

`BulkDumpCodec` was compared against the console's `midi_blk.cpp` implementation
and matches on framing, sub-command/device nibble, 14-bit byte count, the
`count + 8 == total` relation, the 8-byte module field, the checksum range and
algorithm, both directions of the 7-bit packing (including partial groups), and
the header positions of the block number, last-packet and packet index (verified
on the console's packet builder and parser hand-off). Details are in
`../PROTOCOL.md` §2.1.1. No change is required.

## Work items — status

### GAP-1 — typed open/attach errors (`0x22`, `0x25`) — done

* `EtherMidiPacket` now defines `CMD_OPEN_ERROR` and `CMD_ATTACH_ERROR`.
* `handshake()` throws `EtherMidiException` with `isOpenRefused()` /
  `isAttachRejected()` and the reply command byte.
* On `0x22` with mode bit 0 clear, the transport retries the open once with
  bit 0 set, matching the console rule.

### GAP-2 — live/keepalive group (`0x50`–`0x53`) — done

* Incoming `0x50` is always answered with `0x51` (`liveReply`), on either the
  session socket or a reverse connection, regardless of configuration.
* `probeLive()` sends `0x50` and completes on `0x51`/`0x52` with a timeout; it
  returns a boolean and updates `isLive()`.
* `keepaliveIntervalMs(n)` enables idle-based polling (default off, so existing
  behaviour is unchanged); `liveTimeoutMs` bounds each poll;
  `onLiveLost` fires on a failed poll.
* Live frames never reach the MIDI receiver.
* `Ls9Console.Builder` exposes `keepaliveIntervalMs` and `liveTimeoutMs`.

### GAP-3 — reset API (`0x40`) — done

`EtherMidiPacket.reset(port, rxDevice)` and `EtherMidiTransport.reset()` send
the 16-byte reset frame; inbound resets are still logged.

### GAP-4 — multi-frame reassembly (`NEXT`/`FIN`) — closed (not applicable)

The console can carry a logical message over several EtherMIDI frames, but the
LS9 application layer already splits bulk blocks at the SysEx level
(`BulkDumpCodec.encodePackets` / `parsePacket`): one LS9 packet per frame, each
well under 4980 bytes. No LS9 client flow (parameter change, parameter request,
meter request/reply, bulk request, bulk packet, library call) produces a
logical frame larger than one EtherMIDI packet. The read loop therefore treats
every frame independently; an oversized frame is rejected by the decoder
instead of being concatenated. There is no LS9 feature that requires `NEXT`
segmentation, so this is closed rather than deferred.

### GAP-5 — second port 12301 — closed (not applicable)

The console descriptor pairs 12300 with 12301 and a 5688-byte bound. Static
inspection of the firmware and of StageMix's connection code shows that every
LS9 session, parameter, meter and bulk exchange uses **12300**; no client path
selects 12301. The library resolves 12300 only, which matches all observed LS9
flows. The 12301 constant is informational and is not an unimplemented app
feature.

## Tests

Added to `SelfTest` (fake console over TCP, 18 new checks):

1. `testOpenErrorTyped` — `0x22` produces a typed open refusal.
2. `testOpenRetryWithBitZero` — a bitmap without bit 0 is corrected and retried.
3. `testAttachErrorTyped` — `0x25` produces a typed attach rejection.
4. `testLiveReplyToConsolePoll` — a console `0x50` is answered with `0x51`.
5. `testProbeLiveAndReset` — `probeLive()` succeeds on `0x51`; `reset()` sends
   `0x40`.
6. `testProbeLiveTimeout` — no reply marks the session not live.

A reverse-path live poll uses the same `handlePacket` path, so it is covered by
the same test.

## Status summary

| Area | Coverage |
|---|---|
| Framing, handshake, auth, close, SysEx/MIDI | complete |
| Reverse connections (replies, meters, live polls) | complete |
| Live/keepalive | complete (GAP-2) |
| Typed error results | complete (GAP-1) |
| Reset | complete (GAP-3) |
| Segmentation, second port | closed (not used by LS9 flows) |
| Console-side services (DHCP, zone, cascade, VCOM) | out of scope |