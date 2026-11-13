# :storage (part 1)

All file I/O for documents. The engine only ever receives bytes. This module owns SAF `content://`
access, the working copy in app storage, and the verified copy-back.

## Layout

```
<rootDir>/
  tmp/<docId>/              imports in progress (swept at startup)
  work/<docId>/
    original.pdf            bytes copied at import (never changed by this module)
    manifest.json           uri, displayName, size, lastModified, sha256 (version 1, flat)
    staging.pdf             full copy of the new bytes; exists during a pending copy
    copy.pending            line 1: target URI; line 2: source SHA-256 observed before truncation
    staging.next            new bytes awaiting verify (transient)
    save_copy.pdf           Save a copy staging, kept only after a failed copy
    external-<ms>-<uuid>.pdf  external version kept when an overwrite was forced
    autosave.tmp            reserved for the autosave journal, not written here
    *.part                  temp files of atomic writes (swept)
```

## Public API

- `MAX_DOCUMENT_BYTES` = 128 MiB. The one size limit: import cap and in-memory save cap.
- `UriStreams`: `openInput`, `openOutputTruncating` (mode `wt`), `stat` (advisory only), and
  `documentKey(uri): String?` (null = cannot tell; the writer then refuses). `ContentResolverStreams(context)`
  is the Android implementation.
- `WorkspaceStore(rootDir, dispatcher = IO)`: `suspend importFrom(uri, streams, maxBytes = 128 MiB)`,
  `load(docId)`, `sweepTmp(): Int` (returns the number of entries it could not remove).
- `SafWriter(dispatcher = IO, maxInMemoryBytes = 128 MiB, copyTimeoutMs = 5 min)`. One instance per
  process, since the per-document lock map lives in the instance.
  - `saveBack(doc, bytes, streams, verify, overwriteIfSourceChanged = false)`
  - `saveCopyTo(doc, bytes, targetUri, streams, verify)`
  - `pendingCopyStatus(doc): PendingStatus` (Pending / NotPending / Unknown(detail)). Blocking, no lock.
  - `finishPendingCopy(doc, streams, overwriteIfSourceChanged = false)`
  - `exportStaging(doc, streams, targetUri)`: writes the full pending bytes to a new target. Keeps staging.
- Results carry explicit flags, so the UI never infers them:
  - `sourceMayBeTruncated`: the user's file may be partial now.
  - `stagingKept`: `staging.pdf` holds the full new bytes, and the pending state is in place.
  - `CopyResult` has `targetMayBeTruncated` and `stagingKept` instead.
- `detail` strings are fixed text or exception class names. They never contain URIs, paths, file names,
  exception messages or content.

## Pending state

**Pending = `staging.pdf` is a file AND `copy.pending` line 1 names this document's URI.** Only then is
`staging.pdf` the full copy. The marker's line 2 is the source's SHA-256 as observed right before the last
truncation, or the read-back hash after a failed write. A marker without staging, or staging without a
matching marker, is an orphan. The next save removes it and reports a failed removal. `pendingCopyStatus`
never guesses: an unreadable state is `Unknown`.

## Import

- The reported size is checked before reading. The bytes read must then equal the reported size. A
  provider whose SIZE column is stale cannot be imported; the import fails closed.
- The first 1024 bytes must contain `%PDF-` (offset 0 to 1019). The declared MIME type is ignored.
- Written to `tmp/<docId>/`, fsynced, then the directory is renamed into `work/`. Nothing appears under
  `work/` until it is complete.
- Cancellation is honoured while copying. After the commit point (the rename) the result is delivered even
  if the caller was cancelled, so a finished document is never orphaned.
- Blocking I/O runs on the injected dispatcher. Do not call it on the main thread.

## Save (`saveBack`)

Phase 1, cancellable, nothing truncated:
1. Hash the new bytes and write them to `staging.next` (fsync). Verify runs on a copy of the bytes, so a
   verify that mutates its argument cannot change the staged or hashed bytes.
2. Hash the source.
   - Not pending: the source must equal the manifest's SHA-256. A different hash is a `Conflict`, unless
     `overwriteIfSourceChanged`. With overwrite, the external version is first copied to
     `external-<ms>-<uuid>.pdf` (verified by hash, no replace, no `.part` left behind).
   - Pending: the source must be the state the interrupted copy left (the marker's hash, or a prefix of
     `staging.pdf`). Otherwise it is an external edit: `Conflict`, or a snapshot with overwrite. Staging is kept.
3. Promote `staging.next` to `staging.pdf`.

Phase 2, NonCancellable, after the decision:
4. Hash the source again. It must equal the hash from phase 2 (the step 2 result). Otherwise `Conflict`,
   and nothing is truncated. This closes the window between the snapshot and truncation.
5. Write `copy.pending` (line 2 = the hash from step 4). Truncate the source, copy staging into it, fsync.
   The copy runs on a worker thread under `copyTimeoutMs`.
6. Read the source back, counting bytes and hashing. The provider's SIZE is not used. A mismatch is
   `CopyFailed`, with the marker updated to the read-back hash. A read-back that cannot be completed, or that
   times out during read-back, is `Unverifiable`.
7. Write the manifest (new size, sha256; lastModified from a best-effort stat).
8. Remove the marker, then staging. Each removal is checked.

If the source already equals `staging.pdf` when a finish runs, the copy is skipped: only steps 7 and 8 run.

`sync()` and `close()` errors are recorded as warnings (class names only). The read-back decides.

## Failure matrix and UI obligations

Call `pendingCopyStatus(doc)` before showing the document state. `Pending` or `Unknown` means the source may
be partial, whatever the last result said.

| Result | sourceMayBeTruncated | stagingKept | UI |
|---|---|---|---|
| `Saved(doc, snapshot, warning)` | false | false | Use `doc`. If `snapshot` is set, tell the user the external version was kept. Show `warning` as a non-fatal note. |
| `SavedButManifestStale(doc, detail)` | false | true | The file holds the new bytes. The record does not. `doc` carries the updated manifest in memory. Offer Retry (`finishPendingCopy`), which records it without copying. |
| `VerificationFailed` | see flags | see flags | Nothing saved; the engine rejected the result. Keep the edits. If pending, the source is still partial: Retry first. |
| `Conflict` | false (pending: true) | false (pending: true) | The file changed outside the app. Offer: Save a copy; Overwrite (`overwriteIfSourceChanged = true`, which keeps the external version as a snapshot); or Reload from source (discards edits). If pending, `staging.pdf` is the only full copy: do not discard it. |
| `CopyFailed` | true | true | Do not say "saved". Offer Retry (`finishPendingCopy`). Keep the working copy. Do not discard the workspace while `copy.pending` exists. |
| `Unverifiable` | true | true | The save could not be confirmed. Offer Retry, which re-checks and re-copies. Never claim success. |
| `TooLarge` | see flags | see flags | Too large for the in-memory save (above 128 MiB). |
| `Io` | see flags | see flags | Retryable. Pending flags apply as above. |

Save a copy (`saveCopyTo`) and export (`exportStaging`):

| Result | Effect | UI |
|---|---|---|
| `Copied(size, sha256, warning, replacedSource)` | Target written and verified. | Report success for the new file. The original is unchanged, unless `replacedSource` is set: then the target was the original under another URI, and the UI reports the original as replaced. |
| `CopyFailed` (`targetMayBeTruncated = true`) | The target may be partial. | Tell the user the new file is incomplete. Offer retry or another target. |
| `Unverifiable` | Target written, not confirmed. | Do not claim success. Offer retry. |
| `VerificationFailed` / `TooLarge` / `Io` | Target not opened, or it is known to be the source document. | Ask for another location. |

Identity: the writer refuses only when the target is known to be the source (equal URI strings, or equal
non-null `documentKey`s). If `documentKey` is null for either URI (documents opened through VIEW/SEND from
file managers, mail apps or MediaStore are not document URIs), the copy is allowed. This is safe for the data:
the copy is written from the private staged file and read back, so a target that is secretly the source ends
up holding the complete new document. That case is detected after the copy (the source hashed differently
before and hashes to the new bytes now), the manifest is updated (not while a copy-back is pending), and
`Copied.replacedSource` carries the document. If the source cannot be read afterwards, the case goes
undetected and the next Replace original reports a `Conflict`, with the usual snapshot on overwrite.

Overwrite and nondeterministic providers: each conflict check reads the whole source once more (before the
snapshot and again before truncation). On a provider that returns different bytes on each read, the check
never agrees, and every save becomes a permanent `Conflict`. Offer Save a copy or `exportStaging` as the way out.

## Timeouts and cancellation

- Before truncation, cancellation is honoured between chunks (hashing, staging, import). Nothing on the source
  changes. `staging.next` may remain and the next save removes it.
- After the commit point (import rename) and from the marker on (save phase 2), the work runs in
  `NonCancellable`. A cancelled caller still gets the consistent state, but the cancelled coroutine does not
  receive the result. Call `pendingCopyStatus` after a cancellation.
- The copy (write, sync, close, read-back) runs on a worker thread with `copyTimeoutMs` (default 5 min, 0 =
  none). On timeout the worker is interrupted and the result is `CopyFailed` (during write, sync or close)
  or `Unverifiable` (during read-back), with "timed out" in `detail`. The document lock is released.
  **Residual risk:** a worker that ignores the interrupt may still complete a write after the result was
  returned. The marker and staging keep the state recoverable, and the next finish re-checks the source before
  writing. A hung write therefore cannot be made to lose data, but it can keep running in the background
  until the provider returns.

## Known limits

- Import and save share one limit, 128 MiB. Larger documents cannot be imported. Revisit with a streaming
  save path.
- Memory: the input and one verify copy are held in memory (about 2x the input at peak). The copy is kept on
  purpose: the engine takes an array, and a read-only view would not stop a caller from mutating it. Peak
  memory is therefore about 256 MiB plus engine copies at the limit.
- Residual window: the source can change between the re-check and the first byte written. The window is short,
  and it is only the window of one hash and one stat. When overwrite was forced, the snapshot taken in phase 1
  is the only copy of the external version.
- A pending save with new bytes that verify replaces `staging.pdf`. If the user changed the file outside the
  app in that interval, the next check reports a `Conflict`; it does not overwrite silently.
- Import fails closed when the provider's SIZE disagrees with the bytes read.
- Complete documents in `work/` are never reaped by `sweepTmp`. This module has no index of the ids the app
  still references, so a reap could delete the only copy a user can open. Deleting a document belongs to the
  app (which owns the recents index). A cancelled import's temp directory that cannot be removed is
  counted by `sweepTmp`.
- The directory is not fsynced (not possible on Android). Durability relies on file fsync and atomic rename.
- `ContentResolverStreams` is not exercised by JVM tests. It must be verified on a device or emulator
  (`wt` truncation, `sync()` on pipe-backed providers, `close()` behaviour, and `documentKey`, which returns
  null for non-document URIs).
- After a restart, `staging.pdf` is the only full copy of a pending save. The app should expose it through
  `finishPendingCopy` or `exportStaging`.

## Autosave and recovery

Owned files in `work/<docId>/`: `autosave.<gen>.pdf` (generation files, gen = 1, 2, 3 ...), `autosave.json` (the
record) and `autosave.tmp` (the temp file of every write). Nothing else is written or removed here: never
`staging.pdf`, `staging.next`, `copy.pending`, `original.pdf`, `manifest.json` or `save_copy.pdf`.

### Record

`autosave.json` is `{"version":2,"current":{slot},"previous":{slot}}`. Each slot holds `generation`,
`savedAtEpochMs`, `size`, `sha256` (of that generation file) and `sourceManifestSha256` (the manifest hash of the
source version it was taken against). `previous` is absent on the first save. The record is replaced atomically.

### Save sequence (AutosaveJournal)

Each save takes the next generation number: one more than the highest of the record's current generation and any
generation file on disk. So a number is never reused, even after a crash.

| Step | Action | Hook fired after the step |
|---|---|---|
| 1 | Write `autosave.tmp`, fsync | `gen-temp-written` |
| 2 | Rename it to `autosave.<gen>.pdf` | `gen-renamed` |
| 3 | Write the new record to `autosave.tmp`, fsync | `record-temp-written` |
| 4 | Rename it to `autosave.json` (the commit point) | `record-renamed` |
| 5 | Delete every generation file the new record does not name | `old-generation-deleted` |

The new record keeps the old current slot as `previous`. So after a successful save the two newest generations
remain, and only the third-newest is deleted. Keeping the previous generation is what lets a corrupt newest
generation fall back to a valid one.

### Crash table

Verified by injecting a crash at each hook, then reloading with fresh instances (`RecoveryScanner`,
`readVerifiedAutosave`). Save A is already committed; save B crashes.

| Crash after step | Record on disk | Recovered after the crash | Leftovers |
|---|---|---|---|
| 1 (gen temp written) | A | A | `autosave.tmp`, swept on the next write |
| 2 (gen renamed) | A | A | orphan generation B, swept |
| 3 (record temp written) | A | A | orphan generation B, `autosave.tmp` |
| 4 (record renamed) | B (previous = A) | B | generation A kept as previous |
| 5 (old generation deleted) | B (previous = A) | B | none |

A disk-full error or any other write failure before step 4 is the same as a crash at that step: the previous
autosave stays current (`RECORD_NOT_COMMITTED` is reported when the process survives). No step leaves zero valid
autosaves when one existed. Two crashes in a row also leave a recoverable state, and a save after them succeeds.

### RecoveryScanner

- `scan()` returns verified autosaves only. `scanAll()` also returns each `NotRecoverable` with a fixed reason.
- Choice: the record's current generation if it verifies (size and streamed SHA-256). Otherwise the previous
  generation if it verifies, and `usedFallback` is true. If neither verifies, the document is not recoverable.
- Orphan generation files (named by neither slot) are ignored by the scanner. Sweep removes them.
- `sourceChanged` compares the chosen slot's manifest hash with the manifest on disk. It never reads the SAF file,
  so an external edit made after the last verified save is not detected here.
- `hasPendingCopy` comes from `SafWriter.pendingCopyStatus`. A pending or unknown copy does not hide the autosave.
- Read-only. Entries whose names are not document ids, and directories without autosave files, are skipped
  silently. Blocking I/O: call it off the main thread.

### RecoveryActions

- `restore(docId)` returns the bytes of the chosen generation, or throws `RecoveryException(reason)`.
- `discard(docId)` deletes the record, every generation file and `autosave.tmp`, and cancels a debounced save for
  the document. It returns `RefusedPendingCopy` or `RefusedUnknownStatus` while a copy is pending or unknown,
  because `staging.pdf` may be the only full copy.
- `afterSuccessfulSave(docId)`: call it only after `SafWriter.saveBack` or `finishPendingCopy` returned `Saved`.
  Not after `SavedButManifestStale`, `CopyFailed`, `Unverifiable`, `Conflict` or `VerificationFailed`. It cancels a
  debounced save and waits for an in-flight write, so bytes taken before the save cannot come back. It refuses if a
  copy is still pending.
- `sweepOldAutosaves(maxAgeMs, keepPending = true)` does two things, both under the document's lock. (1) It removes
  whole autosaves whose current record is older than `maxAgeMs`. (2) It removes orphan generation files whatever
  their age, but only when the record parses. Documents with a pending or unknown copy are skipped unless
  `keepPending` is false. Even then only autosave files are removed. It returns the number of documents changed.
- Every call holds the document's journal lock, so it never races a save.

### AutosaveRecoverySource

- `latestBytes(docId)` takes the workspace document id (`ImportedDoc.docId`), not a recents key. It returns the
  verified bytes or null, and never throws. `passwordFor` always returns null.
- Mapping: recents rows store `docKey` (the `UriStreams.documentKey` of the uri). They do not store a docId. When
  the app imports a document, it keeps `ImportedDoc.docId` next to the recents row. It then passes that docId here.

### RecentsStore

- `recents.json` sits in the storage root. It is shaped `{"version":1,"entries":{"0":{...},"1":{...}}}`, because
  `Json` has no arrays. Each row has an `order` field (0 = newest). Decoding sorts on `order`, never on the key
  names, so out-of-order keys still load in MRU order. Rows are capped at 50 and deduplicated on the document key
  (`UriStreams.documentKey`, or the raw uri when that is null).
- Row fields: `order`, `docKey`, `uri`, `displayName`, `lastOpenedAt`, `pageCount`, `sizeBytes`, `thumbnailRef` (an
  opaque name, never a path) and `unavailable`.
- Writes are atomic (`recents.json.part`, fsync, rename). A failed write returns `RecentsResult.Failed` and rolls
  memory back to the file.
- A corrupt file (bad JSON, bad field types, a row without `order`, invalid UTF-8, over 1 MiB, or nesting deeper
  than 3) loads as an empty list with `loadedCorrupt` set. It is renamed to `recents.json.corrupt-<ms>` and never
  deleted. If that rename fails, writes are refused so nothing is overwritten.
- Unpaired surrogates in names and uris are replaced by U+FFFD before they are written.
- Methods are synchronized. The constructor reads the file, so build it off the main thread. Nothing is logged.

### Tests and limits

- New JVM tests: `AutosaveJournalTest`, `RecoveryTest` and `RecentsStoreTest`. Debounce is tested under virtual
  time with a dedicated scope on the test dispatcher. Files are real, in a temp folder.
- Corruption of `autosave.json` itself (not a crash) loses both slots. A crash cannot produce that state, because
  the record is replaced by an atomic rename after an fsync.
- Not verified on a device. Autosaves live in app storage, so `ContentResolverStreams` is not involved. The fsync
  and rename behaviour is only checked on the JVM.
- Journal slots (one per document id seen in the process) are kept until the process ends.
