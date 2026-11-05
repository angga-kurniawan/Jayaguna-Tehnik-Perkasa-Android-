#!/usr/bin/env python3
"""Create a private, portable project copy with the existing signing kit."""

import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = Path(".artifacts/portable/Jayaguna-project-private.zip")
SIGNING_FILES = frozenset({
    "development.keystore", "jayaguna-release.jks", "public-fingerprints.json",
})
EXCLUDED_DIRECTORIES = frozenset({
    ".git", ".gradle", ".kotlin", ".idea", ".artifacts", "node_modules",
    "build", "__pycache__", ".aws", ".codex", ".agents", ".vscode", ".venv", "venv",
    ".npm-cache", ".pytest_cache", ".mypy_cache", "emulatorcache",
})
PRIVATE_README_NAME = "PORTABLE-PRIVATE-README.txt"
PRIVATE_README = """SALINAN PROYEK PRIVAT JAYAGUNA

Arsip ini berisi source aplikasi, konfigurasi Firebase, signing key development
dan release, serta password signing dalam keystore.properties.

Kirim secara pribadi hanya kepada pengembang yang Anda beri wewenang.
Jangan unggah ke repository publik atau bagikan arsip ini kepada pengguna APK.
Simpan cadangan privat di tempat lain. Arsip ZIP ini tidak dienkripsi.

Ekstrak ke folder proyek baru di laptop tujuan. Jangan menimpa proyek yang sudah
memiliki signing key sendiri tanpa memeriksa identitas key terlebih dahulu.
Gunakan kembali kit yang sama untuk semua build dan pembaruan berikutnya;
jangan membuat keystore pengganti ketika kit belum tersedia.

Pasang Python 3, Android Studio/SDK, dan JDK 21 pada laptop tujuan.
Jalankan python3 scripts/build.py --check lalu python3 scripts/build.py debug
atau release. Pada Windows gunakan py sebagai pengganti python3.
Path Java/SDK milik laptop asal dan cache build tidak disertakan.
Petunjuk lengkap: docs/PORTABLE_BUILD.md.

Login Google memerlukan client OAuth untuk sertifikat aplikasi dan konfigurasi
provider Firebase yang sesuai. Setelah akun disetujui, aplikasi meminta izin
Drive untuk akun login yang sama. Bagikan empat folder perusahaan ke akun
tersebut sebagai Editor. Drive Android tidak memerlukan backend publik.
Petunjuk: docs/LOGIN_DRIVE.md.
"""


def excluded_directory(relative):
    """Keep caches, host settings and nested signing material outside the copy."""
    lowered = tuple(part.lower() for part in relative.parts)
    if any(part in EXCLUDED_DIRECTORIES or part.startswith(".firebase")
           or part.startswith(".env") for part in lowered):
        return True
    return lowered[0] == ".signing" and len(lowered) > 1


def included_file(relative):
    parts = relative.parts
    name = relative.name.lower()
    if parts[0] == ".signing":
        return len(parts) == 2 and parts[1] in SIGNING_FILES
    if name == "keystore.properties":
        return relative == Path("keystore.properties")
    if name == "local.properties" or name == ".ds_store":
        return False
    if name.startswith(".env") and name != ".env.example":
        return False
    if name.startswith(".firebase"):
        return False
    if name.endswith((".log", ".pyc", ".pyo", ".jks", ".keystore", ".p12",
                      ".pfx", ".pem", ".key")):
        return False
    compact_name = name.replace("-", "").replace("_", "").replace(".", "")
    if (compact_name.startswith(("clientsecret", "credentials"))
            or "serviceaccount" in compact_name or "firebaseadminsdk" in compact_name):
        return False
    return relative != Path(PRIVATE_README_NAME)


def project_files(root):
    for current, directories, files in os.walk(root, followlinks=False):
        directory = Path(current)
        directories[:] = sorted(
            name for name in directories
            if not (directory / name).is_symlink()
            and not excluded_directory((directory / name).relative_to(root))
        )
        for name in sorted(files):
            path = directory / name
            relative = path.relative_to(root)
            if path.is_symlink() or not path.is_file() or not included_file(relative):
                continue
            yield path, relative.as_posix()


def write_entry(archive, path, name):
    # Private permissions also travel with the archive for tools honoring ZIP modes.
    info = zipfile.ZipInfo.from_file(path, arcname=name)
    info.external_attr = (stat.S_IFREG | 0o600) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    with path.open("rb") as source, archive.open(info, "w") as destination:
        shutil.copyfileobj(source, destination)


def package_project(root=ROOT):
    root = Path(root).resolve()
    required = [root / ".signing" / name for name in sorted(SIGNING_FILES)]
    required += [root / "keystore.properties", root / "signing-certificates.properties",
                 root / "app/google-services.json"]
    missing = [path.relative_to(root).as_posix() for path in required
               if path.is_symlink() or not path.is_file()]
    if (root / ".signing").is_symlink():
        raise RuntimeError("Folder .signing harus berada langsung di dalam proyek.")
    if missing:
        raise RuntimeError("Kit atau konfigurasi belum lengkap: " + ", ".join(missing)
                           + ". Pulihkan file asli; script ini tidak membuat key baru.")
    files = list(project_files(root))
    required_names = {path.relative_to(root).as_posix() for path in required}
    if not required_names.issubset({name for _, name in files}):
        raise RuntimeError("Kit atau konfigurasi memakai folder symbolic link yang tidak dapat disalin.")

    output = root / OUTPUT
    for directory in (root / ".artifacts", output.parent):
        if directory.is_symlink():
            raise RuntimeError("Folder keluaran arsip tidak boleh berupa symbolic link.")
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.parent.chmod(0o700)
    temporary = None
    try:
        descriptor, filename = tempfile.mkstemp(
            prefix=".Jayaguna-project-private-", suffix=".zip", dir=output.parent,
        )
        temporary = Path(filename)
        os.chmod(temporary, 0o600)
        with os.fdopen(descriptor, "w+b") as stream:
            with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED,
                                 compresslevel=6) as archive:
                readme = zipfile.ZipInfo(PRIVATE_README_NAME)
                readme.external_attr = (stat.S_IFREG | 0o600) << 16
                readme.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(readme, PRIVATE_README)
                count = 1
                for path, name in files:
                    write_entry(archive, path, name)
                    count += 1
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
        return output, count
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main(arguments=None):
    arguments = list(sys.argv[1:] if arguments is None else arguments)
    if arguments:
        if arguments == ["--help"]:
            print("Penggunaan: python3 scripts/package-portable.py")
            print("Membuat arsip privat proyek beserta signing kit yang sudah ada.")
            return 0
        print("Script ini tidak menerima opsi; gunakan --help.", file=sys.stderr)
        return 2
    try:
        output, count = package_project()
        print(f"Arsip privat siap: {output} ({count} file)")
        print("Berisi signing key dan password. Simpan/transfer secara pribadi; ZIP tidak dienkripsi.")
        return 0
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"Arsip belum dibuat: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
