import os
import sys
import unittest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)

from core.permissions.payload_projection import build_permission_projection_from_payload


class TaskWorkerPermissionProjectionTest(unittest.TestCase):

    def test_permission_projection_prefers_java_camel_case_fields(self):
        payload = {
            "ownerUnitCode": "620102000000",
            "visibleUnitCodes": ["620102000000", "620100000000", "620000000000"],
            "permissionVersion": 123,
            "deptCode": "fallback",
        }

        projection = build_permission_projection_from_payload(payload)

        self.assertEqual("620102000000", projection["owner_unit_code"])
        self.assertEqual([
            "620102000000",
            "620100000000",
            "620000000000",
        ], projection["visible_unit_codes"])
        self.assertEqual(123, projection["permission_version"])

    def test_permission_projection_splits_string_visible_units(self):
        payload = {
            "ownerUnitCode": "620102000000",
            "visibleUnitCodes": "620102000000，620100000000,620000000000",
        }

        projection = build_permission_projection_from_payload(payload)

        self.assertEqual([
            "620102000000",
            "620100000000",
            "620000000000",
        ], projection["visible_unit_codes"])

    def test_permission_projection_falls_back_to_dept_code_for_historical_payload(self):
        payload = {"deptCode": "620102000000"}

        projection = build_permission_projection_from_payload(payload)

        self.assertEqual("620102000000", projection["owner_unit_code"])
        self.assertEqual(["620102000000"], projection["visible_unit_codes"])
        self.assertEqual(0, projection["permission_version"])

    def test_permission_projection_defaults_to_global_without_unit_fields(self):
        projection = build_permission_projection_from_payload({})

        self.assertEqual("global", projection["owner_unit_code"])
        self.assertEqual(["global"], projection["visible_unit_codes"])
        self.assertEqual(0, projection["permission_version"])


if __name__ == "__main__":
    unittest.main()
