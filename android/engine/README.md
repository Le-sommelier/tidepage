# :engine

The isolated engine process. Namespace and package `app.pdfa.engine.service`.

| File | Role |
|---|---|
| `EngineService` | Bound service, `android:process=":engine"`, `isolatedProcess="true"`, not exported, no permissions. Binder stub for `IEngineService`; wraps `EngineServer(UniffiEngine)` with the handle nonce on. |
| `UniffiEngine` | `EngineApi` in process: handle table, argument checks, encryption gate (`FeatureFlags.encrypt`, off), error funnel. Blocking. |
| `NativeEngineBackend` / `NativeSession` | The seam to the native engine, in `:engine-api` types. |
| `ffi/UniffiBackend`, `ffi/Mapping` | The only package that imports `uniffi.*` (record and `PdfException` mapping). |
| `src/main/java/uniffi/pdfa_ffi/pdfa_ffi.kt` | Generated; never edit. `scripts/build-android.sh` rewrites it and `jniLibs/arm64-v8a/libpdfa_ffi.so` (git-ignored). |
| `src/debug/.../HangingBackend` | Debug only: `open` with password `__hang__` or `merge` with a source named `__hang__` never returns. `assembleRelease` runs `verifyReleaseHasNoHangHook`, which fails if the string or class is in the release AAR. |

## How a request flows

1. `call()` on a Binder thread: decodes an inline frame (at most 64 KiB) or hands the pipe to the
   blob reader pool; arms the watchdog; queues the job. No engine code and no pipe I/O on Binder threads.
2. A worker (4 threads, 8 MiB stacks, queue of 64; a full queue answers `Io("engine busy")`) runs
   `EngineServer.handle`. PING and SHUTDOWN use their own thread so they never wait behind engine work.
3. The reply leaves once (`Job.answered`): inline, or header + payload streamed into a socket pair,
   tile pixels into a second one. The worker waits for the writers (bounded by the request deadline,
   30 s when there is none), then tells the watchdog the reply was written.
4. `cancel(id)`: a queued job is answered `Cancelled` without running; a running non-cooperative op
   (merge, split, imagesToPdf, saveFull) arms the 500 ms fuse.
5. Death: the watchdog, SHUTDOWN and `onDestroy` (last client unbound) call `killSelf`:
   `Process.killProcess(myPid())`, then `Runtime.halt(137)` after 1 s.

## Tests

- JVM: `./gradlew :engine:testDebugUnitTest` (fake backend; real mapping code).
- Device: `./gradlew :engine:connectedDebugAndroidTest` against the real service and library
  (`EngineServiceDeviceTest`). It inspects and kills other processes with `su 0`, so it needs a
  userdebug emulator image. Evidence lines are tagged `ENGINE-PROOF` in logcat.

## Known gaps

- No per-process memory cap (PLAN mentions one): Android offers no public `setrlimit`; an
  over-allocation ends in the system low-memory killer, which the client treats as `EngineDied`.
- `UniffiEngine.events` is empty: the native engine reports no progress yet, so nothing is pushed
  through `IEngineCallback.onProgress`.
- Only arm64-v8a is built.
