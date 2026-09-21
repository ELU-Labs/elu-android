from __future__ import annotations

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check-api-snapshot.py"
SPEC = importlib.util.spec_from_file_location("check_api_snapshot", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
API = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(API)


class LegacyFacadeCompatibilityTest(unittest.TestCase):
    ORIGINAL = 'public final class dev.elu.analytics.Elu {\n  public static final void reset();\n}\n'

    def test_additions_preserve_published_members(self):
        candidate = self.ORIGINAL.replace('  public', '  public static final void optOut();\n  public')
        self.assertEqual([], API.missing_legacy_members(self.ORIGINAL, candidate))

    def test_removed_or_changed_method_fails(self):
        for candidate in [self.ORIGINAL.replace('reset();', 'reset(boolean);'), self.ORIGINAL.replace('  public static final void reset();\n', '')]:
            self.assertTrue(API.missing_legacy_members(self.ORIGINAL, candidate))

    def test_same_method_on_another_class_cannot_satisfy_legacy_abi(self):
        self.assertTrue(API.missing_legacy_members(self.ORIGINAL, self.ORIGINAL.replace('analytics.Elu {', 'analytics.Other {')))

    def test_class_declaration_change_is_not_an_addition(self):
        self.assertTrue(API.missing_legacy_members(self.ORIGINAL, self.ORIGINAL.replace('public final class', 'public class')))
