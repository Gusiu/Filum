# Filum - architecture decision record

Each entry records the context, the decision, the rationale and the **rejected
alternatives**. That last heading is the important one: a thesis defends itself by showing
what was deliberately not chosen, and why.

As of 2026-09-01. Entries marked *accepted* hold until explicitly changed.

---

## ADR-001 - One-way backup rather than synchronisation

**Status:** accepted

**Context.** The existing tools (Syncthing, Resilio, FolderSync, Nextcloud) perform
two-way synchronisation, in which deleting a file propagates to every replica. Someone
tidying up the gallery on their phone loses the backup at exactly the moment they need it.

**Decision.** The stream is phone → PC only. The set of files on the server is monotonic:
it can only grow. The server exposes no delete operation.

**Rationale.** This is not a simplified synchronisation but a different data model. The
distinction "backup ≠ sync" is the thesis of the whole project, and nearly every other
decision follows from it.

**Rejected.** Bidirectionality (fundamentally at odds with the goal), a mirror that
propagates deletions (the same), time-based retention with automatic cleanup (the user
would lose data without a conscious decision).

**Consequences.** Disk usage grows without bound - mitigated by the orphan report
(ADR-015), which informs but does not delete.

---

## ADR-002 - File identity without a cryptographic digest

**Status:** accepted as the **default**, supplemented by ADR-016

**Context.** A phone's library can be large - tens or hundreds of gigabytes. Classic tools
detect changes by comparing content digests.

**Decision.** A file is identified by the triple `(path, size, mtime)`. Digests serve only
transfer integrity and the verification mode.

**Rationale.** Hashing the whole library on every cycle requires reading all of it, which
on a battery-powered device costs more the larger the library is. Hashing throughput will
be measured empirically, and the result makes that cost estimable for any size.

**Rejected.** Full hashing on every cycle (energy cost), incremental hashing of new files
only (does not detect modifications to existing ones, so it does not solve the problem).

**Consequences.** A modification that preserves size and mtime goes undetected. The risk is
marginal for photos and video; mitigated by a verification mode the user starts
deliberately.

**A distinction worth keeping.** A digest computed **during a transfer already in progress**
is nearly free, because the bytes pass through the CPU anyway. We reject hashing as a method
of *change detection*, not as a method of *integrity checking*. Conflating the two is a
common mistake.

**Revision.** This decision remains the default mode but is no longer the only one - ADR-016
adds a thorough mode in which a file's identity is its digest. The reason: metadata can be
forged, and in forensic use that is not a hypothetical scenario.

---

## ADR-003 - A server with no database; the disk is the source of truth

**Status:** accepted

**Context.** The server has to answer which of the manifest's files it is missing.

**Decision.** No content index. The answer is produced by a `stat()` on each entry in the
batch and a comparison of `(size, mtime)` against the filesystem.

**Rationale.** Two thousand `stat()` calls on NTFS take tens of milliseconds - irrelevant
next to transfers lasting hours. In exchange, a whole class of problems disappears: an index
drifting out of step with reality, schema migrations, a database corrupted by a power cut.
A backup moved to another disk, copied, or restored from a copy simply works. A file deleted
by hand in Explorer is detected and re-sent.

**Rejected.** SQLite as an index (drift from actual state, the need to rebuild), an in-memory
index with periodic persistence (the same problems plus loss on sudden shutdown).

**Consequences.** Auxiliary structures (`index.jsonl`, orphan reports, and the integrity
ledger's index) are explicitly marked as reconstructible and non-authoritative.

---

## ADR-004 - Server in C#/.NET

**Status:** accepted

**Context.** Python with FastAPI, Kotlin with Ktor and C# with ASP.NET Core were considered.

**Decision.** .NET 9, ASP.NET Core minimal APIs, published as a single executable with no
external runtime.

**Rationale.** Everything that is hard on the server side is Windows-specific: long NTFS
paths, reserved names, free space on a volume, firewall rules, the system service, the tray
icon. In .NET these are native APIs; in Python they are workarounds through `ctypes` or
`pywin32`. Installation for an end user is one file rather than a PyInstaller bundle, which
additionally tends to trip antivirus heuristics.

**Rejected.** Python/FastAPI (a poor deployment story on Windows), Kotlin/Ktor (tempting for
sharing a protocol module with the client - see consequences).

**Consequences.** We give up sharing protocol code with the Android client. We consider this
an advantage: two independent implementations reconciled by a written specification and
shared test vectors prove the specification is complete. A shared module would only have
hidden its gaps.

---

## ADR-005 - Mutual key pinning instead of a certificate authority

**Status:** accepted

**Context.** The server is sometimes reachable from outside the local network (ADR-006), so
a bearer token in the clear would not be enough.

**Decision.** Both sides use self-signed certificates as a container for a key. The client
pins the server's key, the server pins the keys of paired clients (mTLS). The phone's private
key is created in the Android Keystore with hardware backing.

**Rationale.** Symmetric, with no certificate-authority logic, no expiry, and no browser
warnings for the user. There is no token that could leak into a log. Revoking a lost phone's
access means striking one entry.

**Rejected.** A `Bearer` token over plain HTTP (eavesdropping on the local network, backup
contents in the clear), a token over TLS with a public certificate (requires a domain name
and an authority), a homegrown mini-CA signing CSRs (more moving parts with no security
gain).

**Consequences.** The client does not verify the hostname. This is safe **only** because key
pinning replaces it - and must be documented that way, so that nobody removes one safeguard
in the belief that the other covers it.

**StrongBox is not required.** A separate secure element is a higher-end feature; cheaper
hardware offers only the processor's trusted environment (TEE). The application asks for
StrongBox and quietly falls back to TEE when refused - the private key leaves the device in
neither case; what differs is resistance to physical attack. The level actually in use is
read from the Keystore on every start and recorded in the journal at pairing time, rather
than assumed from the hardware.

---

## ADR-006 - Identity by key, not by address

**Status:** accepted

**Context.** A server's address changes constantly: a different network, a new DHCP lease, an
active VPN, the phone away from home.

**Decision.** The client stores a **set of routes** to a server with a known pin, and races
them in parallel on every connection, taking the first correct answer. The `ping` response
carries the list of addresses the server sees itself under, so the set updates itself.

**Rationale.** The address stops being an identity and becomes one of several ways through.
The user configures nothing, ever: at home the local route wins as the fastest, away from
home the overlay network, and after a router change mDNS finds the new address. The model is
proven in Syncthing and in Tailscale itself.

**Rejected.** A single remembered address (breaks on the first network change), a fixed
address forced on the router (pushes configuration onto the user), mDNS alone (does not work
outside the local network).

**Consequences.** It requires binding the socket to a specific `Network` object on Android so
that local routes work despite an active VPN. With a VPN that blocks traffic outside the
tunnel, the local route becomes unavailable - the degradation must be silent.

**Revised after testing on a device.** A VPN that forbids bypassing the tunnel did not make
binding fail: connections on the bound socket hung until they timed out, while the default
route reached the server, because the VPN left the local network out of the tunnel. Local
addresses are therefore raced both bound and on the default route (protocol §4.3), and the
transfer follows the winner.

---

## ADR-007 - Keeping previous versions instead of overwriting

**Status:** accepted

**Context.** Content with a different size or mtime appears under a known path.

**Decision.** The previous content goes to `.filum/versions/` with a timestamp taken from its
own mtime. The `files/` directory stays a clean mirror of the phone's current state.

**Rationale.** Overwriting is a form of data loss, so it would contradict ADR-001. Keeping
versions outside `files/` protects the readability of a tree that is meant to stay browsable
in Explorer with no tools at all (ADR-003).

**Rejected.** Overwriting (contradicts ADR-001), versioning only non-media files (a rule
dependent on file type, hard to defend and hard to test).

**Consequences.** No retention policy - the versions directory grows without bound. Recorded
as an open question.

---

## ADR-008 - Aggregating small files

**Status:** accepted, to be confirmed by measurement

**Context.** A media library usually holds tens of thousands of files, the large majority of
them small things below a few hundred kilobytes.

**Decision.** A separate `POST /bundle` resource accepting a stream of files with length
framing. The size threshold is supplied by the server in the `ping` response.

**Rationale.** With one HTTP request per file, protocol overhead starts to dominate the data
transfer.

**Rejected.** `PUT` per file only (overhead), a whole tar archive (prevents resuming and
partial success).

**Consequences.** Bundles do not support resuming - acceptable, since they only cover small
files. The threshold must be established by measurement, not assumed; it is one of the
experiments for the results chapter.

---

## ADR-009 - Percent-encoding instead of character substitution

**Status:** accepted

**Context.** NTFS forbids characters that are legal on ext4, and is case-insensitive.

**Decision.** Forbidden characters are percent-encoded; case collisions are resolved with a
deterministic digest-derived suffix.

**Rationale.** The mapping must be injective. Substituting `_` makes `a<b.txt` and `a_b.txt`
land on the same path, and the second file silently overwrites the first - a direct violation
of ADR-001 and ADR-007.

**Rejected.** Substituting `_` (lossy), full base64 encoding of names (unreadable for the
user, contradicting ADR-003).

**Consequences.** Names with special characters look unusual on disk, but stay readable and
fully reversible.

---

## ADR-010 - A web panel plus a tray icon

**Status:** accepted

**Context.** The server needs an interface for pairing, inspection and settings.

**Decision.** The interface is a page served by the server itself (Blazor), opened from the
tray icon's menu.

**Rationale.** The server is an HTTP server anyway, so the panel is nearly free - no second
set of libraries and no second way of presenting state. The tray icon gives the application a
physical presence in the system.

**Correction made during implementation.** We originally assumed the panel would also be
reachable from the phone and from another computer on the network, which is convenient when
diagnosing a transfer. That had to go: the panel **issues pairing tokens** and shows the
contents of the backup, so exposing it on the network would let a stranger's device pair
without the owner knowing. The panel is reachable from loopback only. Diagnostic convenience
is not worth a side door into the backup.

**Rejected.** A full WPF/WinUI application (duplicating the state-presentation logic), a
console alone (unacceptable for an end user).

**Consequences.** The system service runs in session 0 and cannot display an interface, so
the tray ultimately has to be a separate process talking to the server over the same API.

---

## ADR-011 - mtime in seconds, normalised in one place

**Status:** accepted

**Context.** `MediaStore.DATE_MODIFIED` returns seconds, `File.lastModified()` milliseconds.
The scanner uses both sources.

**Decision.** The protocol works exclusively in seconds since the Unix epoch, UTC.
Normalisation happens in one place, at the boundary where the filesystem is read.

**Rationale.** If the same file reached the manifest from one source at one time and the other
at another, its identity would change between cycles and the file would be uploaded over and
over. On a large library that is a failure, not an inconvenience.

**Rejected.** Milliseconds (some sources do not provide them, so a conversion would be needed
in the other direction anyway).

---

## ADR-012 - The first transfer over the network; adoption mode optional

**Status:** accepted

**Context.** The first sync covers the whole library and can take a long time. An initial dump
over a cable, with only the difference sent afterwards, was considered.

**Decision.** The primary path is a full transfer over the network, spread over several
nights. Adopting an existing dump is an additional feature.

**Rationale.** Copying over MTP notoriously fails to preserve modification times, and FAT has
a two-second granularity. A mismatched mtime would make the first run consider every file
missing and re-send everything despite the existing dump. A network transfer forces proper
resumability, which is needed anyway, and eliminates a whole class of errors.

**Rejected.** A cable dump only, with mandatory adoption (a single point of failure at first
run).

**Consequences.** The first run takes several nights. Acceptable, because it happens once.

---

## ADR-013 - targetSdk 36 and distribution outside the store

**Status:** accepted

**Context.** Access to the whole of storage requires `MANAGE_EXTERNAL_STORAGE`, which Google
Play does not permit for an application of this kind.

**Decision.** `targetSdk 36`, installed by sideloading, outside the store.

**Rationale.** `targetSdk 36` sidesteps, for now, the `ACCESS_LOCAL_NETWORK` requirement
introduced in Android 17. Since distribution happens outside the store anyway, the obligation
to keep raising the target level does not apply.

**Rejected.** The Storage Access Framework (too slow across hundreds of thousands of files),
restricting ourselves to `MediaStore` (would miss documents and downloads).

**Consequences.** A dependency on a system version that will expire. Deliberately recorded as
a known limitation, to be described in the thesis. `/Android/data` and `/Android/obb` remain
inaccessible and we do not try to work around that; `/Android/media` is accessible and is
included in the backup.

---

## ADR-014 - The overlay network as an interchangeable deployment profile

**Status:** accepted

**Context.** Access from outside the local network requires a meeting point: port forwarding,
an overlay network, or a relay of one's own. There is no fourth option.

**Decision.** The application **integrates with no provider**. An overlay network contributes
exactly one more address to the route set from ADR-006.

**Rationale.** This makes Meshnet, Tailscale, a hand-rolled WireGuard, DDNS with port
forwarding and a self-hosted relay interchangeable without changing a single line of code. The
thesis describes an architecture that admits any of them, rather than resting on a commercial
product.

**Rejected.** Integrating with a specific provider's API (a dependency and a loss of
neutrality), a built-in WireGuard implementation (an enormous effort unrelated to the subject).

**Consequences.** The claim of "no cloud" needs qualifying in the thesis: it means "the data is
never stored by a third party", not "no packet ever crosses someone else's machine". The literal
version is achieved only with a self-hosted relay or port forwarding.

---

## ADR-015 - An orphan report without deletion

**Status:** accepted

**Context.** Since the server never deletes, moving files around on the phone multiplies copies
and disk usage grows without end.

**Decision.** After a session with a complete manifest closes, the server computes the list of
paths present on its side and absent on the phone, and exposes it as a report. It deletes
nothing.

**Rationale.** It preserves the invariant from ADR-001 while giving the user control over the
growth in usage. Orphans are a by-product of a reconciliation that has to be run periodically
anyway.

**Rejected.** Automatic deletion after a grace period (contradicts ADR-001), ignoring the
problem entirely (the disk would fill with no explanation).

**Consequences.** The decision to delete belongs to the user alone and is made by hand, outside
the application or through an explicit action in the panel.

---

## ADR-016 - Integrity over frugality

**Status:** accepted

**Context.** The thesis is written for a cybersecurity and digital forensics specialisation.
Earlier decisions weighed energy cost against change detectability and consistently chose
frugality (ADR-002). For this class of use that weighting is the wrong way round: a backup
about which nothing can be demonstrated has limited evidentiary value.

**Decision.** Integrity is the priority and its cost is acceptable. Concretely:

1. **A digest of every file is mandatory on acceptance.** The server may refuse a transfer
   without one. This is no longer the optional convenience of protocol §9.
2. **An integrity ledger.** The server keeps a durable, append-only record: for every accepted
   file, its path, size, mtime, digest and the moment of acceptance. The ledger is a **chain of
   digests** - each entry covers the digest of the previous one, so retroactive edits to the
   ledger itself are detectable.
3. **The change-detection policy is the user's choice.** Fast mode preserves ADR-002. Thorough
   mode hashes every file on every scan and compares it against the ledger, thereby detecting a
   **metadata-preserving substitution** - a file with unchanged size and modification time but
   different content.
4. **Verification need not be a sample.** The user chooses the scope; a sample remains the
   default for reasons of time, not of principle. Whatever the scope, it must span files of all
   sizes: excluding large ones leaves the hardest-to-reproduce data permanently unchecked.

**Rationale.** The distinction from ADR-002 still holds - hashing for change detection really
does cost a full read of the data. What changes is the answer to whether it is worth it: in
evidentiary use, detecting a metadata-preserving substitution is exactly what the mechanism
exists for. Tampering that leaves size and mtime alone is trivial to perform and invisible to
any system built on metadata alone.

The digest chain in the ledger costs one extra function call per file, and turns a list of
digests into a record that cannot be quietly corrected.

**Rejected.** Forcing thorough mode with no choice (on a large library and weaker hardware this
would mean day-long cycles and a user who turns the application off), signing the ledger with
the server key (the key sits on the same machine, so the signature does not protect against
whoever has access to it - the digest chain gives the same detectability without pretending to
a stronger guarantee).

**Consequences.** Thorough mode requires a full read of the phone's storage on every cycle. This
is visible in the interface and documented, rather than hidden behind a default.

---

## ADR-017 - No assumptions about device capacity

**Status:** accepted

**Context.** The documentation assumed a first sync on the order of 200 GB and derived part of
its reasoning from that number. The assumption turned out to be tied to one particular piece of
hardware - on a device with less storage it is physically impossible, and on a larger one it is
an underestimate.

**Decision.** We assume nothing about library size or device capacity anywhere. The thresholds
we do need are **relative**, or follow from constraints unrelated to data size.

| Threshold | Relative to | Why not absolute |
|---|---|---|
| free-space reserve | `max(2% of capacity, a small minimum)` | 2% of a 128 GB disk is too little, of an 8 TB disk too much - hence both terms |
| bundling threshold | HTTP request overhead against transfer time | depends on the network, not on library size |
| chunk size | the duration of a single request | depends on throughput |
| background-work budget | the Android system limit | not our choice to make |

**Rationale.** The architecture defends itself without that number. Three-level resumability,
incremental scanning and the budget all make sense for a library of any size - more so the
larger it is, but there is no threshold below which they stop mattering. Tying the reasoning to
one figure made it false everywhere that figure does not hold.

**Rejected.** Naming a different number instead of 200 GB (the same mistake with a different
value), scaling the thresholds to library size (this would require knowing it before the first
scan, i.e. before we know anything).

## ADR-018 - Reading the pairing code with the camera, decoded on the device

**Status:** accepted

**Context.** Pairing worked by pasting the `filum1:` string, while the server panel draws the
same string as a QR code and the phone has a camera. The code carries a one-time token and the
server's key fingerprint - the two things that decide which computer the phone will trust.

**Decision.** CameraX supplies the frames and ZXing decodes them, inside the app. The scanner
takes only a code that parses as a Filum pairing code, and a scanned code only fills in the
field: pairing still waits for the user to press Pair. The camera runs only while the scanner
is on screen.

**Rationale.** ZXing is a single Java library with no network code, and it needs no Google
Play services, which a sideloaded device may lack. The data never leaves the phone, which is
what this project promises about everything else too.

**Rejected.** Google ML Kit (by its own data-disclosure notice it sends Google metrics on how
its APIs are used, and its smaller variant depends on Play services). The system camera's QR
recognition with a `filum1:` link (the link would have to be openable from a browser, so any
web page could put an attacker's pairing code one tap away from the user). A separate scanner
app called through an intent (another application would see the token).
