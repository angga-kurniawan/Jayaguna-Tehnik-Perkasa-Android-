#!/usr/bin/env python3
"""Prepare the company backend for Render, or the optional legacy Firebase admin package."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROJECT = "jayagunatehnikperkasa"
INPUTS = ("package.json", "package-lock.json", "tsconfig.json")


def admin_entrypoint(index):
    first_export = re.search(r"^export\s", index, re.MULTILINE)
    admin = re.search(r"^export const manageAdmin\s*=\s*callable\(service\.manageAdmin\);[ \t]*$", index, re.MULTILINE)
    if not first_export or not admin or admin.start() < first_export.start():
        raise ValueError("Existing callable entrypoint changed; review its manageAdmin export before preparing deployment.")
    return index[:first_export.start()] + admin.group().rstrip() + "\n"


def prepare(root=ROOT, target="firebase"):
    if target not in {"firebase", "render"}:
        raise ValueError("Unknown deployment target.")
    root = Path(root).resolve()
    source = root / "functions"
    required = [source / name for name in INPUTS] + [source / "src" / "index.ts"]
    if target == "render":
        required.append(source / "src" / "central-server.ts")
    if source.is_symlink() or (source / "src").is_symlink():
        raise ValueError("Function source must not be a symbolic link.")
    if any(not path.is_file() or path.is_symlink() for path in required):
        raise ValueError("Function package, lockfile, tsconfig, or index source is missing or linked.")
    if any(path.is_symlink() for path in (source / "src").rglob("*")):
        raise ValueError("Function source contains a symbolic link; review it before packaging.")
    entrypoint = admin_entrypoint((source / "src" / "index.ts").read_text(encoding="utf-8-sig")) if target == "firebase" else None
    package = json.loads((source / "package.json").read_text(encoding="utf-8-sig"))
    package["main"] = "lib/admin-index.js" if target == "firebase" else "lib/central-server.js"
    if target == "render":
        package["scripts"] = {"build": "tsc", "start:central": "node lib/central-server.js"}
    base = root / ".artifacts" / "admin-deploy"
    base.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=f"manage-admin-{target}-", dir=base))
    output = stage / "functions"
    output.mkdir()
    for name in INPUTS:
        shutil.copyfile(source / name, output / name)
    # Only TypeScript source is needed; local env, credentials, generated code and caches stay out.
    for path in sorted((source / "src").rglob("*.ts")):
        destination = output / path.relative_to(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    if entrypoint is not None:
        (output / "src" / "admin-index.ts").write_text(entrypoint, encoding="utf-8")
    (output / "package.json").write_text(json.dumps(package, indent=2) + "\n", encoding="utf-8")
    if target == "render":
        (stage / "render.yaml").write_text(RENDER_BLUEPRINT, encoding="utf-8")
        (output / ".node-version").write_text("22\n", encoding="utf-8")
        (stage / ".gitignore").write_text("node_modules/\nlib/\n.env*\n*.log\n*service-account*.json\n*firebase-adminsdk*.json\ncredentials*.json\n", encoding="utf-8")
        return stage
    config = {"functions": [{
        "source": "functions", "codebase": "default", "runtime": "nodejs22",
        "predeploy": ["npm --prefix \"$RESOURCE_DIR\" run build"],
        "ignore": ["node_modules", ".git", ".env*", "**/.env*", "*-debug.log", "**/*service-account*.json", "**/*firebase-adminsdk*.json", "**/credentials*.json", "**/client_secret*.json"],
    }]}
    (stage / "firebase.json").write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    return stage


RENDER_BLUEPRINT = """# Google access and company Drive backend. Runtime secrets stay in Render.
services:
  - type: web
    name: jayaguna-central-api
    runtime: node
    plan: free
    region: singapore
    rootDir: functions
    buildCommand: npm ci && npm run build
    startCommand: npm run start:central
    healthCheckPath: /healthz
    autoDeployTrigger: "off"
    envVars:
      - key: NODE_ENV
        value: production
      - key: HOST
        value: 0.0.0.0
      - key: FIREBASE_PROJECT_ID
        value: jayagunatehnikperkasa
      - key: ENFORCE_APP_CHECK
        value: "false"
      - key: TRUST_PROXY_HOPS
        value: "0"
      - key: FIREBASE_SERVICE_ACCOUNT_JSON
        sync: false
      - key: GOOGLE_OAUTH_CLIENT_ID
        sync: false
      - key: GOOGLE_OAUTH_CLIENT_SECRET
        sync: false
      - key: GOOGLE_DRIVE_ACCOUNT_EMAIL
        value: mtahakurniawan@gmail.com
      - key: GOOGLE_DRIVE_PRODUCTS_FOLDER_ID
        value: 1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd
      - key: GOOGLE_DRIVE_PARTNERS_FOLDER_ID
        value: 1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6
      - key: GOOGLE_DRIVE_PORTFOLIO_RESIDENTIAL_FOLDER_ID
        value: 1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx
      - key: GOOGLE_DRIVE_PORTFOLIO_CORPORATE_FOLDER_ID
        value: 10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK
      - key: DRIVE_TOKEN_ENCRYPTION_KEY
        sync: false
      - key: RESEND_API_KEY
        sync: false
      - key: ACCESS_REQUEST_FROM
        sync: false
"""


def command_line(arguments):
    if os.name == "nt":
        # PowerShell: executable is fixed; quote every argument as a literal string.
        return arguments[0] + " " + " ".join("'" + argument.replace("'", "''") + "'" for argument in arguments[1:])
    return shlex.join(arguments)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=("firebase", "render"), default="render")
    args = parser.parse_args(argv)
    try:
        stage = prepare(target=args.target)
    except (OSError, ValueError) as error:
        print(f"Preparation failed: {error}", file=sys.stderr)
        return 1
    print(f"Staging: {stage}")
    print(command_line(["npm", "--prefix", str(stage / "functions"), "ci"]))
    print(command_line(["npm", "--prefix", str(stage / "functions"), "run", "build"]))
    if args.target == "firebase":
        print(command_line(["firebase", "deploy", "--project", PROJECT, "--config", str(stage / "firebase.json"), "--only", "functions:manageAdmin"]))
    else:
        print(f"Render blueprint: {stage / 'render.yaml'}")
        print("Publish only this staged source to a private repository, then select the Free web service plan in Render.")
        print("Set FIREBASE_SERVICE_ACCOUNT_JSON in Render secret storage; never add credentials to source or the APK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
