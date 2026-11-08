import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "stage_preinstalled_assets", Path(__file__).resolve().parents[1] / "stage_preinstalled_assets.py")
STAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STAGE)


class StagePreinstalledAssetsTest(unittest.TestCase):
    def manifest(self):
        return {
            "sources": {"aharou": {"distros": {"alpine": "https://example.test/releases"}}},
            "images": [{"id": "alpine-3.24", "distro": "alpine", "version": "3.24.3",
                        "paths": {"aharou": "original-{abi}.tar.gz"},
                        "preinstalledPaths": {"aharou": "preinstalled-{abi}.tar.gz"},
                        "abiNames": {"arm64": "aarch64", "x86_64": "x86_64"},
                        "preinstalledSources": {"aharou": {"profile": "general", "recipeRevision": "1"}},
                        "sha256ByAbi": {"arm64": "c" * 64, "x86_64": "d" * 64},
                        "sourceSha256ByAbi": {"aharou": {"arm64": "a" * 64, "x86_64": "b" * 64}},
                        "sourceSizeBytesByAbi": {"aharou": {"arm64": 11, "x86_64": 12}}}]
        }

    def test_plan_keeps_original_hash_separate(self):
        records = STAGE.plan(self.manifest())
        self.assertEqual(["a" * 64, "b" * 64], [r["sha256"] for r in records])
        self.assertEqual(["arm", "x86"], [r["directory"] for r in records])
        self.assertTrue(records[0]["url"].endswith("aarch64.tar.gz"))

    def test_legacy_manifest_has_no_download_plan(self):
        manifest = self.manifest()
        del manifest["images"][0]["preinstalledSources"]
        self.assertEqual([], STAGE.plan(manifest))

    def test_derived_manifest_cannot_fall_back_to_original_checksum(self):
        manifest = self.manifest()
        del manifest["images"][0]["sourceSha256ByAbi"]["aharou"]["arm64"]
        with self.assertRaises(KeyError):
            STAGE.plan(manifest)

    def test_unsupported_profile_fails(self):
        manifest = self.manifest()
        manifest["images"][0]["preinstalledSources"]["aharou"]["profile"] = "napcat"
        with self.assertRaises(ValueError):
            STAGE.plan(manifest)

    def test_missing_or_same_architecture_path_fails(self):
        manifest = self.manifest()
        manifest["images"][0]["preinstalledPaths"]["aharou"] = "same.tar.gz"
        with self.assertRaises(ValueError):
            STAGE.plan(manifest)

    def archive(self, path, *, abi="arm64", missing=None, extra=None):
        with tarfile.open(path, "w:gz") as archive:
            content = json.dumps({"schemaVersion": 1, "profile": "general", "distro": "alpine",
                                  "abi": abi, "recipeRevision": "1"}).encode()
            marker = tarfile.TarInfo(STAGE.MARKER)
            marker.size = len(content)
            archive.addfile(marker, io.BytesIO(content))
            for tool in sorted(STAGE.REQUIRED - {missing}):
                member = tarfile.TarInfo("bin/" + tool)
                archive.addfile(member, io.BytesIO())
            if extra:
                archive.addfile(tarfile.TarInfo(extra), io.BytesIO())

    def test_valid_archive_with_bin_tool_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rootfs.bin"
            self.archive(path)
            STAGE.validate_archive(path, "arm64")

    def test_archive_rejects_wrong_architecture_missing_tools_and_app_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rootfs.bin"
            for kwargs in ({"abi": "x86_64"}, {"missing": "node"}, {"extra": ".provisioned"},
                           {"extra": "../escape"}):
                with self.subTest(kwargs=kwargs):
                    self.archive(path, **kwargs)
                    with self.assertRaises(ValueError):
                        STAGE.validate_archive(path, "arm64")


if __name__ == "__main__":
    unittest.main()
