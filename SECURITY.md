# Security policy

Filum holds a full copy of a phone's storage, so security reports are welcome.

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private vulnerability reporting
instead: the **Report a vulnerability** button on the repository's **Security** tab.

A useful report names the component (server, Android client or the protocol), the
version or commit, the steps to reproduce and what an attacker gains.

## Supported versions

Only the latest release receives fixes. Filum is an engineering thesis project with
a single maintainer, so fixes are made on a best-effort basis.

## What the design promises

The guarantees a report would break are described in the protocol:

- **Authentication** ([PROTOCOL.md §3](docs/PROTOCOL.md#3-identity-and-authentication)):
  phone and server pin each other's keys, and only a paired device reaches any
  resource other than pairing; pairing tokens are single-use and issued from the
  computer itself.
- **Storage** ([PROTOCOL.md §8](docs/PROTOCOL.md#8-server-side-storage)): the server
  never deletes a backed-up file and never overwrites one - a changed file keeps its
  previous version - and no path from the phone resolves outside the device's directory.
- **Integrity** ([PROTOCOL.md §9](docs/PROTOCOL.md#9-integrity)): every transfer is
  checked against a digest, and the acceptance ledger is chained so that changing part
  of it shows. Someone able to rebuild the whole ledger is not stopped by this - the
  protocol says so openly.

Attacks that need an unlocked phone, administrator access to the computer or physical
access to its disk are outside what Filum tries to defend against.
