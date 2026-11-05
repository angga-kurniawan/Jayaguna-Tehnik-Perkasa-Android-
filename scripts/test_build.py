import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("project_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class PortableBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def jdk(self, major):
        path = self.root / ("jdk " + str(major))
        (path / "bin").mkdir(parents=True)
        (path / "bin/java").touch()
        (path / "bin/javac").touch()
        return path

    def sdk(self):
        path = self.root / "Android SDK"
        for part in ("platform-tools", "platforms", "build-tools"):
            (path / part).mkdir(parents=True)
        return path

    def test_parses_supported_and_unsupported_java_versions(self):
        for text, expected in [('openjdk version "21.0.11"', 21), ('java version "17.0.2"', 17),
                               ('java version "1.8.0_432"', 8), ('openjdk version "25.0.4"', 25)]:
            self.assertEqual(expected, build.java_major(text))
        self.assertIsNone(build.java_major("java could not be launched"))

    def test_selects_21_when_laptop_default_is_25_and_17_also_exists(self):
        jdks = [self.jdk(25), self.jdk(17), self.jdk(21)]
        def response(command, **kwargs):
            version = Path(command[0]).parents[1].name.split()[-1]
            return subprocess.CompletedProcess(command, 0, stdout=f'openjdk version "{version}.0.1"')
        with mock.patch.object(build, "java_candidates", return_value=jdks), mock.patch.object(build.subprocess, "run", side_effect=response):
            self.assertEqual((21, jdks[-1]), build.find_jdk(self.root, {}, "Linux", self.root))

    def test_java_25_alone_is_rejected_before_gradle_runs(self):
        jdk = self.jdk(25)
        with mock.patch.object(build, "java_candidates", return_value=[jdk]), mock.patch.object(build.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout='openjdk version "25.0.4"')):
            with self.assertRaisesRegex(RuntimeError, "Java 25"):
                build.find_jdk(self.root, {}, "Linux", self.root)

    def test_java_17_alone_is_rejected_when_daemon_requires_21(self):
        jdk = self.jdk(17)
        criteria = self.root / "gradle/gradle-daemon-jvm.properties"
        criteria.parent.mkdir()
        criteria.write_text("toolchainVersion=21\n", encoding="utf-8")
        with mock.patch.object(build, "java_candidates", return_value=[jdk]), mock.patch.object(build.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout='openjdk version "17.0.2"')):
            with self.assertRaisesRegex(RuntimeError, "JDK 21 dibutuhkan"):
                build.find_jdk(self.root, {}, "Linux", self.root)

    def test_jdk_selection_honors_the_declared_daemon_criterion(self):
        jdk = self.jdk(17)
        criteria = self.root / "gradle/gradle-daemon-jvm.properties"
        criteria.parent.mkdir()
        criteria.write_text("toolchainVersion=17\n", encoding="utf-8")
        with mock.patch.object(build, "java_candidates", return_value=[jdk]), mock.patch.object(build.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout='openjdk version "17.0.2"')):
            self.assertEqual((17, jdk), build.find_jdk(self.root, {}, "Linux", self.root))

    def test_sdk_moves_to_new_laptop_without_changing_other_properties(self):
        path = self.root / "local.properties"
        path.write_bytes(b"# local settings\r\nsdk.dir=C\\:/old/laptop/sdk\r\nbackend.debugBaseUrl=http\\://10.0.2.2\\:8787\r\n")
        sdk = self.sdk()
        selected = build.find_sdk(self.root, {"ANDROID_HOME": str(sdk)}, "Linux", self.root)
        self.assertEqual(sdk, selected)
        self.assertTrue(build.configure_sdk(self.root, selected))
        content = path.read_bytes()
        self.assertIn(b"# local settings\r\n", content)
        self.assertIn(b"backend.debugBaseUrl=http\\://10.0.2.2\\:8787\r\n", content)
        self.assertEqual(str(sdk), build.properties(path)["sdk.dir"])
        self.assertFalse(build.configure_sdk(self.root, selected))

    def test_existing_valid_sdk_takes_priority_over_environment(self):
        sdk = self.sdk()
        (self.root / "local.properties").write_text("sdk.dir=" + str(sdk), encoding="utf-8")
        self.assertEqual(sdk, build.find_sdk(self.root, {"ANDROID_HOME": "/another/laptop"}, "Linux", self.root))

    def test_sdk_paths_with_unicode_round_trip_as_java_properties(self):
        sdk = self.sdk().rename(self.root / "Android SDK milik Déa 📱")
        self.assertTrue(build.configure_sdk(self.root, sdk))
        self.assertEqual(str(sdk), build.properties(self.root / "local.properties")["sdk.dir"])
        self.assertEqual(sdk, build.find_sdk(self.root, {}, "Linux", self.root))

    def test_windows_arguments_are_not_interpreted_as_shell_commands(self):
        arguments = [":app:assembleDebug", "-Pexample=a&echo secret", "-Pvalue=%PATH%"]
        command = build.gradle_command(self.root, self.root / "Java 21", arguments, "Windows")
        self.assertEqual(arguments, command[-3:])
        self.assertEqual("org.gradle.wrapper.GradleWrapperMain", command[-4])
        self.assertTrue(command[0].endswith("java.exe"))

    def test_check_does_not_change_sdk_configuration_or_launch_gradle(self):
        with mock.patch.object(build, "find_jdk", return_value=(21, self.root / "jdk")), mock.patch.object(build, "find_sdk", return_value=self.root / "sdk"), mock.patch.object(build, "configure_sdk") as configure, mock.patch.object(build.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, build.main(["--check"]))
            configure.assert_not_called()
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
