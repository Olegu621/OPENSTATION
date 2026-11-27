import importlib.util
import os
import tempfile
import types
import unittest
from contextlib import contextmanager
from unittest.mock import patch

PATH = os.path.join(os.path.dirname(__file__), "..", "service_flash", "flash_service.py")
spec = importlib.util.spec_from_file_location("flash_service_test", PATH)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class FlashWorkflowTests(unittest.TestCase):
    def setUp(self):
        mock_base = {name: name for name in engine.BASE_PARTITION_FILES}
        mocker = patch.object(engine, "prepare_base_partitions", return_value=mock_base)
        mocker.start()
        self.addCleanup(mocker.stop)

    def test_partition_rejects_failed_worldcup_status(self):
        class Device:
            def start_download(self, *args, **kwargs): return b"success"
            def write_download_block(self, *args, **kwargs): pass
            def bulkCmd(self, *args, **kwargs): return b"failed"

        @contextmanager
        def opened():
            yield Device()

        with tempfile.TemporaryDirectory() as path:
            image = os.path.join(path, "system.img")
            with open(image, "wb") as f:
                f.write(b"test image")
            with patch.object(engine, "open_worldcup", opened):
                with self.assertRaisesRegex(RuntimeError, "не подтвердил запись"):
                    engine.flash_partition("system", image, 0, 100)

    def test_commands_are_ordered_and_no_reset_or_erase(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False,
                                         revision="00053", keep_data=True)
            events = []
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "run_after_ubt", side_effect=lambda: events.append("after_ubt.bat")), \
                 patch.object(engine, "aml_bulkcmd", side_effect=lambda cmd, **kw: events.append(cmd)), \
                 patch.object(engine, "flash_partition", side_effect=lambda name, *a: events.append(name)):
                engine.run_flash(args)
            self.assertEqual(events, ["mmc dev 1", "boot", "dtbo", "odm", "product", "vbmeta", "vendor", "system", "misc", "after_ubt.bat"])

            events_auto = []
            args_auto = types.SimpleNamespace(rom_dir=path, download_latest=False,
                                              revision="auto", keep_data=True)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "run_after_ubt", side_effect=lambda: events_auto.append("after_ubt.bat")), \
                 patch.object(engine, "aml_bulkcmd", side_effect=lambda cmd, **kw: events_auto.append(cmd)), \
                 patch.object(engine, "flash_partition", side_effect=lambda name, *a: events_auto.append(name)):
                engine.run_flash(args_auto)
            self.assertEqual(events_auto, ["mmc dev 1", "boot", "dtbo", "odm", "product", "vbmeta", "vendor", "system", "after_ubt.bat"])

    def test_after_ubt_code_one_is_warning_but_other_errors_remain_errors(self):
        for code, expect_warning in ((0, False), (1, True)):
            proc = types.SimpleNamespace(returncode=code, communicate=lambda timeout: (b"", None))
            with self.subTest(code=code), patch.object(engine.subprocess, "Popen", return_value=proc), patch.object(engine, "emit"):
                self.assertEqual(engine.run_after_ubt(), expect_warning)
        proc = types.SimpleNamespace(returncode=2, communicate=lambda timeout: (b"", None))
        with patch.object(engine.subprocess, "Popen", return_value=proc), patch.object(engine, "emit"):
            with self.assertRaisesRegex(RuntimeError, "код 2"):
                engine.run_after_ubt()

    def test_warning_finishes_after_successful_partition_writes(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False, revision="auto", keep_data=True)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "aml_bulkcmd"), \
                 patch.object(engine, "flash_partition"), \
                 patch.object(engine, "run_after_ubt", return_value=True), \
                 patch.object(engine, "emit") as emit:
                self.assertTrue(engine.run_flash(args))
                self.assertTrue(any("не проверена" in call.args[0] for call in emit.call_args_list))

    def test_bad_base_images_never_begin_writing(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False, revision="auto")
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "prepare_base_partitions", side_effect=RuntimeError("bad image")), \
                 patch.object(engine, "aml_bulkcmd") as command, \
                 patch.object(engine, "flash_partition") as flash:
                with self.assertRaisesRegex(RuntimeError, "bad image"):
                    engine.run_flash(args)
                command.assert_not_called()
                flash.assert_not_called()

    def test_checksum_rejection_precedes_flash_commands(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "aml_bulkcmd") as command:
                with self.assertRaises(RuntimeError):
                    engine.run_flash(args)
                command.assert_not_called()

    def test_finalization_failure_propagates(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "ensure_burn_mode", return_value=True), \
                 patch.object(engine, "aml_bulkcmd"), \
                 patch.object(engine, "flash_partition"), \
                 patch.object(engine, "run_after_ubt", side_effect=RuntimeError("failure")):
                with self.assertRaisesRegex(RuntimeError, "failure"):
                    engine.run_flash(args)


class BasePartitionTests(unittest.TestCase):
    def test_real_bundled_archive_and_every_image(self):
        with tempfile.TemporaryDirectory() as folder:
            result = engine.prepare_base_partitions(folder)
            self.assertEqual(set(result), set(engine.BASE_PARTITION_FILES))
            for name, (size, _) in engine.BASE_PARTITION_FILES.items():
                self.assertEqual(os.path.getsize(result[name]), size)

    def test_archive_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(engine, "BASE_PARTITION_ARCHIVE_SHA256", "0" * 64):
            with self.assertRaises(RuntimeError):
                engine.prepare_base_partitions(folder)
            self.assertEqual(os.listdir(folder), [])

    def test_different_rom_base_is_rejected(self):
        import json
        with tempfile.TemporaryDirectory() as folder:
            with open(os.path.join(folder, "rom_manifest.json"), "w", encoding="utf-8") as output:
                json.dump({"device": "yandexstation_2", "baseSource": "different"}, output)
            with self.assertRaises(RuntimeError):
                engine.verify_base_compatibility(folder)


if __name__ == "__main__":
    unittest.main()
