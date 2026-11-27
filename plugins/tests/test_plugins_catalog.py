import json
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import build_plugins_index as bpi  # noqa: E402


class PluginsCatalogTests(unittest.TestCase):
    def test_index_matches_all_plugin_manifests_and_hashes(self):
        expected = bpi.build_index()
        self.assertGreaterEqual(len(expected["plugins"]), 5)
        with open(bpi.INDEX_PATH, "r", encoding="utf-8") as f:
            actual = json.load(f)
        self.assertEqual(actual, expected)

    def test_plugin_ids_and_tool_names_unique(self):
        index = bpi.build_index()
        ids = [p["id"] for p in index["plugins"]]
        self.assertEqual(len(ids), len(set(ids)))
        all_tools = []
        for p in index["plugins"]:
            all_tools.extend(p["tools"])
        self.assertEqual(len(all_tools), len(set(all_tools)))


if __name__ == "__main__":
    unittest.main()
