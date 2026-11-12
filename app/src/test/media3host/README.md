# Running Media3's MP4 parser on the build machine

`Media3ParserParityTest` holds `Mp4EditTimeline`, the app's reading of which samples Media3 keeps
from a source, against Media3's own parser. It runs the `Mp4Extractor` class the app ships, from
the build's `media3-extractor` artifact, on the JVM.

That class links against a handful of Android framework classes whose SDK stub versions throw
when called. The files here are the smallest replacements that let it run, and the probe that
drives it:

| File | What it stands in for |
| ---- | --------------------- |
| `hostprobe/Media3Mp4Probe.java` | The probe: feeds a file to `Mp4Extractor` and returns the times of the video samples it emits. |
| `android/util/Pair.java` | The parser returns an edit list as a `Pair`. |
| `android/util/SparseArray.java` | The extractor's track map. |
| `android/util/Log.java` | Parser warnings (discarded). |
| `android/text/TextUtils.java` | `isEmpty` and `equals`, used while building a `Format`. |
| `android/os/Build.java` | Identity strings and an SDK level; the stub's are null. |
| `android/app/ActivityManager.java` | "Not a test harness". |

They are **not** part of any source set. Nothing compiles them into the app or onto the
unit-test classpath: the test compiles them into a temporary directory and loads them, with the
Media3 jars, in a class loader of their own, so no other test sees a replaced Android class.

This is a host observation of the parser alone. The platform extractor, the codecs, the muxer
and the metadata remux run only on a device (`EditListTimelineDeviceTest`).
