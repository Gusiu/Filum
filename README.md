# Filum

One-way file backup from an Android phone to a Windows PC, with no cloud and no
intermediary holding the data.

Engineering thesis, WSPA.

> The design documents under `docs/` are written in Polish, as the thesis itself is.
> They remain normative: this file summarises them, it does not replace them.

---

## What it is, and what it is not

Filum is a **backup, not a sync**. The distinction is not cosmetic - the whole
architecture follows from it: deleting a file on the phone **does not delete it
from the PC**.

The well-known tools (Syncthing, Resilio, FolderSync, Nextcloud) synchronise in
both directions and propagate deletions. Someone tidying up their gallery loses
the backup at precisely the moment they need it. The set of files Filum keeps is
monotonic - it can only grow.

## Components

| Directory | Description |
|---|---|
| `server/` | HTTP service for Windows: .NET 9, browser panel, tray icon |
| `android/` | client: Kotlin, Compose, WorkManager, Room, OkHttp |

## Key properties

- **Nothing is lost.** The server exposes no delete operation. Different content
  under a known path does not overwrite what is there - the old version is filed away.
- **Storage is transparent.** What lands on disk is an ordinary directory tree,
  browsable in Explorer. Restoring does not require this application.
- **Integrity is checkable.** The digest of every accepted file goes into an
  append-only ledger where each line carries the digest of the one before it.
  Removing or altering an entry breaks everything that follows. The ledger is plain
  JSONL - it can be verified without this application, in a few lines of code.
- **Change detection is the user's choice.** Fast mode compares size and modification
  time; thorough mode hashes every file on every scan and catches a substitution that
  left the metadata alone.
- **Transfers resume** at three levels: mid-file, mid-queue, and mid-campaign across
  many cycles.
- **An address is not an identity.** The server is recognised by its public key, and
  the client keeps a set of routes and races them in parallel: local network, mDNS,
  overlay network. Changing routers or leaving the house requires no configuration.
- **Mutual key pinning (mTLS)** instead of a bearer token in the clear.

## Documentation

| Document | Contents |
|---|---|
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | protocol specification - normative for both implementations |
| [docs/PATH-RULES.md](docs/PATH-RULES.md) | path normalisation and sanitisation for NTFS |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | module breakdown, flows, build order |
| [docs/DECISIONS.md](docs/DECISIONS.md) | architecture decision record, including the rejected alternatives |
| [docs/testvectors/paths.tsv](docs/testvectors/paths.tsv) | shared test vectors for path sanitisation |

Server and client are written independently, in different languages, and reconciled
solely through the specification and the shared test vectors. That is deliberate:
implementing from the document alone is the evidence that the document is complete.

## Target environment

- Phone: Android 11 or newer (`minSdk 30`), `targetSdk 36`
- Sideloaded - requires `MANAGE_EXTERNAL_STORAGE`
- PC: Windows 10/11, NTFS
- A library of any size; the first sync spreads over as many cycles as it needs

## Status

Stages 1-9 of the [build order table](docs/ARCHITECTURE.md#8-build-order).

| Item | State |
|---|---|
| protocol specification and architecture | written |
| server: pairing, ping, manifest, resumable transfer, versioning | works |
| mTLS with mutual key pinning | works, checked against real TLS |
| client: seven modules, from path rules to the cycle engine | works, APK builds |
| scheduling and background work within the budget | works |
| server panel and tray icon | works |
| server discovery over mDNS | works |
| small files aggregated into bundles | works |
| integrity checks and the orphan report | works |
| append-only ledger chained by digests | works, independently verifiable |
| thorough change detection (digest in the manifest) | works |
| server as a Windows service | works, not registered |
| **client↔server conformance test** | passes |
| **186 server tests, 217 client tests** | green |
| tested on a phone (Galaxy S24 Ultra, Android 16) | full backup and incremental cycles, also behind a VPN |
| QR scanning with the phone camera | not started |

`EndToEndTest` runs the real Kotlin client against the real C# server and checks that
both compute the same file identity - details in [android/README.md](android/README.md).
That test earns its keep: a disagreement over whether an empty optional field is
omitted or written as null passed the full unit suite on both sides and only surfaced
there.

The cycle has not yet been run on a real phone: `MediaStore`, the Android Keystore
and the storage permission can only be exercised on a device.

## Building

```bash
cd server && dotnet test
```

```bash
cd server && dotnet run --project src/Filum.Server --no-launch-profile
```

The Android client needs Android Studio - details in [android/README.md](android/README.md).
