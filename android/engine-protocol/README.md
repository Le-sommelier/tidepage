# :engine-protocol

Wire format shared by the client (`:engine-impl`, `RemoteEngine`) and the engine service.
Pure Kotlin/JVM, no Android imports, depends only on `:engine-api` and coroutines. Package
`app.pdfa.engine.protocol`.

## Primitives (`Wire.kt`)

Little-endian. `u8`, `bool` (0/1 only), `i32`, `i64`, `f32`/`f64` (raw IEEE bits, NaN preserved).
`bytes` = i32 length + data; `string` = i32 UTF-8 byte length + data; list = i32 count + items;
optional = u8 presence (0/1) + value; enums = u8 ordinal (frozen, new entries go last).

Caps (`WireLimits`): blob 512 MiB, string 64 MiB, list 1M items. A reader checks a length against its
cap AND against the bytes left before allocating, and a list count against `count * minItemBytes`.
Every malformed input raises `WireException` and nothing else. Parsers call `finish()`: trailing
bytes are an error.

## Frames (`Framing.kt`)

```
Request  = u8 version(=2) | i64 requestId | i32 opcode | i32 deadlineMs | bytes payload   (21-byte header + payload)
Response = u8 version(=2) | i64 requestId | u8 status (0 OK, 1 ERROR) | bytes payload     (14-byte header + payload)
```
`deadlineMs` is the client's deadline relative to receipt (0 = none). `Request.encode()/decode()`,
`Response.encode()/decode()`; for big frames `Request.writeTo(OutputStream)` and
`Request/Response.readFrom(InputStream)` stream without an intermediate full-size copy (the payload is
allocated once, after its length is validated). The client picks request ids (unique, increasing
within a process generation).

PING result = `i32 protocolVersion | i64 bootId | i32 pid`. `bootId` is random, fixed for one service
process; the client checks it CHANGES across every restart (same id = it attached to the old process:
retry once, then the engine degrades).

## Service-side watchdog contract (a hung engine must die by itself)

The client kill (unbind) is not trusted. The service wraps its dispatcher in `ServiceWatchdog` (pure
Kotlin, injectable clock/ticker/`killSelf`; `EngineServer` already calls it):
1. `onRequest(id, deadlineMs)` for EVERY request on receipt, before any work.
2. `onResponseWritten(id)` once the reply (and tile pixels) were fully written to the transport
   (`EngineServer.responseWritten`).
3. Overdue = `now > receivedAt + deadlineMs + 2 s grace` and not written: the ticker (every 250 ms)
   calls `killSelf()` exactly once. The grace lets the client's typed Timeout normally win.
4. `onCancel(id, cooperative)`: for NON-cooperative ops (MERGE, SPLIT, IMAGES_TO_PDF, SAVE_FULL) a
   500 ms fuse is armed; if the reply is not written by then, `killSelf()`.
5. `killSelf` is supplied by the service: `Process.killProcess(Process.myPid())` or
   `Runtime.getRuntime().halt(137)`. Start the watchdog (`start()`) at service creation.
6. **`call()` must dispatch off the binder thread immediately.** oneway calls on a node are serialised:
   a blocking handler also blocks `cancel` and PING (and so the watchdog's cancel fuse).

## Ops and payloads

Helpers are in `EngineWire`: `<op>Args(...)` / `parse<Op>Args(payload)` build and parse a request
payload; `<op>Result(...)` / `parse<Op>Result(payload)` build and parse an OK response payload.
Ops that share a shape share the helper. Every row has both directions.

| Op (code) | Args helper (parsed type) | Result helper (parsed type) |
|---|---|---|
| OPEN (1) | `openArgs(bytes, password?)` (`OpenArgs`) | `openResult(handle)` (`Long`) |
| CLOSE (2) | `handleArgs` (`Long`) | `unitResult` |
| META (3) | `handleArgs` | `metaResult` (`DocMeta`) |
| PAGE_INFOS (4) | `handleArgs` | `pageInfosResult` (`List<PageInfo>`) |
| RENDER_TILE (5) | `renderTileArgs(h, TileRequest)` (`RenderTileArgs`) | `renderTileResult(TileInfo)`; **pixels out of band** |
| SEARCH_PAGE (6) | `searchPageArgs` (`SearchPageArgs`) | `searchPageResult` (`List<Rect>`) |
| SELECT_TEXT (7) | `selectTextArgs` (`SelectTextArgs`) | `selectTextResult` (`TextSelection?`) |
| OUTLINE (8) | `outlineArgs` (`OutlineArgs`) | `outlineResult` (`OutlinePage`) |
| LINKS (9) | `linksArgs` (`LinksArgs`) | `linksResult` (`List<PageLink>`) |
| STATE (10), UNDO (16), REDO (17), CONFIRM_SAVED (24), REMOVE_PROTECTION (26) | `handleArgs` | `editStateResult` (`EditState`) |
| ROTATE (11) | `rotateArgs` (`RotateArgs`) | `editStateResult` |
| DELETE (12), DUPLICATE (14) | `pagesArgs` (`PagesArgs`) | `editStateResult` |
| MOVE (13) | `moveArgs` (`MoveArgs`) | `editStateResult` |
| INSERT_BLANK (15) | `insertBlankArgs` (`InsertBlankArgs`) | `editStateResult` |
| ADD_MARKUP (18) | `addMarkupArgs` (`AddMarkupArgs`) | `editStateResult` |
| LIST_FIELDS (19) | `handleArgs` | `listFieldsResult` (`List<FieldInfo>`) |
| SET_FIELD (20) | `setFieldArgs` (`SetFieldArgs`) | `editStateResult` |
| RESET_FORM (21) | `resetFormArgs` (`ResetFormArgs`) | `editStateResult` |
| SAVE_INCREMENTAL (22), SAVE_FULL (23) | `handleArgs` | `bytesResult` (`ByteArray`) |
| SAVE_PROTECTED (25) | `saveProtectedArgs` (`SaveProtectedArgs`) | `bytesResult` |
| EXTRACT (27) | `pagesArgs` | `bytesResult` |
| SPLIT (28) | `splitArgs` (`SplitArgs`) | `splitResult` (`List<SplitPart>`) |
| MERGE (29) | `mergeArgs(List<MergeSource>)` | `bytesResult` |
| IMAGES_TO_PDF (30) | `imagesToPdfArgs(images, dpi?)` (`ImagesToPdfArgs`) | `bytesResult` |
| DETECT_DOCUMENT_CORNERS (31) | `detectDocumentCornersArgs(ImageFrame)` | `detectDocumentCornersResult` (`Quad?`) |
| RECTIFY_AND_CLEAN (32) | `rectifyAndCleanArgs` (`RectifyArgs`) | `rectifyAndCleanResult` (`ImageFrame`) |
| PING (33) | `emptyArgs` | `pingResult(version)` (`Int`) |
| SHUTDOWN (34) | `emptyArgs` | `unitResult` |

`handle` is the server's own `i64` handle id (`DocHandle.id`). Model types: one
`WireWriter.writeX` / `WireReader.readX` pair each in `ModelCodec.kt`.

Server push (not a Response): `EngineWire.progress(opId, fraction)` / `parseProgress`, delivered to
the client as it happens (`IEngineCallback.onProgress`).

## Errors (`ErrorCodec`)

ERROR response payload = `u8 code | string detail`. Codes: 1 InvalidPdf, 2 PasswordRequired,
3 WrongPassword, 4 TooLarge, 5 InvalidArgument, 6 Render, 7 Panic, 8 Unsupported, 9 Io,
10 Cancelled, 11 NotPermitted, 12 Timeout, 13 EngineDied. **12 and 13 are client-only: a server
must never send them** (the client treats them as a protocol violation and restarts the engine).
`ErrorCodec.encode(EngineError)` / `decode(bytes)`.

## Tiles (`TileInfo`, `TileBuffer`)

A RENDER_TILE OK payload is only `TileInfo(width, height, size, transferId)`. The pixels
(premultiplied RGBA8, row-major, exactly `size = width*height*4` bytes) travel out of band: the
transport hands the client a `TileBuffer` (id + bytes) next to the `Response`. The client rejects
the reply (EngineError.Render and a process restart) when `size != request.width*height*4`, the
geometry differs from the request, the buffer is missing or short, or the transfer ids differ.
JVM tests use `HeapTileBuffer`; the Android transport uses a pipe (see `:engine-impl`).

## What the server must do

1. **Never trust lengths.** Use `EngineWire.parse*` only; they throw `WireException`. Answer a
   malformed request with an ERROR (`InvalidArgument`), do not crash.
2. **Answer every request id exactly once**, OK or ERROR, even after a failure. Echo the id.
3. **Errors use the error codec.** Map `PdfError` one to one; unexpected throwables become `Panic`
   with a fixed text (never document content, never passwords).
4. **Serialise calls per handle** (the client sends one at a time per handle, but a cancelled call
   may still be running when the next arrives).
5. **Stale handles.** Handle ids must never be reused across process lifetimes: prefix every wire
   handle with a per-boot random 31-bit nonce in the high 32 bits (`id = nonce << 32 | counter`) and
   reject a handle whose nonce differs with `InvalidArgument`. `EngineServer` does this by default
   (`handleNonce`), so a stale handle is rejected, never served by another document. A fresh process
   otherwise hands the ids 1, 2, ... to different documents.
6. **Tile transfer**: allocate `size` bytes, deliver them under `transferId`, reply with `TileInfo`.
7. Honour a cancel request best-effort; honour SHUTDOWN by replying then exiting.

## Reference dispatcher

`EngineServer(api: EngineApi)` already does all of the above for a given `EngineApi`:
`handle(Request): ServerReply(response, tilePixels?)`, plus `progress: Flow<Progress>`. The service can wrap
its UniFFI-backed `EngineApi` in it and write only the Binder plumbing. The JVM loopback test in
`:engine-impl` runs the real codec through it against `:engine-fake`.
