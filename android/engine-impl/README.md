# :engine-impl

`RemoteEngine`: the `EngineApi` client for the engine that runs in a separate process
(`android:process=":engine"`, isolated). All supervision logic is in `RemoteEngine` and is tested on
the JVM against a scriptable loopback transport. The only Android code is the thin
`binder/BinderEngineTransport` and `binder/BlobPipes`.

```kotlin
val engine = RemoteEngine(BinderEngineTransport(ctx, ComponentName(ctx, EngineService::class.java)),
    EngineConfig(), recoveryProvider)
val h = engine.open(bytes, password, docKey = "doc-123")   // extension, see below
```

## Supervision policy

| Call class | Ops | Deadline | On deadline |
|---|---|---|---|
| open | OPEN | 15 s | kill, restart, reopen all, `Timeout` |
| tile | RENDER_TILE | 2 s | same |
| text | META, PAGE_INFOS, SEARCH_PAGE, SELECT_TEXT, OUTLINE, LINKS, LIST_FIELDS | 10 s | same |
| edit | STATE, ROTATE, DELETE, MOVE, DUPLICATE, INSERT_BLANK, UNDO, REDO, ADD_MARKUP, SET_FIELD, RESET_FORM, CONFIRM_SAVED, REMOVE_PROTECTION, CLOSE | 5 s | same |
| save | SAVE_INCREMENTAL, SAVE_FULL, SAVE_PROTECTED, EXTRACT | 60 s | same |
| files | MERGE, SPLIT, IMAGES_TO_PDF | 120 s | same |
| scan | DETECT_DOCUMENT_CORNERS, RECTIFY_AND_CLEAN | 10 s | same |
| ping | PING | 2 s | same |

All are `EngineConfig` fields; `deadlineOverrides[op]` wins. Deadlines are enforced client side
with `withTimeout` around `transport.call`. Restart/recovery steps (`transport.restart()`,
`RecoveryProvider.latestBytes`) have their own deadline (`recoveryStepMs`, 15 s).

Other failures:

| Event | Action | Error to the caller |
|---|---|---|
| process dies by itself (`transport.deaths`, current generation only) | restart + reopen | in-flight call: `EngineDied` |
| transport throws (IO, binder) | restart + reopen | `EngineDied` |
| protocol violation (bad id, undecodable payload, client-only error code, bad tile) | restart + reopen | tile: `Render`; others: `Panic` ("protocol error ...") |
| server ERROR response | none | the decoded `EngineError` |
| caller cancelled a tile | drop (shared fetch cancelled when no waiter is left); best-effort `transport.cancel` | none, never a kill |
| caller cancelled MERGE / SPLIT / IMAGES_TO_PDF (`killOnCancel`), or SAVE_FULL of a doc >= 32 MiB | kill + restart + reopen (in the engine scope, not in the cancelled caller) | `CancellationException` |
| other cancellation | best-effort `transport.cancel` only | `CancellationException` |
| input over 256 MiB, request over 512 MiB, render over 2^26 px | refused before sending | `TooLarge` |
| call after `close()` | none | `EngineDied` |

A call that fails because ANOTHER call restarted the engine gets `EngineDied`; a result that
arrives after the epoch changed is discarded (`EngineDied`) because the handle may have met a
different document in the new process.

**Restart storm guard.** More than `maxRestartsPerWindow` (3) restarts within `stormWindowMs`
(60 s) put the engine in *Degraded*: the process is killed, not restarted, and every call fails fast
with `EngineDied` for `degradedCooldownMs` (30 s). The first call after the cool-down restarts the
engine and reopens documents. A failed or hung `restart()` also degrades.

**Serialisation.** One mutex per handle. Different handles and stateless calls (merge, imagesToPdf,
scan) overlap. Identical in-flight tile requests (handle, page, scale, rect, preview) share one
transport call; each waiter gets its own `ByteBuffer` view.

## Recovery semantics

`RecoveryProvider.latestBytes(docKey)` / `passwordFor(docKey)`. After a restart every open document
is reopened and the client `DocHandle` stays valid (client handle -> current remote handle).

- `engine.open(bytes, password, docKey)` (extension on `RemoteEngine`): no copy is kept; recovery
  calls `latestBytes(docKey)` (journal, else original). Password = `passwordFor(docKey)`, else the
  one given at open.
- `engine.open(bytes, password)` (the `EngineApi` method) / `docKey = null`: the original `bytes`
  array is kept by reference (do not mutate it) when it is at most `maxInMemoryRecoveryBytes`
  (32 MiB); recovery reopens those. Larger: not recoverable.
- No bytes (null from provider, or not recoverable) or the reopen fails: the document is marked dead;
  its calls throw `EngineDied`, other documents are unaffected. A document whose reopen hangs or
  kills the engine is dead too, and the engine restarts again for the rest (counts toward the storm).
- A reopened document starts with an empty undo history, and its `EditState.dirty` is reported
  `true` until `confirmSaved` (so unsaved work is never presented as saved).
- `SafeModeRestarted(atEpochMs, reason)` is emitted once per recovery, after documents are reopened
  (or `degraded: ...` when no restart happened). `Progress` events are forwarded from the server.
- `close()`: marks the engine closed, cancels supervision, `transport.kill`. Never blocks;
  in-flight calls fail with `EngineDied` because the transport must fail pending calls on kill.
- Passwords, document bytes and content are never logged or put in exception messages.

## Review-round additions

- **Deadline to the service**: every Request carries the enforced `deadlineMs`; the service watchdog
  (engine-protocol README) kills a hung engine by itself. After every restart the client PINGs and
  requires a NEW `bootId` (retry once, else degrade): unbind alone is never trusted to kill.
- **Expected generation**: `transport.call(request, deadline, expectedGeneration)` fails with
  EngineDied BEFORE sending when the generation differs (and never auto-starts a process for a
  generation-bound call). Each document binding records its generation. With the server's handle
  nonce an old handle is rejected with InvalidArgument anyway.
- **Events**: `SafeModeRestarted` and `EditsLost(handle, atEpochMs, originalRestored)` (one per
  reopened document; `originalRestored` = unkeyed doc, or `RecoveryProvider.restoredFromOriginal`)
  live in a flow that replays the last 32 to late collectors; `Progress` is separate, drop-oldest.
- **Timers**: storm window, cool-down and recovery cap use the injected monotonic clock
  (`SystemClock.elapsedRealtime` on Android); `clock` is only the event timestamp. A recovery is
  capped at `maxRecoveryMs` (60 s): documents not reopened by then are dead. A caller whose own
  deadline fired waits for the recovery (up to `recoveryStepMs` per step); a cancelled kill-on-cancel
  caller is released immediately.
- **Transport errors**: `TooLarge`/`Io` from the transport are returned as is (no restart);
  `Render`/`Panic` (protocol violations) restart; anything else is EngineDied + restart.
- **Memory** (client, 128 MiB document): the caller's array (1) + the request payload built by
  `EngineWire` (2, presized, no doubling); the pipe write streams from (2) with no third copy. The
  result is read straight into one array sized from the validated frame header; tiles are allocated
  only after their announced size equals `width*height*4` of the request. Peak <= 2 copies on top of
  the caller's array.
- **Pipes** (`BlobIo.kt`, JVM-tested with fakes): the local read end is closed right after the oneway
  transaction; the writer is aborted at the call deadline (`shutdown(2)` on a socket pair unblocks a
  stuck write) and its thread, fd and buffer are released; replies are read on a bounded executor
  with a deadline, never on the binder thread; a malformed reply fails the pending calls with
  Render/Panic (restart) instead of being dropped.
- **Binding**: API 29+ `bindIsolatedService(..., "engine_<generation>")` = a fresh process per
  generation; API 26-28 plain `bindService` + the boot-id check. The generation is incremented when
  the Connection is created, under the lock; the callback is per Connection and a reply is accepted
  only from the Connection its request was sent on; a dead bind completes `ready` at once.
- **Acceptance tests for the service integration (need the real service, not written here)**: hang op -> the
  service pid changes and the new `bootId` differs within deadline + grace; open fd count of the app
  and engine process returns to baseline after 50 failed big calls; kill -9 of the engine mid-tile
  is recovered; `bindIsolatedService` instance names start separate processes.

## Binder transport: what it does

AIDL (`src/main/aidl`): `IEngineService { oneway call(frame, frameBlob, callback); oneway cancel(id) }`,
`IEngineCallback { oneway onResponse(frame, frameBlob, tile); oneway onProgress(frame) }`.

`BinderEngineTransport(context, serviceComponent)`:
1. binds the service lazily (first call or `restart()`), a new `ServiceConnection` per generation;
2. sends `Request.encode()` inline if <= 64 KiB, otherwise through a pipe (`BlobPipes.feed`);
3. completes the pending call when `onResponse` arrives (large frames and tile pixels are read from
   pipes: Binder's ~1 MiB transaction limit rules out inline);
4. on `onServiceDisconnected`, `onBindingDied`, `onNullBinding` or `linkToDeath`: fails all pending
   calls with `EngineDied`, unbinds, emits `DeathEvent(generation, reason)`;
5. `kill()` fails pending calls and unbinds; `restart()` = kill + generation++ + bind.

The service side implements `IEngineService.Stub`, wraps its `EngineApi` in
`protocol.EngineServer`, and answers through `IEngineCallback`, using `BlobPipes.feed` for large
frames and tile pixels (it must write on its own thread).

## What is untested without a device

Everything in `binder/` (compiles and passes lint, never executed): bind/unbind behaviour, death
detection timing, pipe transfers, large-frame handling, callback threading.
Specifically unverified assumptions:
- unbinding an `isolatedProcess` service makes the system kill the process promptly (the app cannot
  `Process.killProcess` an isolated UID); how fast a rebind starts a NEW process, not the dying one;
- `onServiceDisconnected` / `linkToDeath` fire on every OOM/abort/kill of the service process;
- oneway call ordering and Binder buffer use with the 64 KiB inline limit;
- the engine service honouring the server rules in `engine-protocol/README.md` (nonce handles, one
  answer per request, writing pipes on a separate thread);
- real-device latency of the tile pipe (a 2 MiB tile is copied twice).
Also not covered by JVM tests: real wall-clock behaviour (tests use virtual time).
