# Tidepage

A PDF reader and editor for Android that works entirely on your phone. No account, no ads, no
analytics, and no network permission: the app cannot send your documents anywhere.

<img src="docs/design/icon/tidepage-store-512.png" alt="Tidepage icon" width="96">

## What it does

- **Read:** fast tiled rendering, search, text selection and copy, outline, links.
- **Organize pages:** rotate, delete, duplicate, reorder, insert blank pages, with undo.
- **Merge, extract and split** PDFs.
- **Annotate:** pen, highlighter and notes.
- **Fill forms:** text fields, check boxes, radio buttons and lists.
- **Scan:** camera pages or pictures to PDF, with edge detection and clean-up.
- **Save safely:** "Save a copy" by default. Writing back to the original is verified by reading
  the file back, and unsaved work is autosaved and offered again after a crash.

Status: version 0.1, in testing. Android 8.0 or later, 64-bit ARM devices.

## Privacy

The manifest declares one permission, CAMERA, used only by the scanner and asked for when you open
it. There is no INTERNET permission, so nothing can leave the device. See [PRIVACY.md](PRIVACY.md).
A build check (`tools/check_manifest.py`) fails the build if a permission or an exported component
is ever added by accident.

## How it is built

- **UI:** Kotlin and Jetpack Compose (`android/`).
- **PDF engine:** the Rust engine of [pdfcraft](https://github.com/storytold/pdfcraft), wrapped
  with UniFFI (`rust/pdfa-ffi`). It is fetched at a pinned commit and patched at build time; it is
  not copied into this repository.
- **Isolation:** PDFs are untrusted input, so the engine runs in a separate, isolated Android
  process with no file or network access, behind a watchdog. A hostile file can crash that process;
  the app notices, restarts it and keeps your work.

Further reading: [engine contract](docs/contract/CONTRACT.md), [design](docs/design/DESIGN.md),
[library size report](docs/size-report.md), and the READMEs inside `android/storage`,
`android/engine`, `android/engine-impl` and `android/engine-protocol`.

## Building

You need JDK 21, the Android SDK with an NDK, Rust (stable) with the `aarch64-linux-android`
target, and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```bash
scripts/build-android.sh          # fetches the pinned engine, builds libpdfa_ffi.so for arm64
cd android
./gradlew :app:assembleDebug      # or :app:assembleRelease
```

To work on the UI without building the native engine, use the in-memory fake:

```bash
./gradlew :app:installDebug -Ppdfa.fakeEngine=true
```

## Tests

```bash
cargo test --workspace                                   # Rust wrapper, hostile-input corpus, golden renders
cd android && ./gradlew lint test :app:checkManifest     # unit tests and the manifest check
./gradlew :app:connectedDebugAndroidTest                 # end-to-end tests on a device or emulator
python3 -m unittest discover -s tools/tests              # the manifest checker's own tests
```

`:engine:connectedDebugAndroidTest` inspects and kills the engine process, so it needs a rooted
(userdebug) emulator image.

## Known limits

- 64-bit ARM only; no x86 or 32-bit build yet.
- Password protection (writing encrypted PDFs) is switched off until it is verified against other
  readers.
- No OCR, redaction or digital signatures.
- Check boxes and radio buttons in PDFs that define no appearance for them show no mark after
  filling, although the value is saved.

## Licence and credits

Tidepage is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE).

The PDF engine is the work of the pdfcraft project, used under its MIT OR Apache-2.0 licence. The
ArtCraft and PdfCraft names and logos are trademarks of their owners and are not used here. The
third-party licence texts ship inside the app (Settings, Licences).

Tidepage is an independent project. It is not affiliated with or endorsed by the pdfcraft authors
or by Adobe.
