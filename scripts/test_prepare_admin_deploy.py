import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("prepare_admin_deploy", Path(__file__).with_name("prepare-admin-deploy.py"))
preparer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preparer)

INDEX = '''import { onCall } from "firebase-functions/v2/https";
import * as service from "./service";
const options = { region: "asia-southeast2", enforceAppCheck: false };
function callable(handler: any) { return onCall(options, handler); }
export const getSession = callable(service.getSession);
export const manageAdmin = callable(service.manageAdmin);
export const cleanupUploads = scheduled(service.cleanupStaging);
'''


class PrepareAdminDeployTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="jtp deploy test ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "functions"
        (self.source / "src" / "nested").mkdir(parents=True)
        files = {
            "package.json": json.dumps({"name": "test", "main": "lib/index.js", "engines": {"node": "22"}, "scripts": {"build": "tsc"}}),
            "package-lock.json": '{"name":"test","lockfileVersion":3}',
            "tsconfig.json": '{"compilerOptions":{"outDir":"lib"},"include":["src/**/*.ts"]}',
            "src/index.ts": INDEX,
            "src/service.ts": "export function manageAdmin() {}\n",
            "src/nested/helper.ts": "export const helper = true;\n",
            "src/credentials.json": "DO_NOT_COPY_RUNTIME_SECRET",
            "src/.env": "DO_NOT_COPY_RUNTIME_SECRET",
            ".env": "GOOGLE_APPLICATION_CREDENTIALS=DO_NOT_COPY_RUNTIME_SECRET",
            ".env.jayagunatehnikperkasa": "DO_NOT_COPY_RUNTIME_SECRET",
            "scripts/operator.cjs": "DO_NOT_COPY_RUNTIME_SECRET",
            "test/test.mjs": "DO_NOT_COPY_RUNTIME_SECRET",
            "lib/old.js": "DO_NOT_COPY_RUNTIME_SECRET",
            "node_modules/old.js": "DO_NOT_COPY_RUNTIME_SECRET",
        }
        for name, content in files.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        (self.root / ".signing").mkdir()
        (self.root / ".signing" / "private.keystore").write_text("DO_NOT_COPY_RUNTIME_SECRET")

    def test_stage_copies_only_required_inputs_and_no_local_environment(self):
        stage = preparer.prepare(self.root)
        actual = {str(path.relative_to(stage / "functions")) for path in (stage / "functions").rglob("*") if path.is_file()}
        self.assertEqual({"package.json", "package-lock.json", "tsconfig.json", "src/index.ts", "src/admin-index.ts", "src/service.ts", "src/nested/helper.ts"}, actual)
        for path in stage.rglob("*"):
            self.assertFalse(path.is_symlink())
            if path.is_file():
                self.assertNotIn("DO_NOT_COPY_RUNTIME_SECRET", path.read_text())

    def test_admin_entrypoint_keeps_existing_wrapper_and_exposes_only_admin(self):
        stage = preparer.prepare(self.root)
        entrypoint = (stage / "functions/src/admin-index.ts").read_text()
        self.assertEqual(INDEX[:INDEX.index("export const")] + "export const manageAdmin = callable(service.manageAdmin);\n", entrypoint)
        self.assertEqual("lib/admin-index.js", json.loads((stage / "functions/package.json").read_text())["main"])
        config = json.loads((stage / "firebase.json").read_text())
        self.assertEqual(["functions"], list(config))
        self.assertEqual("default", config["functions"][0]["codebase"])
        self.assertIn(".env*", config["functions"][0]["ignore"])

    def test_preparation_preserves_original_files_and_uses_unique_directories(self):
        before = {path.relative_to(self.source): path.read_bytes() for path in self.source.rglob("*") if path.is_file()}
        first = preparer.prepare(self.root)
        second = preparer.prepare(self.root)
        self.assertNotEqual(first, second)
        self.assertEqual(before, {path.relative_to(self.source): path.read_bytes() for path in self.source.rglob("*") if path.is_file()})

    def test_source_links_and_changed_export_are_rejected(self):
        (self.source / "src/index.ts").write_text(INDEX.replace("callable(service.manageAdmin)", "differentHandler()"))
        with self.assertRaises(ValueError):
            preparer.prepare(self.root)
        (self.source / "src/index.ts").write_text(INDEX)
        linked = self.source / "src/linked.ts"
        try:
            linked.symlink_to(self.source / "src/service.ts")
        except OSError:
            self.skipTest("Symbolic links require additional permissions on this platform")
        with self.assertRaises(ValueError):
            preparer.prepare(self.root)

    def test_render_package_uses_admin_server_and_contains_no_firebase_deployment(self):
        (self.source / "src/central-server.ts").write_text("export const server = 'admin-only';\n")
        stage = preparer.prepare(self.root, target="render")
        self.assertFalse((stage / "firebase.json").exists())
        self.assertFalse((stage / "functions/src/admin-index.ts").exists())
        package = json.loads((stage / "functions/package.json").read_text())
        self.assertEqual("lib/central-server.js", package["main"])
        self.assertEqual({"build": "tsc", "start:central": "node lib/central-server.js"}, package["scripts"])
        blueprint = (stage / "render.yaml").read_text()
        self.assertIn("plan: free", blueprint)
        self.assertIn("startCommand: npm run start:central", blueprint)
        self.assertIn("sync: false", blueprint)
        self.assertIn("GOOGLE_OAUTH_CLIENT_SECRET", blueprint)
        self.assertNotIn("DO_NOT_COPY_RUNTIME_SECRET", blueprint)
        self.assertNotIn("GOOGLE_APPLICATION_CREDENTIALS", blueprint)
        self.assertEqual("22", (stage / "functions/.node-version").read_text().strip())
        for path in stage.rglob("*"):
            if path.is_file():
                self.assertNotIn("DO_NOT_COPY_RUNTIME_SECRET", path.read_text())

    def test_render_requires_admin_entrypoint_before_creating_any_package(self):
        with self.assertRaises(ValueError):
            preparer.prepare(self.root, target="render")
        self.assertFalse((self.root / ".artifacts").exists())


if __name__ == "__main__":
    unittest.main()
