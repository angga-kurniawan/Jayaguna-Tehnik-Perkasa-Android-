import argparse
import contextlib
import importlib.util
import io
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("start_emulator", Path(__file__).with_name("start-emulator.py"))
launcher = importlib.util.module_from_spec(SPEC)
with mock.patch.object(sys, "path", [str(Path(__file__).resolve().parent), *sys.path]):
    SPEC.loader.exec_module(launcher)


class StartEmulatorTest(unittest.TestCase):
    def test_launch_preserves_user_data_and_treats_avd_as_one_literal_argument(self):
        avd = "Phone & echo --wipe-data"
        command = launcher.emulator_command(Path("/SDK with spaces/emulator"), avd, "8.8.8.8,1.1.1.1")
        self.assertEqual(avd, command[2])
        self.assertEqual(["-dns-server", "8.8.8.8,1.1.1.1", "-no-snapshot-load"], command[3:])
        self.assertNotIn("-wipe-data", command)
        self.assertNotIn("-wipe-cache", command)
        self.assertNotIn("-snapshot", command)

    def test_dns_rejects_flags_invalid_ips_and_empty_entries(self):
        for value in ("--wipe-data", "8.8.8.8,--wipe-data", "1.2.3.999", "8.8.8.8,", ""):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                launcher.dns_addresses(value)
        self.assertEqual("8.8.8.8,1.1.1.1", launcher.dns_addresses("8.8.8.8, 1.1.1.1"))

    def test_unknown_avd_is_rejected_without_starting_or_stopping_any_emulator(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            executable = sdk / "emulator" / ("emulator.exe" if platform.system() == "Windows" else "emulator")
            executable.parent.mkdir()
            executable.touch()
            with mock.patch.object(launcher, "find_sdk", return_value=sdk), mock.patch.object(launcher.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout="Existing_Phone\n")) as run, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, launcher.main(["Missing_Phone"]))
                run.assert_called_once_with([str(executable), "-list-avds"], capture_output=True, text=True, timeout=30)


if __name__ == "__main__":
    unittest.main()
