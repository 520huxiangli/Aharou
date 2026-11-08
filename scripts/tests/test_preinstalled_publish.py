import copy
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import preinstalled_publish as publishing


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


container_sync = load_script("container_sync_preinstalled_test", "sync-container-images.py")
napcat_sync = load_script("napcat_sync_preinstalled_test", "sync-napcat-assets.py")


def entry():
    return {"id": "alpine-3.23", "distro": "alpine", "version": "3.23.0",
            "path": "alpine/v3.23/releases/{abi}/alpine-minirootfs-3.23.0-{abi}.tar.gz",
            "paths": {"aharou": "old-{abi}.tar.gz"},
            "abiNames": {"arm64": "aarch64", "x86_64": "x86_64"},
            "sizeBytes": 11, "sha256ByAbi": {"arm64": "a" * 64, "x86_64": "b" * 64}}


class NetworkIsolatedTestCase(unittest.TestCase):
    def setUp(self):
        network = patch("urllib.request.urlopen", side_effect=AssertionError("UnexpectedNetworkAccess"))
        network.start()
        self.addCleanup(network.stop)


class PreinstalledPublishTest(NetworkIsolatedTestCase):
    def test_preinstall_dry_run_never_builds_or_contacts_network(self):
        sync = Mock()
        with patch.object(publishing, "recipe_revision") as revision, patch.object(publishing, "checked_build") as build:
            self.assertEqual(publishing.run_general(sync, SimpleNamespace(apply=False), {}, [entry()]), 0)
        self.assertEqual(sync.mock_calls, [])
        revision.assert_not_called()
        build.assert_not_called()

    def test_container_cli_routes_preinstall_before_online_probes(self):
        data = {"images": [entry()]}
        with patch.object(sys, "argv", ["sync-container-images.py", "--preinstall"]), \
                patch.object(container_sync, "load_manifests", return_value=data), \
                patch.object(container_sync, "probe_versions", autospec=True) as versions, \
                patch.object(container_sync, "sync_eol_fields", autospec=True) as eols, \
                patch.object(container_sync, "report_unlisted_series", autospec=True) as series, \
                patch.object(container_sync, "backfill_checksums", autospec=True) as backfills, \
                patch.object(container_sync, "save_manifests", autospec=True) as save:
            self.assertEqual(container_sync.main(), 0)
        versions.assert_not_called()
        eols.assert_not_called()
        series.assert_not_called()
        backfills.assert_not_called()
        save.assert_not_called()

    def test_normal_apply_preserves_preinstalled_entry(self):
        protected = entry()
        protected.update(preinstalledSources={"aharou": {"profile": "general", "recipeRevision": "1"}},
                         preinstalledPaths={"aharou": "derived-{abi}.tar.gz"},
                         sourceSha256ByAbi={"aharou": {"arm64": "c" * 64, "x86_64": "d" * 64}},
                         sourceSizeBytesByAbi={"aharou": {"arm64": 50, "x86_64": 60}})
        before = copy.deepcopy(protected)
        with patch.object(sys, "argv", ["sync-container-images.py", "--apply"]), \
                patch.object(container_sync, "load_manifests", return_value={"images": [protected]}), \
                patch.object(container_sync, "probe_versions", autospec=True,
                             return_value=({"version": protected["version"]}, None)) as versions, \
                patch.object(container_sync, "probe_artifacts", autospec=True) as artifacts, \
                patch.object(container_sync, "sync_eol_fields", autospec=True, return_value=[]), \
                patch.object(container_sync, "report_unlisted_series", autospec=True), \
                patch.object(container_sync, "backfill_checksums", autospec=True, return_value=[]), \
                patch.object(container_sync, "save_manifests", autospec=True) as save:
            self.assertEqual(container_sync.main(), 0)
        self.assertEqual(protected, before)
        versions.assert_called_once_with(protected)
        artifacts.assert_not_called()
        save.assert_not_called()

    def _apply_original_update(self, image, upload_results):
        payload = b"new official archive"
        hashes = {abi: hashlib.sha256(payload).hexdigest() for abi in publishing.ABIS}
        info = {"version": "3.23.1", "urls": {abi: f"https://example.invalid/{abi}" for abi in publishing.ABIS},
                "sizes": {abi: len(payload) for abi in publishing.ABIS}}

        def download(url, destination):
            Path(destination).write_bytes(payload)
            return True

        with patch.object(container_sync, "ensure_release", autospec=True, return_value={"id": "r", "assets": []}), \
                patch.object(container_sync, "fetch_checksums", autospec=True, return_value=hashes), \
                patch.object(container_sync, "download", autospec=True, side_effect=download), \
                patch.object(container_sync, "upload", autospec=True, side_effect=upload_results) as upload:
            result = container_sync.apply_changes([{"entry": image, "info": info}])
        self.assertEqual(upload.call_count, 2)
        return result, hashes

    def test_new_original_success_clears_only_its_preinstalled_source(self):
        image = entry()
        keys = ("preinstalledPaths", "sourceSha256ByAbi", "sourceSizeBytesByAbi", "preinstalledSources")
        for key in keys:
            image[key] = {"aharou": {"old": key}, "other": {"keep": key}}
        (applied, failed), hashes = self._apply_original_update(image, [True, True])
        self.assertEqual(len(applied), 1)
        self.assertEqual(failed, [])
        self.assertEqual(image["version"], "3.23.1")
        self.assertEqual(image["path"], container_sync.path_for(image, "3.23.1"))
        self.assertEqual(image["sha256ByAbi"], hashes)
        for key in keys:
            self.assertEqual(image[key], {"other": {"keep": key}})

    def test_second_original_upload_failure_preserves_all_preinstalled_metadata(self):
        image = entry()
        for key in ("preinstalledPaths", "sourceSha256ByAbi", "sourceSizeBytesByAbi", "preinstalledSources"):
            image[key] = {"aharou": {"old": key}, "other": {"keep": key}}
        before = copy.deepcopy(image)
        (applied, failed), hashes = self._apply_original_update(image, [True, False])
        self.assertEqual(applied, [])
        self.assertEqual(len(failed), 1)
        self.assertEqual(image, before)

    def test_recipe_change_rebuilds_same_upstream_version(self):
        image = entry()
        image.update(preinstalledSources={"aharou": {"profile": "general", "recipeRevision": "1"}},
                     sourceSha256ByAbi={"aharou": {"arm64": "c" * 64, "x86_64": "d" * 64}},
                     sourceSizeBytesByAbi={"aharou": {"arm64": 50, "x86_64": 60}})
        image["preinstalledPaths"] = {"aharou": "derived-{abi}.tar.gz"}
        info = {"version": image["version"]}
        self.assertTrue(publishing.current_preinstalled(image, info, "1"))
        self.assertFalse(publishing.current_preinstalled(image, info, "2"))

    def test_dual_arch_publishing_keeps_upstream_hashes_separate(self):
        image = entry()
        original = copy.deepcopy(image)
        payload = b"official archive"
        hashes = {abi: hashlib.sha256(payload).hexdigest() for abi in publishing.ABIS}
        info = {"version": image["version"], "urls": {abi: f"https://official.invalid/{abi}" for abi in publishing.ABIS},
                "sizes": {abi: len(payload) for abi in publishing.ABIS}}
        sync = Mock()

        def download(url, destination):
            Path(destination).write_bytes(payload)
            return True

        def build(source, destination, distro, abi, profile):
            destination.write_bytes(f"preinstalled-{abi}".encode())
            return {"profile": profile, "recipeRevision": "1", "sha256": publishing.file_digest(destination),
                    "sizeBytes": destination.stat().st_size}

        def verify(*args):
            self.assertEqual(image, original)

        sync.download.side_effect = download
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(publishing, "recipe_revision", return_value="1"), \
                patch.object(publishing, "checked_build", side_effect=build), \
                patch.object(publishing, "publish_general", side_effect=verify) as publish:
            publishing.publish_general_entry(sync, image, info, hashes, {"id": "release"}, Path(temporary))
        self.assertEqual(publish.call_count, 2)
        self.assertEqual(image["path"], original["path"])
        self.assertEqual(image["sha256ByAbi"], original["sha256ByAbi"])
        self.assertEqual(image["paths"], original["paths"])
        self.assertEqual(image["version"], original["version"])
        self.assertEqual(image["sizeBytes"], original["sizeBytes"])
        self.assertNotEqual(image["sourceSha256ByAbi"]["aharou"], hashes)
        self.assertIn("-general-r1-", image["preinstalledPaths"]["aharou"])
        self.assertIn("{abi}", image["preinstalledPaths"]["aharou"])

    def test_published_base_uses_existing_cnb_bytes_not_new_upstream(self):
        image = entry()
        data = {"sources": {"aharou": {"distros": {"alpine": "https://example.invalid/container-images"}}}}
        sync = Mock()
        sync.http_size.return_value = 42
        info, hashes = publishing.published_base(sync, data, image)
        self.assertEqual(info["version"], image["version"])
        self.assertEqual(hashes, image["sha256ByAbi"])
        self.assertEqual(info["urls"]["arm64"], "https://example.invalid/container-images/old-aarch64.tar.gz")
        sync.probe_versions.assert_not_called()
        sync.probe_artifacts.assert_not_called()

    def test_missing_base_digest_never_accepts_a_newer_upstream(self):
        image = entry()
        image.pop("sha256ByAbi")
        sync = Mock()
        sync.probe_versions.return_value = ({"version": "3.23.9"}, None)
        with self.assertRaisesRegex(RuntimeError, "PublishedBaseChecksumUnavailable"):
            publishing.published_base(sync, {}, image)
        sync.fetch_checksums.assert_not_called()
        sync.http_size.assert_not_called()

    def test_second_upload_failure_does_not_modify_manifest_entry(self):
        image = entry()
        original = copy.deepcopy(image)
        payload = b"base"
        hashes = {abi: hashlib.sha256(payload).hexdigest() for abi in publishing.ABIS}
        info = {"version": image["version"], "urls": {abi: abi for abi in publishing.ABIS},
                "sizes": {abi: len(payload) for abi in publishing.ABIS}}
        sync = Mock()
        sync.download.side_effect = lambda url, path: (Path(path).write_bytes(payload) or True)

        def build(source, destination, distro, abi, profile):
            destination.write_bytes(b"prepared")
            return {"profile": profile, "recipeRevision": "1", "sha256": "c" * 64, "sizeBytes": 8}

        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(publishing, "recipe_revision", return_value="1"), \
                patch.object(publishing, "checked_build", side_effect=build), \
                patch.object(publishing, "publish_general", side_effect=[None, RuntimeError("RemoteContentMismatch")]):
            with self.assertRaisesRegex(RuntimeError, "RemoteContentMismatch"):
                publishing.publish_general_entry(sync, image, info, hashes, {"id": "release"}, Path(temporary))
        self.assertEqual(image, original)

    def test_publish_is_no_overwrite_and_verifies_remote_bytes(self):
        sync = SimpleNamespace(CNB_OWNER="owner", CNB_REPO="repo", CNB_TAG="tag", upload=Mock(return_value=True))
        with patch.object(publishing, "verify_remote") as verify:
            publishing.publish_general(sync, {"id": "r", "assets": []}, Path("immutable.tar.gz"),
                                       {"sha256": "a" * 64, "sizeBytes": 40})
        sync.upload.assert_called_once_with("r", "immutable.tar.gz", overwrite=False)
        verify.assert_called_once()
        self.assertEqual(verify.call_args.args[2:], ("a" * 64, 40))

    def test_remote_digest_mismatch_is_fatal(self):
        response = io.BytesIO(b"wrong")
        response.status = 200
        sync = SimpleNamespace(http_size=Mock(return_value=5), UA="test")
        with patch.object(publishing.urllib.request, "urlopen", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "RemoteContentMismatch"):
                publishing.verify_remote(sync, "https://example.invalid/asset", "a" * 64, 5)

    def test_builder_metadata_is_verified_against_actual_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "built.tar.gz"
            destination.write_bytes(b"actual")
            builder = SimpleNamespace(RECIPE_REVISION="1", build_rootfs=Mock(return_value={
                "profile": "general", "recipeRevision": "1", "sha256": "a" * 64, "sizeBytes": 6}))
            with patch.dict(sys.modules, {"preinstalled_rootfs": builder}):
                with self.assertRaisesRegex(RuntimeError, "PreinstalledBuildMetadataMismatch"):
                    publishing.checked_build(Path("source"), destination, "alpine", "arm64", "general")


class NapcatPreinstalledPublishTest(NetworkIsolatedTestCase):
    def test_preinstall_dry_run_does_not_download_build_or_publish(self):
        with patch.object(sys, "argv", ["sync-napcat-assets.py", "--preinstall"]), \
                patch.object(napcat_sync, "check_rootfs_constants"), \
                patch.object(napcat_sync, "download") as download, \
                patch.object(napcat_sync, "load_sync") as load, \
                patch.object(napcat_sync, "checked_build") as build:
            self.assertEqual(napcat_sync.main(), 0)
        download.assert_not_called()
        load.assert_not_called()
        build.assert_not_called()

    def test_preinstalled_assets_use_new_tag_and_independent_digests(self):
        downloaded = [(item, Path(item["name"]), item["expected"], 10)
                      for item in napcat_sync.assets() if "expected" in item]
        sync = Mock()
        sync.get_cnb_release.return_value = None
        sync.ensure_release.return_value = {"id": "new-release"}

        def build(source, destination, distro, abi, profile):
            destination.write_bytes(abi.encode())
            return {"profile": profile, "recipeRevision": "1", "sha256": publishing.file_digest(destination),
                    "sizeBytes": destination.stat().st_size}

        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(napcat_sync, "recipe_revision", return_value="1"), \
                patch.object(napcat_sync, "checked_build", side_effect=build) as build_call, \
                patch.object(napcat_sync, "publish", side_effect=lambda sync, rel, path, digest, size, tag:
                             f"https://example.invalid/{tag}/{path.name}") as publish:
            records, release, tag = napcat_sync.publish_preinstalled_rootfs(sync, downloaded, Path(temporary))
        self.assertNotEqual(tag, napcat_sync.TAG)
        self.assertIn("-preinstalled-r1-", tag)
        self.assertEqual({record["architecture"] for record in records}, {"aarch64", "x86_64"})
        self.assertEqual([call.args[3] for call in build_call.call_args_list], ["arm64", "x86_64"])
        self.assertEqual(publish.call_count, 2)
        for record in records:
            self.assertEqual(record["profile"], "napcat")
            self.assertNotEqual(record["sha256"], napcat_sync.ROOTFS_SHA[record["architecture"]])
            self.assertIn(tag, record["url"])
            self.assertTrue(record["source"].startswith("https://github.com/termux/proot-distro/"))

    def test_second_preinstalled_upload_failure_returns_no_records(self):
        downloaded = [(item, Path(item["name"]), item["expected"], 10)
                      for item in napcat_sync.assets() if "expected" in item]
        sync = Mock()
        sync.get_cnb_release.return_value = {"id": "release"}
        metadata = {"profile": "napcat", "recipeRevision": "1", "sha256": "a" * 64, "sizeBytes": 100}
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(napcat_sync, "recipe_revision", return_value="1"), \
                patch.object(napcat_sync, "checked_build", return_value=metadata), \
                patch.object(napcat_sync, "publish", side_effect=["https://example.invalid/first", RuntimeError("Failed")]):
            with self.assertRaisesRegex(RuntimeError, "Failed"):
                napcat_sync.publish_preinstalled_rootfs(sync, downloaded, Path(temporary))


if __name__ == "__main__":
    unittest.main()
