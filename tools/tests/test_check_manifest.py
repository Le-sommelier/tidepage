"""Unit tests for tools/check_manifest.py.

Run from the repository root:
    python3 -m unittest discover -s tools/tests -v
"""

import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(TOOLS_DIR)
SCRIPT = os.path.join(TOOLS_DIR, "check_manifest.py")
FIXTURES = os.path.join(HERE, "fixtures")

sys.path.insert(0, TOOLS_DIR)
import check_manifest as cm  # noqa: E402

# Every bad fixture is otherwise clean when MainActivity is allowed to be exported,
# so each one must trigger exactly its own rule (and no others).
ALLOW = ["MainActivity"]

# fixture -> (expected rule, expected number of violations)
BAD_FIXTURES = {
    "bad_internet.xml": ("R1", 1),
    "bad_network_state.xml": ("R1", 1),
    "bad_sdk23_internet.xml": ("R1", 1),
    "bad_extra_permission.xml": ("R2", 1),
    "bad_allowbackup_missing.xml": ("R3", 1),
    "bad_no_backup_rules.xml": ("R3", 1),
    "bad_debuggable.xml": ("R4", 1),
    "bad_cleartext.xml": ("R5", 1),
    "bad_exported_service.xml": ("R6", 1),
    "bad_intent_filter_no_exported.xml": ("R6", 1),
    "bad_engine_not_isolated.xml": ("R7", 1),
    "bad_provider_grant.xml": ("R8", 1),
    "bad_camera_required.xml": ("R9", 1),
}


def fixture(name):
    return os.path.join(FIXTURES, name)


def wrap(body, app_attrs='android:allowBackup="false" android:dataExtractionRules="@xml/r"'):
    """Build a manifest around body (children of application) for inline tests."""
    return (
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        'xmlns:tools="http://schemas.android.com/tools">'
        "%s<application %s>%s</application></manifest>"
    ) % ("", app_attrs, body)


MAIN_ACTIVITY = (
    '<activity android:name=".MainActivity" android:exported="true">'
    '<intent-filter><action android:name="android.intent.action.MAIN"/>'
    '<category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>'
)


class GoodManifestTests(unittest.TestCase):
    def test_good_manifest_passes_with_allowed_launcher(self):
        self.assertEqual(cm.check_file(fixture("good.xml"), allow_exported=ALLOW), [])

    def test_good_manifest_flags_only_unallowed_launcher(self):
        violations = cm.check_file(fixture("good.xml"))
        self.assertEqual([(v.rule, v.name) for v in violations], [("R6", ".MainActivity")])

    def test_remove_instructions_are_honoured(self):
        violations = cm.check_file(fixture("remove_internet.xml"), allow_exported=ALLOW)
        self.assertEqual(violations, [])


class FixtureRuleTests(unittest.TestCase):
    def test_each_bad_fixture_triggers_only_its_rule(self):
        for name, (rule, count) in sorted(BAD_FIXTURES.items()):
            with self.subTest(fixture=name):
                violations = cm.check_file(fixture(name), allow_exported=ALLOW)
                self.assertEqual({v.rule for v in violations}, {rule})
                self.assertEqual(len(violations), count)

    def test_at_least_ten_bad_fixtures_exist(self):
        self.assertGreaterEqual(len(BAD_FIXTURES), 10)
        for name in BAD_FIXTURES:
            self.assertTrue(os.path.isfile(fixture(name)), name)


class PermissionTests(unittest.TestCase):
    def test_network_permission_cannot_be_allowed(self):
        violations = cm.check_file(
            fixture("bad_internet.xml"), allow_exported=ALLOW,
            allow_permission=["android.permission.INTERNET"],
        )
        self.assertEqual({v.rule for v in violations}, {"R1"})

    def test_extra_permission_allowed_with_flag(self):
        violations = cm.check_file(
            fixture("bad_extra_permission.xml"), allow_exported=ALLOW,
            allow_permission=["READ_EXTERNAL_STORAGE"],
        )
        self.assertEqual(violations, [])

    def test_short_permission_name_is_normalised(self):
        violations = cm.check_text(
            wrap('<uses-permission android:name="CAMERA"/>' + MAIN_ACTIVITY), allow_exported=ALLOW,
        )
        self.assertEqual(violations, [])

    def test_r2_message_tells_user_about_flag(self):
        (v,) = cm.check_file(fixture("bad_extra_permission.xml"), allow_exported=ALLOW)
        self.assertIn("--allow-permission android.permission.READ_EXTERNAL_STORAGE", v.message)

    def test_camera_feature_absent_is_fine(self):
        xml = wrap(MAIN_ACTIVITY)
        self.assertEqual(cm.check_text(xml, allow_exported=ALLOW), [])


class ApplicationTests(unittest.TestCase):
    def test_missing_application_is_reported(self):
        violations = cm.check_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>'
        )
        self.assertEqual([v.rule for v in violations], ["R3"])

    def test_allow_backup_true_is_reported(self):
        xml = wrap(MAIN_ACTIVITY, 'android:allowBackup="true" android:dataExtractionRules="@xml/r"')
        violations = cm.check_text(xml, allow_exported=ALLOW)
        self.assertEqual([v.rule for v in violations], ["R3"])

    def test_full_backup_content_satisfies_backup_rule(self):
        xml = wrap(MAIN_ACTIVITY, 'android:allowBackup="false" android:fullBackupContent="@xml/b"')
        self.assertEqual(cm.check_text(xml, allow_exported=ALLOW), [])

    def test_debuggable_false_passes(self):
        xml = wrap(MAIN_ACTIVITY, 'android:allowBackup="false" android:dataExtractionRules="@xml/r" '
                                  'android:debuggable="false"')
        self.assertEqual(cm.check_text(xml, allow_exported=ALLOW), [])


class ExportedComponentTests(unittest.TestCase):
    def test_allow_by_suffix(self):
        body = MAIN_ACTIVITY + '<service android:name="com.example.bridge.PdfBridge" android:exported="true"/>'
        violations = cm.check_text(wrap(body), allow_exported=["PdfBridge", "MainActivity"])
        self.assertEqual(violations, [])

    def test_allow_by_full_name(self):
        body = MAIN_ACTIVITY + '<receiver android:name="app.pdfa.Boot" android:exported="true"/>'
        self.assertEqual(cm.check_text(wrap(body), allow_exported=["MainActivity", "app.pdfa.Boot"]), [])

    def test_exported_receiver_without_allow_fails(self):
        body = MAIN_ACTIVITY + '<receiver android:name=".Boot" android:exported="true"/>'
        violations = cm.check_text(wrap(body), allow_exported=ALLOW)
        self.assertEqual([(v.rule, v.element, v.name) for v in violations], [("R6", "receiver", ".Boot")])

    def test_explicit_exported_false_with_intent_filter_passes(self):
        body = MAIN_ACTIVITY + (
            '<activity android:name=".Share" android:exported="false"><intent-filter>'
            '<action android:name="android.intent.action.SEND"/></intent-filter></activity>'
        )
        self.assertEqual(cm.check_text(wrap(body), allow_exported=ALLOW), [])

    def test_component_without_exported_or_filter_passes(self):
        body = MAIN_ACTIVITY + '<activity android:name=".Settings"/>'
        self.assertEqual(cm.check_text(wrap(body), allow_exported=ALLOW), [])

    def test_removed_component_is_ignored(self):
        body = MAIN_ACTIVITY + '<service android:name=".Gone" android:exported="true" tools:node="remove"/>'
        self.assertEqual(cm.check_text(wrap(body), allow_exported=ALLOW), [])

    def test_activity_alias_is_checked(self):
        body = MAIN_ACTIVITY + '<activity-alias android:name=".Alias" android:exported="true"/>'
        violations = cm.check_text(wrap(body), allow_exported=ALLOW)
        self.assertEqual([v.rule for v in violations], ["R6"])


class EngineServiceTests(unittest.TestCase):
    def test_engine_suffix_process_must_be_isolated(self):
        body = MAIN_ACTIVITY + (
            '<service android:name=".Eng" android:exported="false" android:process="com.x.engine"/>'
        )
        violations = cm.check_text(wrap(body), allow_exported=ALLOW)
        self.assertEqual([v.rule for v in violations], ["R7"])

    def test_engine_exported_must_be_explicit_false(self):
        body = MAIN_ACTIVITY + (
            '<service android:name=".Eng" android:isolatedProcess="true" android:process=":engine"/>'
        )
        violations = cm.check_text(wrap(body), allow_exported=ALLOW)
        self.assertEqual([v.rule for v in violations], ["R7"])
        self.assertIn('android:exported="false"', violations[0].message)

    def test_engine_fully_compliant_passes(self):
        body = MAIN_ACTIVITY + (
            '<service android:name=".Eng" android:exported="false" '
            'android:isolatedProcess="true" android:process=":engine"/>'
        )
        self.assertEqual(cm.check_text(wrap(body), allow_exported=ALLOW), [])

    def test_non_engine_process_is_not_constrained(self):
        body = MAIN_ACTIVITY + '<service android:name=".Work" android:process=":worker" android:exported="false"/>'
        self.assertEqual(cm.check_text(wrap(body), allow_exported=ALLOW), [])


class ProviderTests(unittest.TestCase):
    def test_grant_uri_child_element_fails(self):
        body = MAIN_ACTIVITY + (
            '<provider android:name=".P" android:authorities="a" android:exported="false">'
            '<grant-uri-permission android:path="/x"/></provider>'
        )
        violations = cm.check_text(wrap(body), allow_exported=ALLOW)
        self.assertEqual([v.rule for v in violations], ["R8"])

    def test_grant_uri_false_passes(self):
        body = MAIN_ACTIVITY + (
            '<provider android:name=".P" android:authorities="a" android:exported="false" '
            'android:grantUriPermissions="false"/>'
        )
        self.assertEqual(cm.check_text(wrap(body), allow_exported=ALLOW), [])


class ReportingTests(unittest.TestCase):
    def test_text_line_format(self):
        (v,) = cm.check_file(fixture("bad_internet.xml"), allow_exported=ALLOW)
        self.assertEqual(v.text(), "FAIL R1 uses-permission android.permission.INTERNET -- "
                                   "network permission is forbidden")

    def test_bad_root_raises(self):
        with self.assertRaises(ValueError):
            cm.check_text("<application/>")


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, SCRIPT, *args], capture_output=True, text=True,
        )

    def test_good_exit_zero_and_ok(self):
        proc = self.run_cli(fixture("good.xml"), "--allow-exported", "MainActivity")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("OK", proc.stdout)

    def test_bad_exit_one_and_lists_rule(self):
        proc = self.run_cli(fixture("bad_internet.xml"), "--allow-exported", "MainActivity")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("FAIL R1 uses-permission android.permission.INTERNET", proc.stdout)
        self.assertIn("FAIL: 1 violation(s)", proc.stdout)

    def test_allow_permission_flag_is_accepted(self):
        proc = self.run_cli(fixture("bad_extra_permission.xml"), "--allow-exported", "MainActivity",
                            "--allow-permission", "READ_EXTERNAL_STORAGE")
        self.assertEqual(proc.returncode, 0, proc.stdout)

    def test_json_output(self):
        proc = self.run_cli(fixture("bad_debuggable.xml"), "--allow-exported", "MainActivity", "--json")
        self.assertEqual(proc.returncode, 1)
        data = json.loads(proc.stdout)
        self.assertEqual([d["rule"] for d in data], ["R4"])
        self.assertEqual(set(data[0]), {"rule", "element", "name", "message"})

    def test_missing_file_exit_two(self):
        proc = self.run_cli(os.path.join(FIXTURES, "does_not_exist.xml"))
        self.assertEqual(proc.returncode, 2)
        self.assertIn("error", proc.stderr)



if __name__ == "__main__":
    unittest.main()
