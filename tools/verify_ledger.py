#!/usr/bin/env python3
"""Checks a Filum backup without Filum: the ledger's chain, and every file against it.

    python verify_ledger.py <device directory> [--head N:DIGEST ...] [--chain-only] [--json]
    python verify_ledger.py <device directory> --provenance PATH [--head N:DIGEST ...] [--json]

The device directory is <root>/<device-slug>, the one holding files/ and .filum/.
Exit status: 0 when everything adds up, 1 when something does not, 2 on bad usage.

--provenance follows one phone path, written as the ledger writes it (DCIM/IMG_0001.JPG),
instead of checking the whole backup. It first names the device the path came from: label,
key pin and pairing date from .filum/device.json, or unknown when there is no readable one,
as after the device was unpaired. For every ledger line that accepted the path it prints
the time of acceptance, size, modification time and digest; where on disk that version lies,
under files/ or .filum/versions/, and whether the file there still holds that content; and,
given --head, whether the line is witnessed: the head's own line, or one still linked to it
through the digests in between. Other files on disk standing for the path are listed too,
and the current copy must carry the modification time accepted, as in the full check.

Only the standard library, and nothing taken from the Filum code: this follows
docs/PROTOCOL.md (§8, §9.2, §9.5) and docs/PATH-RULES.md (§5-§8). That is the point - a
backup whose integrity only the program that wrote it can check proves little.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys

GENESIS = "genesis"

# PROTOCOL §2: times are whole seconds since the Unix epoch, UTC.
EPOCH = datetime.datetime(1970, 1, 1)

# PATH-RULES §5: every %XX stands for one escaped ASCII character, in uppercase hex.
ESCAPE = re.compile(r"%([0-9A-F]{2})")

# PROTOCOL §8.3: a previous version is filed as <stem>.<YYYYMMDD>T<HHmmss>Z[-n]<extension>,
# the extension running from the name's last dot - so the stem of .nomedia is empty.
VERSION_NAME = re.compile(r"^(?P<stem>.*)\.(?P<stamp>\d{8}T\d{6})Z(?:-\d+)?(?P<extension>\.[^.]*)?$")


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


def utc(seconds, layout):
    """A time in seconds since the epoch, written in UTC to `layout`; None when it is no such time."""
    try:
        return (EPOCH + datetime.timedelta(seconds=seconds)).strftime(layout)
    except (TypeError, ValueError, OverflowError):
        return None


def read_entry(line):
    """A line of the ledger or of index.jsonl as a dict, or None when it cannot be read: every
    entry is a JSON object, so text that is not JSON, or JSON that is not an object, is none."""
    try:
        entry = json.loads(line)
    except ValueError:
        return None
    return entry if isinstance(entry, dict) else None


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
        entry = read_entry(line)
        if entry is None:
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
        entry = read_entry(line)
        if entry is not None and "s" in entry and "p" in entry:
            reverse[entry["s"]] = entry["p"]
    return reverse


def read_device(meta):
    """The device's record from .filum/device.json (PROTOCOL §8.1), or None when there is no
    readable one - unpairing a device removes that file and nothing else."""
    try:
        with open(os.path.join(meta, "device.json"), encoding="utf-8") as stream:
            record = json.load(stream)
    except (OSError, ValueError):
        return None
    return record if isinstance(record, dict) else None


def phone_path(stored, index):
    """The phone's path for a path stored under files/ (PATH-RULES §8)."""
    return index.get(stored) or "/".join(decode_segment(segment) for segment in stored.split("/"))


def version_of(stored, index):
    """(phone path, timestamp in the name) for a path stored under versions/, or None when the
    name is not a version name (PROTOCOL §8.3)."""
    directory, _, name = stored.rpartition("/")
    match = VERSION_NAME.match(name)
    if not match:
        return None
    original = f"{match.group('stem')}{match.group('extension') or ''}"
    return phone_path(f"{directory}/{original}" if directory else original, index), match.group("stamp")


def mtime_problem(path, full, entry):
    """The problem, if any, with a current copy holding its path's latest content: it must also
    carry that entry's modification time, which the server sets on commit (PROTOCOL §8.2).
    verify and --provenance ask this alike, so that one file gets one verdict."""
    if int(os.stat(full).st_mtime) != entry.get("m"):
        return f"{path}: the modification time differs from the ledger's latest entry ({entry.get('n')})"
    return None


def walk(root):
    """Relative paths, with / as the separator, of every file under root."""
    for directory, _, names in os.walk(root):
        for name in names:
            full = os.path.join(directory, name)
            yield os.path.relpath(full, root).replace(os.sep, "/"), full


def check_ledger(lines, heads=()):
    """The part of a report that reads the ledger alone: its chain and the heads given."""
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
    return report


def witnessed_span(lines, number, digest):
    """(first, last): the lines a head still vouches for, or None when line `number` is not the
    line that was witnessed (PROTOCOL §9.5).

    The witnessed line carries the digest of the line before it, and that line the digest of
    its own predecessor - so the span reaches back until a line no longer hashes to what the
    line after it says it should."""
    if number > len(lines) or line_digest(lines[number - 1]) != digest:
        return None
    first = number
    while first > 1 and (read_entry(lines[first - 1]) or {}).get("prev") == line_digest(lines[first - 2]):
        first -= 1
    return first, number


def verify(device, heads=(), chain_only=False):
    meta = os.path.join(device, ".filum")
    lines = ledger_lines(os.path.join(meta, "integrity.jsonl"))
    report = check_ledger(lines, heads)
    if chain_only:
        return report

    history = {}
    for line in lines:
        entry = read_entry(line)
        if entry is not None:
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
            problem = f"{path}: the content differs from the ledger's latest entry ({latest.get('n')})"
        else:
            problem = mtime_problem(path, full, latest)
        if problem:
            report["problems"].append(problem)
        else:
            counts["matching"] += 1

    for path in history:
        if path not in seen:
            report["problems"].append(f"{path}: in the ledger but not on disk - the server never deletes")

    for stored, full in walk(os.path.join(meta, "versions")):
        counts["versions"] += 1
        version = version_of(stored, index)
        if not version:
            report["problems"].append(f"versions/{stored}: not a version name")
            continue
        path = version[0]
        digests = {entry.get("d") for entry in history.get(path, [])}
        if file_digest(full) not in digests:
            report["problems"].append(f"versions/{stored}: no ledger entry for {path} has this content")

    report.update(counts)
    return report


def provenance(device, path, heads=()):
    """One phone path's history: the device it came from, every ledger line that accepted it,
    where on disk each accepted version lies and whether it still holds that content, and
    which of those lines the heads vouch for."""
    meta = os.path.join(device, ".filum")
    lines = ledger_lines(os.path.join(meta, "integrity.jsonl"))
    # An unpaired device leaves its backup behind without device.json: unknown, but nothing
    # wrong with the backup.
    record = read_device(meta)
    report = {"device": {key: record.get(key) for key in ("label", "pin", "pairedAt")} if record else None,
              "path": path, **check_ledger(lines, heads)}

    spans = []
    for head in report["heads"]:
        head["lines"] = witnessed_span(lines, head["n"], head["digest"])
        if head["lines"]:
            spans.append(head["lines"])

    acceptances, latest = [], None
    for number, line in enumerate(lines, 1):
        entry = read_entry(line)
        if entry is not None and entry.get("p") == path:
            latest = entry
            acceptances.append({
                "line": number, "at": entry.get("at"), "s": entry.get("s"), "m": entry.get("m"),
                "d": entry.get("d"), "locations": [], "content": "missing",
                "witnessed": any(first <= number <= last for first, last in spans) if heads else None,
            })

    # Every file standing for this path, mapped back the way verify maps it, so that the two
    # agree on which file belongs to which path. Sorted, because the order a directory lists
    # its files in depends on the filesystem, and the report should not.
    index = read_index(meta)
    copies = [{"location": f"files/{stored}", "full": full, "stamp": None, "digest": file_digest(full)}
              for stored, full in walk(os.path.join(device, "files"))
              if phone_path(stored, index) == path]
    for stored, full in walk(os.path.join(meta, "versions")):
        version = version_of(stored, index)
        if version and version[0] == path:
            copies.append({"location": f".filum/versions/{stored}", "full": full, "stamp": version[1],
                           "digest": file_digest(full)})
    copies.sort(key=lambda copy: copy["location"])

    accepted = {acceptance["d"] for acceptance in acceptances}
    for acceptance in acceptances:
        # Where the server keeps it (PROTOCOL §8.2-§8.3): the latest acceptance is the current
        # copy, and an earlier one was filed away when the next arrived, under a name carrying
        # its modification time - which tells apart versions that hold the same content.
        if acceptance is acceptances[-1]:
            expected = [copy for copy in copies if copy["location"].startswith("files/")]
        else:
            stamp = utc(acceptance["m"], "%Y%m%dT%H%M%S")
            expected = [copy for copy in copies if stamp and copy["stamp"] == stamp]
        # Failing that, the same content anywhere else: a file deleted by hand and sent again
        # leaves no version, and the current copy holds what the earlier line accepted.
        found = ([copy for copy in expected if copy["digest"] == acceptance["d"]]
                 or [copy for copy in copies if copy["digest"] == acceptance["d"]])
        if found:
            acceptance["content"] = "matches"
        else:
            # Nowhere. A file in its place holding content this path never had is what became
            # of it; one holding another line's content belongs to that line.
            found = [copy for copy in expected if copy["digest"] not in accepted]
            if found:
                acceptance["content"] = "differs"
        acceptance["locations"] = [copy["location"] for copy in found]

    placed = {location for acceptance in acceptances for location in acceptance["locations"]}
    report["acceptances"] = acceptances
    report["others"] = [
        {"location": copy["location"],
         "content_of": [acceptance["line"] for acceptance in acceptances if acceptance["d"] == copy["digest"]]}
        for copy in copies if copy["location"] not in placed]

    if not acceptances and not copies:
        report["problems"].append(f"{path}: not in the ledger and not on disk")
    for acceptance in acceptances:
        if acceptance["content"] == "differs":
            report["problems"].append(
                f"line {acceptance['line']}: the content at {', '.join(acceptance['locations'])} is not what was accepted")
        elif acceptance["content"] == "missing":
            report["problems"].append(f"line {acceptance['line']}: the content accepted is not on disk")
    if acceptances and acceptances[-1]["content"] == "matches":
        for copy in copies:
            if copy["location"].startswith("files/") and copy["location"] in acceptances[-1]["locations"]:
                problem = mtime_problem(path, copy["full"], latest)
                if problem:
                    report["problems"].append(problem)
    for other in report["others"]:
        if not other["content_of"]:
            report["problems"].append(f"{other['location']}: no ledger entry for {path} has this content")
    return report


def describe_device(device):
    if device is None:
        return "device: unknown - .filum/device.json is missing or cannot be read"
    label, pin, paired = ("?" if device[key] is None else device[key] for key in ("label", "pin", "pairedAt"))
    return f"device: {label}, key pin {pin}, paired {paired}"


def print_provenance(report):
    def when(seconds):
        return utc(seconds, "%Y-%m-%dT%H:%M:%SZ") or repr(seconds)

    print(f"{report['path']}: accepted {len(report['acceptances'])} time(s)")
    for acceptance in report["acceptances"]:
        print(f"line {acceptance['line']}: accepted {when(acceptance['at'])}, {acceptance['s']} bytes, "
              f"modified {when(acceptance['m'])}")
        print(f"  sha256 {acceptance['d']}")
        for location in acceptance["locations"]:
            print(f"  {location}: content {acceptance['content']}")
        if not acceptance["locations"]:
            print("  not on disk")
        if acceptance["witnessed"] is not None:
            print("  witnessed" if acceptance["witnessed"] else "  not witnessed")
    if report["others"]:
        print("also on disk:")
    for other in report["others"]:
        held = ", ".join(f"line {line}" for line in other["content_of"])
        print(f"  {other['location']}: " + (f"the content of {held}" if held else "content never accepted"))


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
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--chain-only", action="store_true", help="check the ledger alone, read no files")
    scope.add_argument("--provenance", metavar="PATH",
                       help="report on one phone path instead: the device it came from, every line that "
                            "accepted it, where each accepted version lies and whether it still holds "
                            "that content, and whether the heads given vouch for those lines")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    arguments = parser.parse_args(argv)

    if not os.path.isdir(os.path.join(arguments.device, ".filum")):
        parser.error(f"{arguments.device} holds no .filum directory")

    if arguments.provenance is None:
        report = verify(arguments.device, arguments.head, arguments.chain_only)
    else:
        report = provenance(arguments.device, arguments.provenance, arguments.head)
    if arguments.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        if arguments.provenance is not None:
            print(describe_device(report["device"]))
        print(f"ledger: {report['entries']} entries, chain {report['chain']}")
        for head in report["heads"]:
            span = head.get("lines")
            print(f"head {head['n']}: {head['result']}" + (f", lines {span[0]}-{span[1]} as witnessed" if span else ""))
        if arguments.provenance is not None:
            print_provenance(report)
        elif not arguments.chain_only:
            print(f"files: {report['files']}, matching the ledger: {report['matching']}; versions: {report['versions']}")
        for problem in report["problems"]:
            print(f"PROBLEM {problem}")
        print("OK" if not report["problems"] else f"{len(report['problems'])} problem(s)")
    return 0 if not report["problems"] else 1


if __name__ == "__main__":
    sys.exit(main())
