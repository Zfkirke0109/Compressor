# PERF-001 decision journal — foreground service + wake lock (2026-07-17)

Branch `feat/batch-foreground-service`. Adversarial 3-lens review (android-lifecycle w/ live
official-docs verification, source-path/idempotency, security/privacy) run against the committed
branch; findings triaged below. Verified each against source/docs before accepting.

## ACCEPTED and fixed
1. **FGS type API threshold off-by-one (android-lifecycle, should-fix).** mediaProcessing and its
   `FOREGROUND_SERVICE_MEDIA_PROCESSING` permission FIRST EXIST on **API 35** (Android 15), not 34.
   Guarding at `SDK_INT >= 34` would make Android 14 request a type whose permission the OS lacks →
   SecurityException (caught → stopSelf → batch unprotected in background), the exact failure this
   PR prevents. My own lint output corroborated it ("requires API level 35"). Fixed: mediaProcessing
   only `>= 35`; dataSync for API 29-34 (its `FOREGROUND_SERVICE_DATA_SYNC` permission is real on 34
   and declared). Corrected the comment.
2. **Unhandled API-35 `onTimeout(startId, fgsType)` (android-lifecycle, should-fix).** API 35 delivers
   the FGS timeout via the two-arg overload; only the one-arg (API 34) was overridden, so a 35+
   timeout would hit the no-op super → fatal RemoteServiceException. Fixed: both overloads delegate to
   a single clean stop (stopForeground + stopSelf). A real batch finishes far inside the 6h cap; this
   is a safety backstop.
3. **Asymmetric begin()/end() bracket (source-path AND security, both flagged — should-fix).**
   `begin()` sat ~86 lines before the `try` whose `finally` calls `end()`; a throw in the setup region
   would leak the FGS + wake lock. Fixed: `begin()` moved to the FIRST statement inside the try, and
   `end()` moved to the FIRST statement in the finally — a symmetric bracket no setup/encode/terminal
   throw can leak. Only cheap pre-try setup (cache clear, state init) now runs before protection, and
   nothing expensive.
4. **Tests did not exercise concurrency (source-path, nice-to-have).** The AtomicBoolean/compareAndSet
   was only tested sequentially. Added `concurrentBeginsFireStartExactlyOnce` (32 threads released via
   a CyclicBarrier) proving exactly one start/acquire under a real race.
5. **Lint hygiene on my new file.** Removed an ObsoleteSdkInt (`>= N` == minSdk 24) guard; suppressed
   the (benign, guarded) InlinedApi on the API-35 constant with an explanatory annotation.

## CONFIRMED GOOD (reviewers tried to refute, could not)
- startForeground called synchronously as the first action in onStartCommand (meets the attach
  deadline). dataSync 3-arg startForeground correctly gated `>= Q`.
- POST_NOTIFICATIONS denied on 33+: FGS still elevates priority; app honest + usable (manifest comment
  accurate).
- Wake lock non-leaking: PARTIAL only (screen never forced), setReferenceCounted(false), single
  bounded acquire, isHeld-guarded release, @Volatile + CAS idempotency. WAKE_LOCK declared, used.
- ForegroundServiceStartNotAllowedException swallowing is defensible (batch runs independently; begin
  fires while foreground; Log.w keeps observability). No user-actionable recovery exists.
- START_NOT_STICKY correct (batch state lives in the ViewModel; nothing to resume). Service starts no
  work, holds only application context, adds no exported surface, no network/dependency, clean logs.

## Not addressed here (out of scope, noted)
- Wake-lock leak-guard bound (3h) vs FGS cap (6h) envelopes differ — intentional; independent bounds,
  end() releases deterministically. No correctness impact.
- Pre-existing lint: 60 baseline errors on main (e.g. `MediaCodecInfo#isSoftwareOnly` NewApi at
  BatchCompressorViewModel.kt:1768) — NOT introduced by this PR; CI is green on main with them, so CI
  does not gate lintDebug. Left untouched (do not bundle unrelated cleanup).

## Remaining gate before merge
On-device lifecycle validation on the S23 (batch survives backgrounding + screen-off; notification
present; service stops on completion) via the CI-signed artifact, with APK signer verified against the
expected shared SHA-256. A foreground-service reliability fix is not asserted working until observed.
