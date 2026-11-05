#!/usr/bin/env python3
"""Start an existing AVD with fresh DNS while retaining its user data."""

import argparse
import ipaddress
import platform
import subprocess
import sys

from build import find_sdk


def dns_addresses(value):
    try:
        addresses = [str(ipaddress.ip_address(item.strip())) for item in value.split(",")]
    except ValueError as error:
        raise argparse.ArgumentTypeError("DNS harus berupa alamat IP yang valid, dipisahkan koma.") from error
    if not 1 <= len(addresses) <= 4:
        raise argparse.ArgumentTypeError("Gunakan 1–4 alamat IP DNS, dipisahkan koma.")
    return ",".join(addresses)


def emulator_command(executable, avd, dns):
    return [str(executable), "-avd", avd, "-dns-server", dns, "-no-snapshot-load"]


def main(arguments=None):
    parser = argparse.ArgumentParser(description="Jalankan emulator dengan DNS baru tanpa menghapus data atau akun.")
    parser.add_argument("avd", nargs="?", help="Nama AVD dari --list")
    parser.add_argument("--list", action="store_true", help="Tampilkan AVD yang tersedia")
    parser.add_argument("--dns", type=dns_addresses, default="8.8.8.8,1.1.1.1", help="1–4 alamat IP DNS")
    args = parser.parse_args(arguments)
    if bool(args.avd) == args.list:
        parser.error("Pilih nama AVD atau --list.")
    try:
        sdk = find_sdk()
        executable = sdk / "emulator" / ("emulator.exe" if platform.system() == "Windows" else "emulator")
        if not executable.is_file():
            raise RuntimeError("Android Emulator belum ditemukan. Pasang melalui Android Studio → SDK Manager.")
        result = subprocess.run([str(executable), "-list-avds"], capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Daftar AVD belum dapat dibaca. Periksa pemasangan Android Emulator.")
        avds = [name.strip() for name in result.stdout.splitlines() if name.strip()]
        if args.list:
            print("\n".join(avds) if avds else "Belum ada AVD. Buat melalui Android Studio → Device Manager.")
            return 0
        if args.avd not in avds:
            raise RuntimeError("AVD tidak ditemukan. Jalankan kembali dengan --list untuk melihat nama yang tersedia.")
        print(f"Memulai {args.avd} dengan DNS {args.dns}. Data dan akun tetap disimpan.", flush=True)
        print("Jika AVD masih berjalan, tutup emulator tersebut dahulu agar perubahan DNS diterapkan.", flush=True)
        return subprocess.run(emulator_command(executable, args.avd, args.dns)).returncode
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
