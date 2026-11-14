import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class AfterUbtBatchTests(unittest.TestCase):
    def test_batch_order_and_each_error(self):
        csc = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
        if os.name != "nt" or not csc.exists():
            self.skipTest("Requires Windows .NET Framework compiler")
        commands = ["store init", "amlmmc enc", "setenv silent 0", "setenv lock 10000000", "setenv avb2 0", "saveenv"]
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            shutil.copy(Path(__file__).resolve().parent.parent / "service_flash/after_ubt.bat", d)
            (d / "mock.cs").write_text('''using System; using System.IO; class P { static int Main(string[] a) { File.AppendAllText(Environment.GetEnvironmentVariable("TRACE_FILE"), a[1] + "\\n"); if (a[1] == Environment.GetEnvironmentVariable("FAIL_COMMAND")) { Console.WriteLine("ERR: mock failed"); return 0; } Console.WriteLine("ok"); return 0; } }''')
            subprocess.run([str(csc), "/nologo", "/out:" + str(d / "update.exe"), str(d / "mock.cs")], check=True)
            for failed in [None] + commands:
                trace = d / "trace.txt"
                if trace.exists(): trace.unlink()
                env = dict(os.environ, TRACE_FILE=str(trace), FAIL_COMMAND=failed or "")
                result = subprocess.run(["cmd.exe", "/d", "/c", "after_ubt.bat"], cwd=d, env=env, capture_output=True, timeout=20)
                expected = commands if failed is None else commands[:commands.index(failed) + 1]
                with self.subTest(failed=failed):
                    self.assertEqual(trace.read_text().splitlines(), expected)
                    self.assertEqual(result.returncode == 0, failed is None)

if __name__ == "__main__": unittest.main()
