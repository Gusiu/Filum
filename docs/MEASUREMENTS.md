# Measurements

What the transfer settings rest on: numbers taken on real hardware with the measurement bench
of the `qa` build (architecture §7). They describe one phone, one computer and one home
network - read them for proportions and trends, not as constants.

## Setup

| | |
|---|---|
| Phone | Samsung Galaxy S24 Ultra, Android 16, on USB power, in power-saving mode, VPN off |
| Network | Wi-Fi 6 at 5 GHz, phone link 1441 Mb/s, RSSI -32 to -43 dBm; the computer on the same Wi-Fi, so every byte crosses the air twice |
| Server | Release build on Windows 11, NVMe SSD; a test instance with its own key, backup root and port, at idle priority, restarted for every change of its settings |
| Repetitions | three per point; the tables give the mean and the standard deviation |

The bench lives in the `qa` build. A cycle goes through the real `SyncRunner` - the route
race, the Room queue, OkHttp, the foreground service with a Wi-Fi lock - over a set of
generated files with pseudo-random content and a fixed modification time. Each run moves the
set under a fresh directory, so the server stores everything anew. Commands are string extras
for `BenchActivity`, and each one appends a line of JSON to `files/bench/results.jsonl`:

```bash
adb shell am start --user 0 -n pl.wspa.filum.qa/pl.wspa.filum.app.bench.BenchActivity \
    --es command generate --es set s1m --es count 256 --es size 1048576
adb shell am start --user 0 -n pl.wspa.filum.qa/pl.wspa.filum.app.bench.BenchActivity \
    --es command sync --es set s1m --es run r001 --es parallel 1
adb exec-out run-as pl.wspa.filum.qa cat files/bench/results.jsonl
```

The server's share of a cycle comes from its own log: Kestrel's request durations - from
the start of a request to its response, receiving the body included - summed over the cycle.

## 1. Bundles (ADR-008)

One transfer at a time. Separate requests: `smallFileThreshold` 0. Bundles: 8 MB, which puts
every file up to 4 MB in a bundle.

| File size | Files | Separate requests: s | MB/s | Bundles: s | MB/s | Speed-up |
|---|---|---|---|---|---|---|
| 4 KB | 4096 | 138.8 ± 15.5 | 0.1 | 24.2 ± 1.4 | 0.7 | 5.7× |
| 16 KB | 2048 | 67.3 ± 5.3 | 0.5 | 12.2 ± 0.1 | 2.6 | 5.5× |
| 64 KB | 1024 | 36.1 ± 3.0 | 1.8 | 7.6 ± 0.2 | 8.4 | 4.7× |
| 256 KB | 512 | 23.6 ± 2.8 | 5.5 | 7.6 ± 0.4 | 16.9 | 3.1× |
| 1 MB | 256 | 17.7 ± 1.0 | 14.5 | 11.6 ± 0.1 | 22.1 | 1.5× |
| 4 MB | 64 | 12.2 ± 0.1 | 21.1 | 11.6 ± 0.4 | 22.0 | 1.0× |
| 16 MB | 16 | 11.1 ± 1.1 | 23.2 | | | |
| 64 MB | 4 | 12.4 ± 0.7 | 20.7 | | | |

A file sent on its own costs 22-27 ms beyond the server's handling of it, whatever its size up
to 1 MB: the turnaround of one request - the response, the queue update, the next request,
two trips across the network. In a bundle that falls to 3-5 ms a file. The gain shrinks as
files grow and is gone at 4 MB, so the server offers 1 MB as the threshold.

## 2. Transfers at once

| Set | Bundles | 1 at once: s; MB/s | 3: s; MB/s | 8: s; MB/s |
|---|---|---|---|---|
| 64 KB × 1024 | no | 36.2 ± 1.3; 1.8 | 11.2 ± 0.4; 5.7 | 9.8 ± 0.1; 6.5 |
| 1 MB × 256 | no | 18.4 ± 0.9; 14.0 | 11.3 ± 0.1; 22.7 | 12.8 ± 0.4; 20.0 |
| 16 MB × 16 | no | 10.9 ± 0.5; 23.5 | 13.7 ± 0.3; 18.6 | 14.7 ± 0.5; 17.4 |
| 4 KB × 4096 | yes | 25.3 ± 0.5; 0.6 | 25.1 ± 0.8; 0.6 | 25.4 ± 0.9; 0.6 |

Small files sent one per request gain almost in proportion: the turnaround is waiting, and
waits overlap. Large files lose a fifth of their throughput - the requests do run at the same
time, but each takes proportionally longer. Bundled small files gain nothing, because a batch
of 256 queue entries makes a single bundle and transfers run in parallel only within a batch.
With small files in bundles, more transfers at once would speed up nothing that matters and
slow down the large files, so `parallelTransfers` stays at one.

## 3. SHA-256 (thorough mode, ADR-016)

| Source | Data | MB/s |
|---|---|---|
| a buffer in memory - the processor alone | 1024 MB | 1649 ± 2 |
| a generated set, most likely from the page cache | 256 MB | 1200 ± 19 |
| the user's own files, mostly read from flash | 2586 MB | 685 ± 17 |

Thorough mode reads the whole library on every scan. At 685 MB/s that is 15 s for 10 GB,
2.5 min for 100 GB and 12.5 min for 500 GB.

## 4. Finding the files

| Method | Files | Time |
|---|---|---|
| a full walk of the shared storage | 8024 | 0.76-1.55 s |
| MediaStore, the full listing | 6105 | 0.16-0.27 s |
| MediaStore, changes since a stored generation (none) | 0 | 3-5 ms |

Asking MediaStore what changed is some 200 times faster than a walk, but MediaStore does not
index every file - hence the delta cycle reads changes from MediaStore and the full cycle
walks the tree.

## 5. The ledger

- Appending stays flat as the ledger grows: 2048 files of 4 KB took 14.9-17.5 s a run while
  the ledger grew from 16,000 to 33,000 lines.
- Checking a ledger of 133,468 lines, as a first witness does (protocol §9.5), takes 1.8-2.3 s
  on the phone. The witness after a cycle of 2048 files reads one page and takes 0.42 s.
- The last manifest batch of a full cycle takes 0.5-0.8 s on the server against 70-90 ms for
  the others: the orphan report walks the device's directory, 129,000 files at the time.

## 6. HTTP/2 flow-control windows

A large file goes up in one stream, and the phone may have no more of it in flight - sent, but
not yet read by the server and acknowledged - than the flow-control window. Kestrel's defaults
on .NET 9 are 768 KB a stream and 1 MB a connection. Two series of 16 files of 16 MB, each file
a request of its own, with the server restarted for every change of its windows:

- the first on a quiet night, in the setup above, the windows in blocks, three runs a point;
- the second in the evening, with the phone's power saving off, on channel 64 at 2401 Mb/s,
  with a ping from the computer to the idle phone averaging 131 ms, the windows and the number
  of transfers alternating and their order turning every round, five runs a point.

| Stream / connection window | At once | First series: s; MB/s | Second series: s; MB/s | Server's handling of a file, ms: first; second |
|---|---|---|---|---|
| 768 KB / 1 MB (default) | 1 | 12.0 ± 1.1; 21.4 | 65.9 ± 3.9; 3.9 | 541; 3523 |
| 1 MB / 4 MB | 1 | 11.5 ± 0.4; 22.2 | 56.8 ± 6.7; 4.5 | 498; 3141 |
| 8 MB / 32 MB | 1 | 8.6 ± 0.2; 29.7 | 27.7 ± 5.4; 9.2 | 308; 1352 |
| 768 KB / 1 MB (default) | 3 | 14.4 ± 0.2; 17.8 | 78.0 ± 2.7; 3.3 | 2131; 12869 |
| 1 MB / 4 MB | 3 | 14.3 ± 0.2; 17.9 | 44.4 ± 19.2; 5.8 | 2066; 6963 |
| 8 MB / 32 MB | 3 | 14.4 ± 0.3; 17.8 | 56.3 ± 28.4; 4.5 | 2081; 8463 |

The 8 MB window is the one that matters: one file at a time went 1.4 times faster on the
quiet night and 2.4 times faster in the evening, when the default window took 5.5 times as long.
A 1 MB window is barely more than the default and gives barely more. Three transfers at once
gain nothing consistent, and in the evening their times scattered widely. At 30 MB/s the
default window covers some 25 ms of a transfer and the 8 MB one more than a quarter of a
second. The series do not tell whether the phone waited on the network or on the server's
reading, nor why the evening was slower; the larger window bridges delays on either side, so
the server sets 8 MB a stream and 32 MB a connection.
