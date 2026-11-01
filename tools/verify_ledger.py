#!/usr/bin/env python3
"""Checks a Filum backup without Filum: the ledger's chain, and every file against it.

    python verify_ledger.py <device directory> [--head N:DIGEST ...] [--chain-only] [--json]

The device directory is <root>/<device-slug>, the one holding files/ and .filum/.
Exit status: 0 when everything adds up, 1 when something does not, 2 on bad usage.

Only the standard library, and nothing taken from the Filum code: this follows
docs/PROTOCOL.md (§8, §9.2) and docs/PATH-RULES.md (§5-§8). That is the point - a backup
whose integrity only the program that wrote it can check proves little.
"""
import argparse
import hashlib
import json
import os
import re
import sys

GENESIS = "genesis"

# PATH-RULES §5: every %XX stands for one escaped ASCII character, in uppercase hex.
ESCAPE = re.compile(r"%([0-9A-F]{2})")

# PROTOCOL §8.3: a previous version is filed as <stem>.<YYYYMMDD>T<HHmmss>Z[-n]<extension>.
VERSION_NAME = re.compile(r"^(?P<stem>.+)\.\d{8}T\d{6}Z(?:-\d+)?(?P<extension>\.[^.]*)?$")


def line_digest(line):
    """A ledger line's digest, over its exact text (PROTOCOL §9.2)."""
    return hashlib.sha256(line.encode("utf-8")).hexdigest()


def file_digest(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_segment(segment):
    """Reverses PATH-RULES §5 for one segment."""
    return ESCAPE.sub(lambda match: chr(int(match.group(1), 16)), segment)


def ledger_lines(path):
    """The ledger's lines as the server reads them: split on line breaks, empty ones skipped."""
    if not os.path.isfile(path):
        return []
    with open(path, "rb") as stream:
        text = stream.read().decode("utf-8")
    return [line.rstrip("\r") for line in text.split("\n") if line.rstrip("\r")]


def check_chain(lines):
    """Returns (entries, problem): problem is None when every line points at the one before."""
    expected_previous, expected_number = GENESIS, 1
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            return expected_number - 1, f"line {expected_number} cannot be read"
        if entry.get("n") != expected_number:
            return expected_number - 1, f"entry {entry.get('n')} where {expected_number} was expected - a line is missing or was inserted"
        if entry.get("prev") != expected_previous:
            return expected_number - 1, f"entry {expected_number} does not point at the line before it - the ledger was edited"
        expected_previous = line_digest(line)
        expected_number += 1
    return expected_number - 1, None


def read_index(meta):
    """PATH-RULES §7: stored name -> phone path, for names percent-decoding cannot reverse."""
    reverse = {}
    path = os.path.join(meta, "index.jsonl")
    for line in ledger_lines(path):
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if "s" in entry and "p" in entry:
            reverse[entry["s"]] = entry["p"]
    return reverse


def phone_path(stored, index):
    """The phone's path for a path stored under files/ (PATH-RULES §8)."""
    return index.get(stored) or "/".join(decode_segment(segment) for segment in stored.split("/"))


def walk(root):
    """Relative paths, with / as the separator, of every file under root."""
    for directory, _, names in os.walk(root):
        for name in names:
            full = os.path.join(directory, name)
            yield os.path.relpath(full, root).replace(os.sep, "/"), full


def verify(device, heads=(), chain_only=False):
    meta = os.path.join(device, ".filum")
    lines = ledger_lines(os.path.join(meta, "integrity.jsonl"))
    entries, problem = check_chain(lines)
    report = {"entries": entries, "chain": problem or "intact", "heads": [], "problems": []}
    if problem:
        report["problems"].append(f"chain: {problem}")

    # A head recorded elsewhere - by the phone, or in a report: that line must still be
    # there, unchanged, and then the whole chain up to it is unchanged too.
    for number, digest in heads:
        if number > len(lines):
            outcome = f"the ledger has {len(lines)} lines, fewer than the witnessed {number}"
        elif line_digest(lines[number - 1]) != digest:
            outcome = f"line {number} is not the line that was witnessed"
        else:
            outcome = "matches"
        report["heads"].append({"n": number, "digest": digest, "result": outcome})
        if outcome != "matches":
            report["problems"].append(f"head {number}: {outcome}")

    if chain_only:
        return report

    history = {}
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        history.setdefault(entry.get("p"), []).append(entry)

    index = read_index(meta)
    files = os.path.join(device, "files")
    seen = set()
    counts = {"files": 0, "matching": 0, "versions": 0}

    for stored, full in walk(files):
        counts["files"] += 1
        path = phone_path(stored, index)
        seen.add(path)
        if path not in history:
            report["problems"].append(f"{path}: on disk but not in the ledger")
            continue
        latest = history[path][-1]
        if file_digest(full) != latest.get("d"):
            report["problems"].append(f"{path}: the content differs from the ledger's latest entry ({latest.get('n')})")
        elif int(os.stat(full).st_mtime) != latest.get("m"):
            report["problems"].append(f"{path}: the modification time differs from the ledger's latest entry ({latest.get('n')})")
        else:
            counts["matching"] += 1

    for path in history:
        if path not in seen:
            report["problems"].append(f"{path}: in the ledger but not on disk - the server never deletes")

    for stored, full in walk(os.path.join(meta, "versions")):
        counts["versions"] += 1
        directory, _, name = stored.rpartition("/")
        match = VERSION_NAME.match(name)
        if not match:
            report["problems"].append(f"versions/{stored}: not a version name")
            continue
        original = f"{match.group('stem')}{match.group('extension') or ''}"
        path = phone_path(f"{directory}/{original}" if directory else original, index)
        digests = {entry.get("d") for entry in history.get(path, [])}
        if file_digest(full) not in digests:
            report["problems"].append(f"versions/{stored}: no ledger entry for {path} has this content")

    report.update(counts)
    return report


def parse_head(text):
    number, _, digest = text.partition(":")
    if not number.isdigit() or int(number) < 1 or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise argparse.ArgumentTypeError(f"expected N:DIGEST, got {text!r}")
    return int(number), digest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("device", help="<root>/<device-slug>, holding files/ and .filum/")
    parser.add_argument("--head", action="append", type=parse_head, default=[], metavar="N:DIGEST",
                        help="a ledger head the phone witnessed; may be given more than once")
    parser.add_argument("--chain-only", action="store_true", help="check the ledger alone, read no files")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    arguments = parser.parse_args(argv)

    if not os.path.isdir(os.path.join(arguments.device, ".filum")):
        parser.error(f"{arguments.device} holds no .filum directory")

    report = verify(arguments.device, arguments.head, arguments.chain_only)
    if arguments.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print(f"ledger: {report['entries']} entries, chain {report['chain']}")
        for head in report["heads"]:
            print(f"head {head['n']}: {head['result']}")
        if not arguments.chain_only:
            print(f"files: {report['files']}, matching the ledger: {report['matching']}; versions: {report['versions']}")
        for problem in report["problems"]:
            print(f"PROBLEM {problem}")
        print("OK" if not report["problems"] else f"{len(report['problems'])} problem(s)")
    return 0 if not report["problems"] else 1


if __name__ == "__main__":
    sys.exit(main())
