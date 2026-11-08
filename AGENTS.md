# AGENTS.md — LS9 protocol library

## What this repository is

A dependency-free Java 17 library for controlling **Yamaha LS9-16 / LS9-32**
over Ethernet. The Android StageMix client lives in a separate repository:

- Protocol: `https://github.com/leocb/Yamaha-LS9-Network-Protocol-Library`
- Android app: `https://github.com/leocb/LS9-StageMix-Android`

Read `README.md` for usage and `PROTOCOL.md` for the reverse-engineering
evidence. Treat `PROTOCOL.md` as the protocol source of truth; do not change
wire formats, model IDs, addresses or checksums without cited evidence.

## Build and test

```bash
./test.sh                 # 603 checks; fake LS9 over TCP
mvn -B package            # JAR + sources + javadoc
```

There is no physical LS9 in this environment. The transport and protocol are
validated against the disassembly, the official parameter list and a byte-
accurate fake console. Do not claim hardware validation.

## Scope and boundaries

- **LS9 only.** The EtherMIDI transport is shared with DME/M7CL-era devices,
  but the parameter list, model ID (`0x12`), element map, curves, scenes and
  libraries are LS9-specific. M7CL console control is not implemented.
- **Out of scope:** server mode, hardware validation, and per-console features
  whose LS9 parameters are not published.
- The official StageMix IPA contains shared CL/QL/M7CL code for DCA, Surround,
  Recorder, Transport, Dim and Solo A/B. Do not expose those as LS9 features.
- No Yamaha code, firmware, images, nibs or confidential material belongs in
  this repository. Keep the MIT license and the interoperability note.

## Java compatibility

The library is consumed by Android and by the app's `ls9-core.jar`:

- Java 17 source/target.
- No third-party runtime dependencies.
- Keep the core Android-compatible: avoid `InputStream.readAllBytes()`,
  `String.padEnd`, `stripTrailing`, `List.copyOf` and similar APIs unavailable
  on older Android levels. Use the helpers in `internal/`.
- Generated parameter/fader/value/meter CSVs and `Ls9Parameters.java` are
  produced by `tools/generate_resources.py` and
  `tools/generate_java_parameters.py`; regenerate rather than hand-edit.
- `src/main/resources/io/github/leocb/ls9/ls9-bulk-layouts.csv` is generated
  by `tools/generate_bulk_layouts.py` from LS9 Editor's private descriptor
  tables (`_gElementDesc`, `_gParamDesc`, `_gLibraryDesc`,
  `_gCollectionDesc`). The editor binary is not shipped; the committed CSV is
  the source used at runtime. Do not hand-edit it.
- Add self-test coverage for protocol changes. `./test.sh` must stay green.

## Releases and publishing

Version in `pom.xml` must equal the tag without the `v` prefix. Use:

```bash
./scripts/release.sh 0.1.1
```

That verifies a clean tree, bumps the POM, builds, commits, creates `v0.1.1`,
and pushes the branch and tag. A tag push triggers:

1. `.github/workflows/ci.yml` — `./test.sh` + `mvn package`
2. `.github/workflows/publish.yml` — verify version → Maven Central →
   JitPack → GitHub Release

Maven Central secrets required by the publish workflow:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_TOKEN`
- `MAVEN_GPG_PRIVATE_KEY`
- `MAVEN_GPG_PASSPHRASE`

Publishing rules:

- Do not reuse or force-move a published tag.
- Keep `central-publishing-maven-plugin` at **0.12.0 or newer**; older versions
  bundle Maven 3.10 staging metadata and Central rejects the deployment.
- Coordinates: `io.github.leocb:ls9-java`.
- JitPack coordinates:
  `com.github.leocb:Yamaha-LS9-Network-Protocol-Library:vX.Y.Z`.

## Commit hygiene

- `target/`, `out/`, built JARs, IDE files and `local.properties` are ignored.
- Do not commit GPG private keys, tokens or generated release bundles.
- Update `README.md`/`PROTOCOL.md` when behavior or evidence changes.
