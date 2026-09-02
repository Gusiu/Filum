# Filum — server

.NET 9, ASP.NET Core minimal APIs. Implements the resources described in
[../docs/PROTOCOL.md](../docs/PROTOCOL.md).

## Projects

| Project | Responsibility |
|---|---|
| `Filum.Protocol` | DTOs, `PathRules`, protocol constants — no file operations |
| `Filum.Core` | storage engine, server certificate, device registry, integrity ledger |
| `Filum.Server` | Kestrel with mTLS, HTTP resources, pairing, panel |
| `Filum.Tray` | system tray icon |
| `Filum.Protocol.Tests` | path vectors shared with the Android client |
| `Filum.Core.Tests` | storage engine, security, integrity, API integration tests |

## Running

```bash
cd server && dotnet run --project src/Filum.Server --no-launch-profile
```

| Setting | Default | Meaning |
|---|---|---|
| `Filum:Root` | `%LOCALAPPDATA%\Filum\backup` | backup directory |
| `Filum:ConfigDirectory` | `%LOCALAPPDATA%\Filum` | server private key |
| `Filum:Bind` | `0.0.0.0` | listen address |
| `Filum:Port` | `8765` | port |
| `Filum:EnableMdns` | `true` | advertise the service on the local network |
| `Filum:RequireDigest` | `true` | reject uploads that carry no digest |

Every setting can be overridden by an environment variable, e.g. `Filum__Root`.

The private key lives **outside the backup directory**. That directory gets copied to
another disk or restored from a backup, and the key would travel with the data.

`Filum:RequireDigest` is worth understanding before turning it off. With it on, a file
that arrives without a digest is refused before any bytes reach the disk, and every
accepted file is recorded in the ledger. Turning it off makes the digest optional and
leaves those files out of the ledger — reasonable for an installation that only cares
about getting data back, not about being able to show what it received.

## Security

Since stage 4, **mutual key pinning** applies (protocol §3.1, ADR-005):

- on first start the server generates an EC P-256 pair and a self-signed certificate,
- during pairing the phone hands over its certificate and the server records the key's fingerprint,
- every resource other than `/api/v1/pair` requires a client certificate with a known fingerprint.

There is no certificate authority and no token riding along in every request. Revoking
a lost phone's access means striking one entry from the registry.

This is what makes listening on `0.0.0.0` safe: without a paired key, no writing
resource answers at all.

## Integrity ledger

Every accepted file appends a line to `<device>/.filum/integrity.jsonl`, and each line
carries the digest of the line before it (protocol §9.2). Removing, altering or
inserting an entry breaks the chain from that point on, so partial tampering is
detectable. The panel shows the entry count and the chain verdict per device.

The chain hashes the stored line verbatim rather than the deserialised object: were the
serialiser ever to reorder fields, hashing the object would invalidate the entire
history without anyone touching it.

The file is plain JSONL with no byte-order mark, so verifying it takes a few lines in
any language and does not go through this project's code at all. That independence is
the point — a ledger only our own implementation can read is worth exactly as much as
trust in that implementation.

What the chain does **not** give you is protection against someone with disk access
rebuilding the ledger from scratch. Signing it with the server key would not help
either, since that key sits on the same machine. The honest guarantee is detectability
of partial tampering, not impossibility of tampering.

## Panel

Open in a browser **on the machine running the server**:

```
https://127.0.0.1:8765/
```

It shows server status, free space, and the paired phones with their file counts, sizes
and ledger state. Under `/pairing` it draws a QR code for pairing another device.

The panel is reachable **from loopback only** — it issues pairing tokens and shows the
contents of the backup, so exposing it on the network would let a stranger's device
pair without the owner knowing (ADR-010). The certificate is self-signed, so the
browser will warn on first visit.

The same token can be fetched from the command line:

```bash
curl -sk https://127.0.0.1:8765/api/v1/pair/new
```

The token is single-use and lives for 10 minutes.

### Disabling and unpairing a device

A disabled device gets `403` on every request, but its backup is untouched. Unpairing
removes only the `device.json` entry — the files stay, because the most common reason
to unpair is a lost phone, which is exactly when the backup matters most.

## Running as a Windows service

The same executable works either way — it detects how it was started. The service is
what lets the backup keep accepting files while no user is logged in.

Publish as a single file:

```bash
cd server && dotnet publish src/Filum.Server -c Release -r win-x64 --self-contained -p:PublishSingleFile=true
```

Register the service (command prompt with administrator rights):

```bash
sc.exe create Filum binPath= "C:\path\to\Filum.Server.exe" start= auto
```

The service runs in session 0 and **cannot show a UI** — which is why the tray icon is
a separate process started at logon (ADR-010).

The configuration directory defaults to something account-dependent, so it is worth
passing `Filum:Root` and `Filum:ConfigDirectory` explicitly at registration time —
otherwise a service running as `LocalSystem` will use different directories than the
server started by hand.

## Tray icon

```bash
cd server && dotnet run --project src/Filum.Tray
```

Shows whether the server is running, how many phones the backup covers and how much
space is left. Right-click gives the panel, the pairing screen and the backup directory.

It is a **separate process**, because a Windows service runs in session 0 and cannot
show a UI. It talks to the server over the same local API as the panel — there is no
special channel between them.

It verifies the server certificate by the key fingerprint read from `server.pfx`, not
by a trust chain. That way it does not trust whatever happens to claim port 8765.

The configuration directory and port can be overridden with `FILUM_CONFIG` and
`FILUM_PORT`.

## Tests

```bash
cd server && dotnet test
```

## Manual walkthrough

The server certificate is self-signed, so `curl` needs `-k`; the phone checks the key
fingerprint instead. That fingerprint can be verified independently:

```bash
openssl s_client -connect 127.0.0.1:8765 </dev/null 2>/dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '='
```

The result must match the `k` field from `/api/v1/pair/new`.

Sending a file requires a paired certificate. `X-Filum-Path` is base64url without
padding, and `X-Filum-Digest` carries the SHA-256 of the whole file — required by
default, and checked when the transfer closes:

```bash
curl -sk --cert client.crt --key client.key -X PUT https://127.0.0.1:8765/api/v1/file -H "X-Filum-Path: RENJTS9DYW1lcmEvYS5qcGc" -H "X-Filum-Size: 28" -H "X-Filum-Mtime: 1756400000" -H "X-Filum-Offset: 0" -H "X-Filum-Digest: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" --data-binary @file.jpg
```

Resuming means repeating the request with `X-Filum-Offset` set to the `offset` from the
last response. A mismatched offset yields `409` with an `expectedOffset` field.

## What is implemented

| Resource | State |
|---|---|
| panel: status, devices, QR code, orphan report, ledger state | yes, loopback only |
| `GET /api/v1/admin/status` | yes, loopback only |
| `GET /api/v1/pair/new` | yes, loopback only |
| `POST /api/v1/pair` | yes, single-use token, 5 attempts per 15 minutes |
| `GET /api/v1/ping` | yes, with the list of local addresses (§4.4) |
| `POST /api/v1/manifest` | yes, with `rejected` and `divergent` |
| `PUT /api/v1/file` | yes, resuming and versioning |
| mTLS with key pinning | yes |
| mDNS advertising | yes |
| integrity ledger with chained digests | yes |
| `POST /api/v1/bundle` | yes |
| `POST /api/v1/verify` | yes |
| `GET /api/v1/orphans` | yes |
| system tray icon | yes, separate process |
