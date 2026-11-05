#!/usr/bin/env python3
"""Build with this laptop's JDK/SDK while keeping the project's signing identity."""

import glob
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def unescape_property(value):
    def decode(match):
        escape = match[1]
        return chr(int(escape[1:], 16)) if escape.startswith("u") else {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(escape, escape)
    value = re.sub(r"\\(u[0-9a-fA-F]{4}|.)", decode, value)
    return value.encode("utf-16", "surrogatepass").decode("utf-16")


def escape_property_path(path):
    text = path.as_posix().replace("\\", "\\\\").replace(":", "\\:")
    result = []
    for char in text:
        if ord(char) < 128:
            result.append(char)
        else:
            encoded = char.encode("utf-16-be")
            result.extend("\\u" + encoded[index:index + 2].hex() for index in range(0, len(encoded), 2))
    return "".join(result)


def properties(path):
    """Read the scalar paths used in local.properties and Gradle's JDK setting."""
    if not path.is_file():
        return {}
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith(("#", "!")):
            continue
        match = re.match(r"\s*([^:=\s]+)\s*[:=]\s*(.*)", line)
        if match:
            result[match[1]] = unescape_property(match[2]).strip()
    return result


def java_major(output):
    match = re.search(r'(?:openjdk|java) version "([\d.]+)', output)
    if not match:
        return None
    parts = match[1].split(".")
    return int(parts[1] if parts[0] == "1" and len(parts) > 1 else parts[0])


def java_candidates(root, env, system, home):
    candidates = [env.get("JAVA_HOME"), env.get("JDK_HOME"),
                  properties(root / ".gradle/config.properties").get("java.home")]
    patterns = []
    if system == "Darwin":
        for version in ("21", "17"):
            try:
                result = subprocess.run(["/usr/libexec/java_home", "-v", version],
                                        capture_output=True, text=True, timeout=10)
                if result.returncode == 0:
                    candidates.append(result.stdout.strip())
            except (OSError, subprocess.TimeoutExpired):
                pass
        patterns = ["/Library/Java/JavaVirtualMachines/*/Contents/Home",
                    str(home / "Library/Java/JavaVirtualMachines/*/Contents/Home"),
                    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"]
    elif system == "Windows":
        for variable in ("ProgramFiles", "ProgramW6432", "LOCALAPPDATA"):
            if env.get(variable):
                base = Path(env[variable])
                patterns.extend(str(base / part) for part in (
                    "Android/Android Studio/jbr", "Programs/Android Studio/jbr",
                    "Java/*", "Microsoft/jdk*", "Eclipse Adoptium/*", "Amazon Corretto/*"))
    else:
        patterns = ["/usr/lib/jvm/*", "/opt/java/*", "/opt/android-studio/jbr"]
    for pattern in patterns:
        candidates.extend(sorted(glob.glob(pattern)))
    executable = shutil.which("java", path=env.get("PATH", ""))
    if executable:
        candidates.append(str(Path(executable).resolve().parent.parent))
    return list(dict.fromkeys(Path(value).expanduser() for value in candidates if value))


def find_jdk(root=ROOT, env=None, system=None, home=None):
    env = os.environ if env is None else env
    system = system or platform.system()
    required = properties(root / "gradle/gradle-daemon-jvm.properties").get("toolchainVersion", "21")
    if required not in ("17", "21"):
        raise RuntimeError("Kriteria JDK proyek tidak didukung. Gunakan toolchainVersion=21 pada konfigurasi daemon Gradle.")
    required = int(required)
    suffix = ".exe" if system == "Windows" else ""
    compatible = []
    for candidate in java_candidates(root, env, system, home or Path.home()):
        executable = candidate / "bin" / ("java" + suffix)
        if not executable.is_file():
            continue
        try:
            result = subprocess.run([str(executable), "-XshowSettings:properties", "-version"],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            continue
        major = java_major(result.stdout)
        actual_home = re.search(r"^\s*java.home\s*=\s*(.+)$", result.stdout, re.MULTILINE)
        candidate = Path(actual_home[1].strip()) if actual_home else candidate
        if result.returncode == 0 and major == required and (candidate / "bin" / ("javac" + suffix)).is_file():
            compatible.append((major, candidate))
    if not compatible:
        raise RuntimeError(f"JDK {required} dibutuhkan oleh daemon Gradle proyek ini, tetapi belum ditemukan. Pilih/pasang JDK {required}; Java 25 tidak cocok dengan Gradle proyek ini.")
    return max(compatible, key=lambda item: item[0])


def find_sdk(root=ROOT, env=None, system=None, home=None):
    env = os.environ if env is None else env
    system = system or platform.system()
    home = home or Path.home()
    candidates = [properties(root / "local.properties").get("sdk.dir"),
                  env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT")]
    if system == "Darwin":
        candidates.append(str(home / "Library/Android/sdk"))
    elif system == "Windows" and env.get("LOCALAPPDATA"):
        candidates.append(str(Path(env["LOCALAPPDATA"]) / "Android/Sdk"))
    else:
        candidates.append(str(home / "Android/Sdk"))
    for value in candidates:
        if value:
            candidate = Path(value).expanduser()
            if not candidate.is_absolute():
                candidate = root / candidate
            if all((candidate / part).is_dir() for part in ("platform-tools", "platforms", "build-tools")):
                return candidate.resolve()
    raise RuntimeError("Android SDK belum ditemukan. Pasang SDK melalui Android Studio → SDK Manager, atau atur ANDROID_HOME.")


def configure_sdk(root, sdk):
    path = root / "local.properties"
    current = properties(path).get("sdk.dir")
    if current:
        original = Path(current).expanduser()
        if not original.is_absolute():
            original = root / original
        if original.resolve() == sdk.resolve():
            return False
    content = path.read_bytes().decode("utf-8") if path.exists() else ""
    newline = "\r\n" if "\r\n" in content else "\n"
    replacement = "sdk.dir=" + escape_property_path(sdk) + newline
    lines = content.splitlines(keepends=True)
    updated = []
    inserted = False
    for line in lines:
        if re.match(r"\s*sdk\.dir\s*[:=]", line):
            if not inserted:
                updated.append(replacement)
                inserted = True
        else:
            updated.append(line)
    if not inserted:
        if updated and not updated[-1].endswith(("\r", "\n")):
            updated[-1] += newline
        updated.append(replacement)
    path.write_bytes("".join(updated).encode("utf-8"))
    return True


def gradle_command(root, jdk, arguments, system=None):
    if (system or platform.system()) == "Windows":
        # Invoke the same wrapper main class without cmd.exe interpreting %, &, or |.
        return [str(jdk / "bin/java.exe"), "-Xmx64m", "-Xms64m", "-classpath",
                str(root / "gradle/wrapper/gradle-wrapper.jar"),
                "org.gradle.wrapper.GradleWrapperMain", *arguments]
    return [shutil.which("sh") or "sh", str(root / "gradlew"), *arguments]


def main(arguments=None):
    arguments = list(sys.argv[1:] if arguments is None else arguments)
    check_only = arguments == ["--check"]
    if "--check" in arguments and not check_only:
        print("Gunakan --check tanpa task Gradle.", file=sys.stderr)
        return 2
    aliases = {"debug": ":app:assembleDebug", "release": ":app:assembleRelease"}
    arguments = [aliases.get(value, value) for value in arguments] or [":app:assembleDebug"]
    try:
        major, jdk = find_jdk()
        sdk = find_sdk()
        print(f"JDK {major}: {jdk}", flush=True)
        print(f"Android SDK: {sdk}", flush=True)
        print("Signing development: " + ("tersedia" if (ROOT / ".signing/development.keystore").is_file() else "belum tersedia"), flush=True)
        print("Konfigurasi release: " + ("tersedia" if (ROOT / "keystore.properties").is_file() else "belum tersedia"), flush=True)
        if check_only:
            return 0
        configure_sdk(ROOT, sdk)
        env = dict(os.environ, JAVA_HOME=str(jdk), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
        return subprocess.run(gradle_command(ROOT, jdk, arguments), cwd=ROOT, env=env).returncode
    except (OSError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
