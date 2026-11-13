# Running Media3's MP4 parser and muxer on the build machine

Two unit tests run the Media3 classes the app ships, from the build's own `media3-extractor`,
`media3-muxer` and `media3-transformer` artifacts, on the JVM:

- `Media3ParserParityTest` holds `Mp4EditTimeline`, the app's reading of which samples Media3
  keeps from a source, against Media3's own `Mp4Extractor`.
- `Media3MuxerObservationTest` feeds the samples that extractor emits into Media3's
  `MuxerWrapper` and default muxer, the end of every Transformer export, and reads the file back:
  what happens to the first video timestamp, and what a copied AAC track keeps.

Those classes link against a handful of Android framework classes whose SDK stub versions throw
when called. The files here are the smallest replacements that let them run, and the probes that
drive them:

| File | What it is |
| ---- | ---------- |
| `hostprobe/Media3Mp4Probe.java` | Feeds a file to `Mp4Extractor` and returns the samples it emits. |
| `androidx/media3/transformer/Media3TransmuxProbe.java` | Writes those samples through `MuxerWrapper` and the default muxer. In Media3's package because `MuxerWrapper` is package-private. |
| `android/util/Pair.java` | The parser returns an edit list as a `Pair`. |
| `android/util/SparseArray.java` | The extractor's and the muxer wrapper's track maps. |
| `android/util/Log.java` | Keeps Media3's warnings for a test to read: it says in its log when it changes a timestamp. |
| `android/text/TextUtils.java` | `isEmpty` and `equals`, used while building a `Format`. |
| `android/os/Build.java` | Identity strings and an SDK level; the stub's are null. |
| `android/os/SystemClock.java` | A monotonic clock; the stub's is a native method. |
| `android/app/ActivityManager.java` | "Not a test harness". |

They are **not** part of any source set. Nothing compiles them into the app or onto the
unit-test classpath: `Media3Host` (in the unit tests) compiles them into a temporary directory
and loads them, with the Media3 jars, in a class loader of their own, so no other test sees a
replaced Android class.

A Media3 upgrade can break this in two ways, and both are the point. If Media3 behaves
differently, a test fails and names what changed. If its internals move (`MuxerWrapper`'s
constructor, a new Android class on the path), the probes stop compiling or running, and the
failure says which: update the probe or add a replacement here, then read what the tests report.

These are host observations of Media3 alone. The platform extractor and muxer, the codecs and
Transformer's threading run only on a device (`EditListTimelineDeviceTest`).
