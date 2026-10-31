# Filum - Android client

Kotlin, Compose, Room, OkHttp. Implements the client side of
[../docs/PROTOCOL.md](../docs/PROTOCOL.md) and [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Building

```bash
cd android && ./gradlew :app:assembleDebug
```

```bash
cd android && ./gradlew test
```

Device tests and the measurements for the thesis use the `qa` build type: debuggable,
signed with the debug key and installed as `pl.wspa.filum.qa` next to the regular app.
Its key, pairing and database are its own, so testing never touches an installation
that holds a real backup.

```bash
cd android && ./gradlew :app:assembleQa
```

Requires Android Studio (Gradle, the Kotlin compiler, the SDK). Tool versions:
AGP 9.3.2, Gradle 9.5, Kotlin 2.3.10, `compileSdk 37`, `targetSdk 36`.

`compileSdk` and `targetSdk` are two different things. We compile against 37 because
the newer AndroidX libraries require it, but system behaviour is governed by
`targetSdk` - and that stays at 36 per ADR-013, to sidestep the `ACCESS_LOCAL_NETWORK`
requirement introduced in Android 17 for now.

## Modules

| Module | Responsibility | Depends on Android |
|---|---|---|
| `:core:protocol` | wire models, `PathRules`, pairing-code parsing, integrity policy | no - plain JVM |
| `:core:crypto` | key in the Android Keystore, server key pinning | partly |
| `:core:net` | resource client, route racing, mDNS, binding sockets to Wi-Fi | partly |
| `:core:data` | Room: queue, journal, foreground-service budget, settings | yes |
| `:core:scan` | `MediaStore` with the generation counter, tree walk, exclusions | partly |
| `:core:sync` | cycle engine: manifest, resuming, bundling, error classification | no - behind interfaces |
| `:app` | pairing, home, folders, journal, dependency wiring | yes |

The split is not decoration. The hardest parts - path rules, route racing, and the
engine's decisions about what to retry and what to defer - sit in modules that test on
an ordinary JVM, with no emulator, in seconds.

## Tests

189 unit tests, all on the JVM:

- **path rules** - the same `docs/testvectors/paths.tsv` vectors as the C# implementation,
- **key pinning** - vectors from fixed certificates whose fingerprints were computed with openssl,
- **resource client** - `MockWebServer` with real TLS and `requireClientAuth`,
- **route racing** - a virtual clock, so delays measured in seconds do not lengthen the run,
- **scanner** - real files in a temporary directory,
- **engine** - full fakes for the queue and the server, checking state transitions,
- **the background-retry decision** - extracted from the worker so it can be tested at all,
- **small-file bundling** - splitting, limits, and partial bundle failure,
- **integrity policy** - whether thorough mode adds a digest to the manifest, whether the
  digest accompanies every chunk, and whether a reported divergence reaches the journal.

### Cross-implementation conformance test

`EndToEndTest` runs the **real Kotlin client against the real C# server**, over real
mTLS. The unit tests on each side check that each implementation does what its author
intended - only this test checks that the two intentions agree.

It is skipped until the environment variables are present, so it does not break an
ordinary build. To run it, start the server, fetch a pairing code and pass its parts:

```bash
curl -sk https://127.0.0.1:8765/api/v1/pair/new
```

```bash
cd android && FILUM_HOST=127.0.0.1 FILUM_PORT=8765 FILUM_PIN=<k> FILUM_TOKEN=<t> ./gradlew :core:sync:testDebugUnitTest --tests "*EndToEndTest*" --rerun-tasks
```

The test sends four files, among them a name with a colon and a name with Polish
diacritics, then asks for the manifest a second time. The server must then report that
it has everything - a disagreement over `mtime` units or Unicode normalisation would
surface right here, as a repeated request for the same files.

It is worth running after every change to the shape of a response. The most recent such
change added an optional field to the manifest: the server wrote it as `null`, the
client treated `null` as a syntax error and abandoned the sync. The full unit suite was
green on both sides - because each side did exactly what its author intended.

## Status

| Item | State |
|---|---|
| pairing, ping, manifest, resumable transfer | works, checked against the server |
| screens: pairing, home, journal | work |
| scanning: `MediaStore` and the full tree walk | works on a phone |
| scheduling: manual, daily, weekly | works |
| foreground service that watches its budget | works |
| discovery over mDNS | works, checked against the server |
| integrity check over a sample of files | works |
| fast/thorough change-detection toggle | works |
| exclusions screen (Folders) | works |
| QR scanning with the camera | works, checked on a phone |

Pairing reads the QR code in the server panel with the camera, or takes the `filum1:…`
string pasted from the clipboard. A scanned code only fills in the field: pairing still
waits for the Pair button (ADR-018).

**Tested on a phone.** A Galaxy S24 Ultra on Android 16 made a full backup of 7807
files (8 GB) in 21 minutes over Wi-Fi, with the device key in StrongBox, and then
incremental ones, also behind a VPN that forbids bypassing the tunnel. A script
independent of this code checked the copy: the ledger chain, the digest of every file on
disk and the set of paths against the phone's database all agree.

The test found faults that no test on the JVM could show - the Keystore key refused to
sign the TLS handshake, losing probes that ignored cancellation cost the race its winner,
and a bound socket hung under the VPN instead of failing.
