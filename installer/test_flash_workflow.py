import importlib.util
import os
import tempfile
import types
import unittest
import subprocess
from unittest.mock import patch

PATH = os.path.join(os.path.dirname(__file__), "..", "service_flash", "flash_service.py")
spec = importlib.util.spec_from_file_location("flash_service_test", PATH)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class FlashWorkflowTests(unittest.TestCase):
    def test_partition_rejects_error_text_even_on_zero_exit(self):
        if os.name != "nt":
            self.skipTest("Windows process test")
        compiler = os.path.join(os.environ.get("WINDIR", r"C:\Windows"),
                                r"Microsoft.NET\Framework64\v4.0.30319\csc.exe")
        with tempfile.TemporaryDirectory() as path:
            source, exe, image = (os.path.join(path, n) for n in ("mock.cs", "update.exe", "system.img"))
            with open(source, "w") as f:
                f.write('using System; class P { static int Main() { Console.Write("ERR: write failed"); return 0; } }')
            open(image, "wb").close()
            subprocess.run([compiler, "/nologo", "/out:" + exe, source], check=True)
            with patch.object(engine, "get_update_exe", return_value=exe), patch.object(engine, "SCRIPT_DIR", path):
                with self.assertRaisesRegex(RuntimeError, "Ошибка записи"):
                    engine.flash_partition("system", image, 0, 100)

    def test_commands_are_ordered_and_no_reset_or_erase(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False,
                                         revision="auto", keep_data=True)
            events = []
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
                 patch.object(engine, "verify_rom"), \
                 patch.object(engine, "is_uboot_alive", return_value=True), \
                 patch.object(engine, "run_after_ubt", side_effect=lambda: events.append("after_ubt.bat")), \
                 patch.object(engine, "aml_bulkcmd", side_effect=lambda cmd, **kw: events.append(cmd)), \
                 patch.object(engine, "flash_partition", side_effect=lambda name, *a: events.append(name)):
                engine.run_flash(args)
            self.assertEqual(events, ["mmc dev 1", "vbmeta", "vendor", "system", "after_ubt.bat"])

    def test_checksum_rejection_precedes_any_device_command(self):
        with tempfile.TemporaryDirectory() as path:
            for filename in engine.ROM_FILES:
                open(os.path.join(path, filename), "wb").close()
            args = types.SimpleNamespace(rom_dir=path, download_latest=False)
            with patch.object(engine, "check_worldcup_driver", return_value=True), \
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
                 patch.object(engine, "is_uboot_alive", return_value=True), \
                 patch.object(engine, "aml_bulkcmd"), \
                 patch.object(engine, "flash_partition"), \
                 patch.object(engine, "run_after_ubt", side_effect=RuntimeError("failure")):
                with self.assertRaisesRegex(RuntimeError, "failure"):
                    engine.run_flash(args)


if __name__ == "__main__":
    unittest.main()
