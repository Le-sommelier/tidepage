#!/usr/bin/env python3
"""Hardening check for an Android manifest (see tools/README.md).

Usage:
    python3 tools/check_manifest.py <AndroidManifest.xml>
        [--allow-exported NAME ...] [--allow-permission NAME ...] [--json]

Run it on the merged manifest when possible (the output of the manifest merger),
because libraries can add permissions and components. Standard library only.

Exit codes: 0 no violations, 1 violations found, 2 usage or parse error.
"""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass

ANDROID_NS = "http://schemas.android.com/apk/res/android"
TOOLS_NS = "http://schemas.android.com/tools"
A = "{%s}" % ANDROID_NS
T = "{%s}" % TOOLS_NS

# R1: never allowed, not even with --allow-permission.
NETWORK_PERMISSIONS = frozenset({
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.NFC",
})

# R2: permissions allowed without --allow-permission.
DEFAULT_ALLOWED_PERMISSIONS = frozenset({"android.permission.CAMERA"})

PERMISSION_TAGS = frozenset({"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"})
COMPONENT_TAGS = frozenset({"activity", "activity-alias", "service", "receiver", "provider"})


@dataclass(frozen=True)
class Violation:
    rule: str
    element: str
    name: str
    message: str

    def text(self):
        return "FAIL %s %s %s -- %s" % (self.rule, self.element, self.name, self.message)


def _removed(elem):
    """True when a manifest merger instruction removes this element (tools:node="remove")."""
    return elem.get(T + "node") == "remove"


def _perm_name(raw):
    """Short permission names such as CAMERA mean android.permission.CAMERA."""
    if raw and "." not in raw:
        return "android.permission." + raw
    return raw


def _strip_dots(name):
    return name.lstrip(".")


def _full_name(raw, package):
    if raw.startswith(".") and package:
        return package + raw
    return raw


def _is_allowed_component(raw, full, allowed):
    """Match --allow-exported entries by full name or by the part after the last dot."""
    candidates = {
        _strip_dots(raw),
        full,
        full.rsplit(".", 1)[-1],
        raw.rsplit(".", 1)[-1],
    }
    candidates.discard("")
    return bool(candidates & allowed)


def _is_exported(elem):
    """Explicit android:exported wins. Without it, an intent-filter means exported."""
    value = elem.get(A + "exported")
    if value is not None:
        return value != "false"  # fail closed on odd values
    return elem.find("intent-filter") is not None


def _check_permission(elem, allowed_permissions):
    raw = elem.get(A + "name", "")
    name = _perm_name(raw) or "(no name)"
    if name in NETWORK_PERMISSIONS:
        return [Violation("R1", elem.tag, name, "network permission is forbidden")]
    if name not in allowed_permissions:
        return [Violation(
            "R2", elem.tag, name,
            "permission is not in the allowlist; pass --allow-permission %s if intended" % name,
        )]
    return []


def _check_feature(elem):
    if elem.get(A + "name") == "android.hardware.camera" and elem.get(A + "required") != "false":
        return [Violation(
            "R9", elem.tag, "android.hardware.camera",
            'android:required must be "false" (an absent attribute means required)',
        )]
    return []


def _check_application(app):
    out = []
    allow_backup = app.get(A + "allowBackup")
    if allow_backup is None:
        out.append(Violation("R3", "application", "android:allowBackup",
                             "missing (the default is true); set it to \"false\""))
    elif allow_backup != "false":
        out.append(Violation("R3", "application", "android:allowBackup",
                             'must be "false", found "%s"' % allow_backup))
    if app.get(A + "dataExtractionRules") is None and app.get(A + "fullBackupContent") is None:
        out.append(Violation("R3", "application", "android:dataExtractionRules",
                             "neither android:dataExtractionRules nor android:fullBackupContent is set"))
    if app.get(A + "debuggable") == "true":
        out.append(Violation("R4", "application", "android:debuggable", 'must not be "true"'))
    if app.get(A + "usesCleartextTraffic") == "true":
        out.append(Violation("R5", "application", "android:usesCleartextTraffic", 'must not be "true"'))
    return out


def _check_component(elem, package, allowed_components):
    out = []
    raw = elem.get(A + "name", "")
    display = raw or "(no name)"
    full = _full_name(raw, package)

    if _is_exported(elem) and not _is_allowed_component(raw, full, allowed_components):
        if elem.get(A + "exported") is None:
            reason = "exported implicitly by an intent-filter (no android:exported)"
        else:
            reason = "exported=true"
        out.append(Violation(
            "R6", elem.tag, display,
            "%s and not in --allow-exported" % reason,
        ))

    if elem.tag == "service":
        process = elem.get(A + "process", "")
        if process == ":engine" or process.endswith("engine"):
            if elem.get(A + "isolatedProcess") != "true":
                out.append(Violation("R7", "service", display,
                                     'engine service needs android:isolatedProcess="true"'))
            if elem.get(A + "exported") != "false":
                out.append(Violation("R7", "service", display,
                                     'engine service needs android:exported="false"'))

    if elem.tag == "provider":
        if elem.get(A + "grantUriPermissions") == "true":
            out.append(Violation("R8", "provider", display,
                                 'android:grantUriPermissions must not be "true"'))
        elif elem.find("grant-uri-permission") is not None:
            out.append(Violation("R8", "provider", display,
                                 "<grant-uri-permission> child grants URI access"))
    return out


def check_root(root, allow_exported=(), allow_permission=()):
    """Return the list of Violation objects for a parsed <manifest> element."""
    if root.tag != "manifest":
        raise ValueError("root element is <%s>, expected <manifest>" % root.tag)

    allowed_permissions = DEFAULT_ALLOWED_PERMISSIONS | {_perm_name(p) for p in allow_permission}
    allowed_components = {_strip_dots(n) for n in allow_exported}
    package = root.get("package", "")

    violations = []
    apps = []
    for elem in root:
        if _removed(elem):
            continue
        if elem.tag in PERMISSION_TAGS:
            violations.extend(_check_permission(elem, allowed_permissions))
        elif elem.tag == "uses-feature":
            violations.extend(_check_feature(elem))
        elif elem.tag == "application":
            apps.append(elem)

    if not apps:
        violations.append(Violation("R3", "application", "application",
                                    "no <application> element; backup settings cannot be checked"))
    for app in apps:
        violations.extend(_check_application(app))
        for comp in app:
            if comp.tag in COMPONENT_TAGS and not _removed(comp):
                violations.extend(_check_component(comp, package, allowed_components))
    return violations


def check_text(xml_text, allow_exported=(), allow_permission=()):
    """Check manifest source given as a string."""
    return check_root(ET.fromstring(xml_text), allow_exported, allow_permission)


def check_file(path, allow_exported=(), allow_permission=()):
    """Check a manifest file. Raises ET.ParseError, OSError or ValueError on bad input."""
    return check_root(ET.parse(path).getroot(), allow_exported, allow_permission)


def build_parser():
    parser = argparse.ArgumentParser(description="Check an Android manifest against the hardening rules.")
    parser.add_argument("manifest", help="path to AndroidManifest.xml (merged manifest preferred)")
    parser.add_argument("--allow-exported", nargs="+", action="extend", default=[], metavar="NAME",
                        help="component names allowed to be exported (full name or last segment)")
    parser.add_argument("--allow-permission", nargs="+", action="extend", default=[], metavar="NAME",
                        help="extra permissions allowed (network permissions stay forbidden)")
    parser.add_argument("--json", action="store_true", help="print violations as a JSON array")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        violations = check_file(args.manifest, args.allow_exported, args.allow_permission)
    except (OSError, ET.ParseError, ValueError) as exc:
        print("error: cannot check %s: %s" % (args.manifest, exc), file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps([asdict(v) for v in violations], indent=2))
    else:
        for v in violations:
            print(v.text())
        if violations:
            print("FAIL: %d violation(s) in %s" % (len(violations), args.manifest))
        else:
            print("OK: 0 violations in %s" % args.manifest)
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
