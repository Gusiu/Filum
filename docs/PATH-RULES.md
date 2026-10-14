# Filum - path rules

Normative document. Both implementations MUST pass the
[`testvectors/paths.tsv`](testvectors/paths.tsv) set.

This is the place where data is most easily lost without anyone noticing, which is why
the rules are spelled out here rather than left to the implementation.

---

## 1. The problem

Android uses filesystems from the ext4/f2fs family: they are case-sensitive and allow any
byte in a name except `/` and `\0`. Windows with NTFS is considerably more restrictive:

- forbidden characters `< > : " | ? * \` and the control codes `0x01`-`0x1F`,
- names reserved since DOS: `CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`,
  including with any extension and regardless of case,
- trailing dots and spaces are silently trimmed,
- **names are case-insensitive** - `Photo.jpg` and `photo.jpg` are the same file,
- a limit of 255 UTF-16 units per component and 260 characters for the whole path, unless
  the `\\?\` prefix is used.

Naive sanitisation by replacing bad characters with `_` is **lossy and not invertible**:
`a<b.txt` and `a_b.txt` land on the same path, so the second file silently overwrites the
first. That breaks invariant I2 from [PROTOCOL.md](PROTOCOL.md).

## 2. The governing principle

**The mapping from a phone path to a Windows path MUST be injective.** Two different
source paths must never land on the same destination path.

This is achieved through percent-encoding (rather than substitution with `_`), which is
reversible, and through explicit case-collision resolution (§6).

---

## 3. Canonical form on the client

Before a path enters the manifest, the client MUST:

1. express it **relative to the storage root**, with no leading `/`,
2. use `/` as the separator,
3. encode it in UTF-8 and normalise it to **Unicode NFC**,
4. contain no `.` or `..` segments, no empty segments and no `0x00` byte.

NFC normalisation matters for names with diacritics: the same name written in NFD and in
NFC is two different paths as far as a byte comparison is concerned, which would cause the
same files to be uploaded over and over.

The manifest (`POST /manifest`) operates **on the canonical form only**. The sanitisation
in §5 is a server-side storage detail and never appears in the protocol.

---

## 4. Rejection (HTTP 422)

The server MUST reject a path if it:

| Condition | `error` |
|---|---|
| starts with `/` | `absolute` |
| contains a `..` segment | `traversal` |
| contains a `.` segment | `dot_segment` |
| contains an empty segment (`a//b`) | `empty_segment` |
| contains a `0x00` byte | `nul` |
| exceeds `limits.maxPathBytes` | `path_too_long` |

Rejection is a **permanent** error for that file: the client records it in the journal and
does not retry.

> **Implementation note (.NET).** A drive letter is not rejected, because after the
> encoding in §5 it is no longer a drive letter (`C:` → `C%3A`). That does not change the
> fact that `Path.Combine(root, anything_rooted)` **discards its first argument** and
> returns an absolute path. Sanitisation MUST precede the join, and after the join the
> implementation MUST additionally check that the resolved absolute path lies inside the
> device directory. Two layers of defence, because the cost of getting it wrong is writing
> an arbitrary file anywhere on the disk.

---

## 5. Encoding components

The rules apply **independently to each segment**, in the order given. Hexadecimal digits
are written in **uppercase**.

1. **`%` → `%25`.** This must come first, or the encoding stops being reversible.
2. Characters forbidden by NTFS:
   `<` → `%3C`, `>` → `%3E`, `:` → `%3A`, `"` → `%22`,
   `|` → `%7C`, `?` → `%3F`, `*` → `%2A`, `\` → `%5C`.
3. Control codes `0x01`-`0x1F` → `%01`-`%1F`.
4. **Trailing dot or space**: encode **only the segment's last character**
   (`.` → `%2E`, ` ` → `%20`). Dots and spaces inside a name are left alone, because
   Windows trims only trailing ones. The rule applies once: `files...` → `files..%2E`.
5. **Reserved name**: if the segment before the first dot equals (case-insensitively) one
   of `CON PRN AUX NUL COM1..COM9 LPT1..LPT9`, encode the segment's **first character**.
   `CON` → `%43ON`, `con.txt` → `%63on.txt`. The first character is encoded in its original
   case, so reversing reproduces the name exactly. `CONSOLE.txt` is not a reserved name and
   is left unchanged.
6. **Length limit**: if a segment exceeds 255 UTF-16 units after encoding, truncate it to
   247 and append `~` plus the first 6 hexadecimal characters in **lowercase** of the
   `SHA-256` of the original segment (that is, the digest's first 3 bytes). This yields at
   most 254 units. The entry goes into `index.jsonl` (§7).

   Truncation **must not split a `%XX` sequence** - otherwise the encoding stops being
   reversible. If the boundary falls inside a sequence, back up to its start. Every `%` in
   the encoded text starts a sequence, because a literal `%` was turned into `%25` by rule
   1, so the recognition is unambiguous.

Segments are joined with the platform separator and appended to
`<root>/<device-slug>/files/`.

The server MUST perform all file operations through paths carrying the `\\?\` prefix, to
bypass the 260-character limit. The prefix itself does not lift the 255-unit per-component
limit - hence rule 6.

---

## 6. Case collisions

NTFS is case-insensitive, so `Pictures/Photo.jpg` and `Pictures/photo.jpg` from the phone
point at one file on disk. Left unhandled, the second would silently overwrite the first -
another violation of I2.

Detection needs no index at all, because **Windows preserves case on write** even though it
ignores case when looking a name up:

1. Compute the destination path `S` per §5.
2. Check whether a file exists at `S`. If not - write directly, done.
3. If it exists, read the **actual name from the directory** and compare it against `S`
   byte for byte.
   - Equal → this is the same file, the normal update path (versioning, protocol §8.3).
   - Different → a case collision.
4. On collision, append to the name (before the extension) `~` and the first 6 hexadecimal
   characters in **lowercase** of the `SHA-256` of the **full original path**:
   `Pictures/photo.jpg` → `Pictures/photo~6a8a8e.jpg`.

The value `~6a8a8e` in the example above is binding and is checked by a test on both sides
- an implementation producing anything else has diverged from this document.

The resolution is deterministic and stable: the same source path always yields the same
destination name, including after restoring the backup from a copy.

---

## 7. `index.jsonl`

The file `<root>/<device-slug>/.filum/index.jsonl` contains entries **only** for paths that
cannot be reversed by percent-decoding alone - that is, the cases from §5 rule 6 and §6.

```jsonc
{"p":"Pictures/photo.jpg","s":"Pictures/photo~6a8a8e.jpg","r":"case_collision"}
```

The file is **auxiliary, not authoritative**: it can be reconstructed from any complete
manifest, because both rules are deterministic. Losing it does not lose data, only the
readable record of the original name. The disk remains the source of truth (ADR-003).

---

## 8. Reversing

Percent-decoding is unambiguous, so for the vast majority of files the original name is
recovered with no side state at all. The `~<hash>` cases require consulting `index.jsonl`
or a manifest.

Keeping the mapping reversible is not tidiness for its own sake - it is the condition for
being able to move the whole backup back onto a device with a case-sensitive filesystem.
