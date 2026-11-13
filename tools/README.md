# tools/

Build-time checks for the Android app. Standard library only (Python 3.8+), no network.

## check_manifest.py

Checks an Android manifest against the project's hardening rules (no INTERNET,
no backup of user data, no exported components by accident, no debuggable or cleartext builds).

```sh
python3 tools/check_manifest.py <AndroidManifest.xml> \
    [--allow-exported NAME ...] [--allow-permission NAME ...] [--json]
```

- Run it on the **merged** manifest (what the manifest merger produces), because libraries can
  add permissions and components. Raw source manifests also work, but they miss merged entries.
- `--allow-exported` accepts a full component name or its last segment (`MainActivity` matches
  `.MainActivity` and `app.pdfa.MainActivity`).
- `--allow-permission` extends the allowlist (default: `android.permission.CAMERA`). Network
  permissions can never be allowed (R1).
- Output: one line per violation, then a summary line.

```
FAIL R1 uses-permission android.permission.INTERNET -- network permission is forbidden
FAIL: 1 violation(s) in app/build/.../AndroidManifest.xml
```

A clean run prints `OK: 0 violations in <path>`.

Exit codes: `0` no violations, `1` violations found, `2` the file could not be read or parsed.
`--json` prints a JSON array of `{rule, element, name, message}` objects instead of text.

### Rules

| Rule | Checks |
|---|---|
| R1 | `uses-permission` (also `-sdk-23`, `-sdk-m`) of INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, NFC. Not overridable. |
| R2 | Any other permission not in the allowlist. Fix: pass `--allow-permission`. |
| R3 | `application/@android:allowBackup` is exactly `"false"` (missing counts as fail), and `dataExtractionRules` or `fullBackupContent` is set. |
| R4 | `application/@android:debuggable` is not `"true"`. |
| R5 | `application/@android:usesCleartextTraffic` is not `"true"`. |
| R6 | Activity, service, receiver, provider (and activity-alias) that are exported are in `--allow-exported`. Exported means `android:exported` not `"false"`, or, when the attribute is absent, an `intent-filter` is present. |
| R7 | Service with process `:engine` (or ending in `engine`) has `isolatedProcess="true"` and an explicit `exported="false"`. |
| R8 | Provider with `grantUriPermissions="true"` or a `<grant-uri-permission>` child. |
| R9 | `uses-feature android.hardware.camera` has `required="false"` when present. |

Elements with `tools:node="remove"` are ignored. Permissions and components are checked in
document order, and every violation is reported (the checker never stops at the first).

## Tests

From the repository root:

```sh
python3 -m unittest discover -s tools/tests -v
```

- `tools/tests/fixtures/good.xml` passes with `--allow-exported MainActivity`.
- `tools/tests/fixtures/bad_*.xml` each break exactly one rule (checked with the same allow flag).
- `tools/tests/fixtures/remove_internet.xml` plants INTERNET with `tools:node="remove"` and must pass.

## Wiring into Gradle

The `:app` module runs the checker on the merged manifest of every variant (`./gradlew :app:checkManifest`),
and CI runs that task.
