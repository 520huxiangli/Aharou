import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "preinstalled_rootfs.py"
SPEC = importlib.util.spec_from_file_location("preinstalled_rootfs", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def archive(path, extra=(), wrapper="", marker=None):
    mode = "w:xz" if str(path).endswith(".xz") else "w:gz"
    with tarfile.open(path, mode) as output:
        for name in ("etc", "usr", "usr/bin"):
            item = tarfile.TarInfo(wrapper + name)
            item.type = tarfile.DIRTYPE
            output.addfile(item)
        files = [("etc/os-release", b"ID=debian\n")]
        if marker is not None:
            files.append((MODULE.MARKER, json.dumps(marker).encode()))
        for name, data in files:
            item = tarfile.TarInfo(wrapper + name)
            item.size = len(data)
            output.addfile(item, io.BytesIO(data))
        for name, kind, value in extra:
            item = tarfile.TarInfo(wrapper + name)
            item.type = kind
            if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                item.linkname = value
                output.addfile(item)
            elif kind == tarfile.REGTYPE:
                item.size = len(value)
                output.addfile(item, io.BytesIO(value))
            else:
                output.addfile(item)


class ArchiveSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name, "source.tar.xz")
        self.target = Path(self.temp.name, "result.tar.gz")

    def rewrite(self):
        MODULE._rewrite(self.source, self.target, "w:gz", True)

    def test_normalizes_pd_wrapper_without_extracting(self):
        archive(self.source, wrapper="debian-trixie-aarch64/", extra=[
            ("usr/bin/tool", tarfile.REGTYPE, b"tool"),
            ("bin", tarfile.SYMTYPE, "usr/bin"),
            ("usr/bin/alias", tarfile.LNKTYPE, "debian-trixie-aarch64/usr/bin/tool"),
        ])
        with patch.object(tarfile.TarFile, "extractall", side_effect=AssertionError("host extraction")):
            self.rewrite()
        with tarfile.open(self.target) as result:
            self.assertEqual(result.getmember("usr/bin/alias").linkname, "usr/bin/tool")
            self.assertEqual(result.getmember("bin").linkname, "usr/bin")
            self.assertNotIn("debian-trixie-aarch64/etc", result.getnames())

    def test_removes_host_markers_but_keeps_database_and_licenses(self):
        archive(self.source, extra=[
            (".installed", tarfile.REGTYPE, b"yes"),
            (".installed_custom", tarfile.REGTYPE, b"yes"),
            (".provisioned", tarfile.REGTYPE, b"yes"),
            ("var/lib/dpkg/status", tarfile.REGTYPE, b"packages"),
            ("usr/share/doc/tool/copyright", tarfile.REGTYPE, b"license"),
        ])
        self.rewrite()
        with tarfile.open(self.target) as result:
            names = result.getnames()
            self.assertFalse(MODULE.HOST_MARKERS.intersection(names))
            self.assertIn("var/lib/dpkg/status", names)
            self.assertIn("usr/share/doc/tool/copyright", names)

    def test_docker_runtime_markers_are_not_distributed(self):
        with tarfile.open(self.source, "w:xz") as output:
            for name in (".dockerenv", ".dockerinit"):
                output.addfile(tarfile.TarInfo(name))
            directory = tarfile.TarInfo("etc")
            directory.type = tarfile.DIRTYPE
            output.addfile(directory)
        self.rewrite()
        with tarfile.open(self.target) as result:
            self.assertEqual(result.getnames(), ["etc"])

    def test_rejects_traversal_special_files_and_unsafe_links(self):
        cases = [
            [("../escape", tarfile.REGTYPE, b"x")],
            [("/absolute", tarfile.REGTYPE, b"x")],
            [("bad", tarfile.SYMTYPE, "../escape")],
            [("bad", tarfile.LNKTYPE, "missing")],
            [("bad", tarfile.CHRTYPE, b"")],
            [("bad", tarfile.FIFOTYPE, b"")],
            [("bin", tarfile.SYMTYPE, "/usr/bin"), ("bin/overwrite", tarfile.REGTYPE, b"x")],
            [("alias", tarfile.SYMTYPE, "."), ("bad", tarfile.SYMTYPE, "alias/../escape")],
            [("a", tarfile.SYMTYPE, "b"), ("b", tarfile.SYMTYPE, "a")],
            [("etc/os-release", tarfile.REGTYPE, b"duplicate")],
        ]
        for extra in cases:
            with self.subTest(extra=extra):
                archive(self.source, extra=extra)
                with self.assertRaises(ValueError):
                    self.rewrite()

    def test_absolute_in_root_symlink_is_allowed(self):
        archive(self.source, extra=[("bin", tarfile.SYMTYPE, "/usr/bin")])
        self.rewrite()

    def test_virtual_devices_are_omitted_without_host_extraction(self):
        archive(self.source, extra=[
            ("dev", tarfile.DIRTYPE, b""),
            ("dev/null", tarfile.CHRTYPE, b""),
            ("dev/disk", tarfile.BLKTYPE, b""),
            ("dev/pipe", tarfile.FIFOTYPE, b""),
            ("proc", tarfile.DIRTYPE, b""),
            ("sys", tarfile.DIRTYPE, b""),
        ])
        self.rewrite()
        with tarfile.open(self.target) as result:
            names = result.getnames()
            self.assertIn("dev", names)
            self.assertIn("proc", names)
            self.assertIn("sys", names)
            self.assertNotIn("dev/null", names)
            self.assertNotIn("dev/disk", names)
            self.assertNotIn("dev/pipe", names)

    def test_usrmerge_links_and_hardlinks_preserve_root_layout(self):
        archive(self.source, extra=[
            ("bin", tarfile.SYMTYPE, "usr/bin"),
            ("usr/bin/tool", tarfile.REGTYPE, b"tool"),
            ("usr/bin/alias", tarfile.LNKTYPE, "./usr/bin/tool"),
            ("usr/bin/selected", tarfile.SYMTYPE, "/etc/alternatives/tool"),
            ("etc/alternatives/tool", tarfile.SYMTYPE, "/usr/bin/tool"),
        ])
        self.rewrite()
        with tarfile.open(self.target) as result:
            self.assertEqual(result.getmember("usr/bin/alias").linkname, "usr/bin/tool")
            self.assertEqual(result.getmember("usr/bin/selected").linkname, "/etc/alternatives/tool")

    def test_wrapper_hardlink_cannot_escape_wrapper(self):
        archive(self.source, wrapper="debian-trixie-aarch64/", extra=[
            ("bad", tarfile.LNKTYPE, "etc/os-release"),
        ])
        with self.assertRaises(ValueError):
            self.rewrite()


class BuildRootfsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name, "source.tar.gz")
        self.target = Path(self.temp.name, "preinstalled.tar.gz")
        archive(self.source)
        self.calls = []
        self.exit_code = b"0\n"
        self.fail_action = None
        self.export_marker = self.marker()

    @staticmethod
    def marker(profile="general", distro="debian", abi="arm64"):
        return {"schemaVersion": 1, "profile": profile, "distro": distro,
                "abi": abi, "recipeRevision": "1"}

    def docker(self, command, **kwargs):
        self.calls.append((command, kwargs))
        action = command[1]
        if action == self.fail_action:
            raise subprocess.CalledProcessError(1, command)
        if action == "export":
            archive(Path(command[command.index("--output") + 1]), marker=self.export_marker)
        stdout = self.exit_code if action == "inspect" else b"ok\n"
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr=b"")

    def build(self, distro="debian", abi="arm64", profile="general"):
        with patch.object(MODULE.shutil, "which", return_value="/usr/bin/docker"), \
                patch.object(MODULE.subprocess, "run", side_effect=self.docker):
            return MODULE.build_rootfs(self.source, self.target, distro, abi, profile)

    def test_output_contract_platform_and_isolation(self):
        with patch.dict(os.environ, {"CNB_TOKEN": "do-not-forward", "GITHUB_TOKEN": "private"}):
            result = self.build()
        self.assertEqual(result, {"profile": "general", "recipeRevision": "1",
                                  "sha256": hashlib.sha256(self.target.read_bytes()).hexdigest(),
                                  "sizeBytes": self.target.stat().st_size})
        for command, kwargs in self.calls:
            self.assertNotIn("CNB_TOKEN", kwargs["env"])
            self.assertNotIn("GITHUB_TOKEN", kwargs["env"])
            self.assertFalse({"--privileged", "--volume", "-v", "--mount"}.intersection(command))
        for action in ("import", "create"):
            command = next(command for command, _ in self.calls if command[1] == action)
            self.assertEqual(command[command.index("--platform") + 1], "linux/arm64")
        create = next(command for command, _ in self.calls if command[1] == "create")
        self.assertIn("-i", create)
        self.assertIn("/usr/bin/env", create)
        start = next(kwargs for command, kwargs in self.calls if command[1] == "start")
        self.assertEqual(start["input"], MODULE.PREINSTALL_SCRIPT.read_bytes())
        self.assertEqual([command[1] for command, _ in self.calls][-2:], ["rm", "image"])

    def test_napcat_x86_64(self):
        self.export_marker = self.marker(profile="napcat", abi="x86_64")
        self.assertEqual(self.build(abi="x86_64", profile="napcat")["profile"], "napcat")
        create = next(command for command, _ in self.calls if command[1] == "create")
        self.assertIn("linux/amd64", create)

    def test_failed_step_always_cleans_only_current_resources(self):
        for action in ("import", "create", "start", "export"):
            with self.subTest(action=action):
                self.calls.clear()
                self.fail_action = action
                with self.assertRaises(subprocess.CalledProcessError):
                    self.build()
                self.assertFalse(self.target.exists())
                commands = [command for command, _ in self.calls]
                self.assertEqual(commands[-2][1:3], ["rm", "--force"])
                self.assertEqual(commands[-1][1:4], ["image", "rm", "--force"])
                name = commands[-2][-1]
                self.assertTrue(name.startswith("aharou-rootfs-"))
                self.assertEqual(commands[-1][-1], name + ":build")
                self.assertFalse(any("prune" in command for command in commands))

    def test_build_names_are_unique(self):
        self.build()
        self.target = Path(self.temp.name, "second.tar.gz")
        self.build()
        creates = [command for command, _ in self.calls if command[1] == "create"]
        names = [command[command.index("--name") + 1] for command in creates]
        self.assertEqual(len(set(names)), 2)

    def test_unsafe_source_never_reaches_docker(self):
        archive(self.source, extra=[("../escape", tarfile.REGTYPE, b"bad")])
        with self.assertRaises(ValueError):
            self.build()
        self.assertEqual(self.calls, [])

    def test_missing_export_marker_is_rejected(self):
        self.export_marker = None
        with self.assertRaises(ValueError):
            self.build()
        self.assertFalse(self.target.exists())

    def test_cleanup_failure_does_not_publish_artifact(self):
        def failed_cleanup(command, **kwargs):
            if command[1] == "rm":
                self.calls.append((command, kwargs))
                return subprocess.CompletedProcess(command, 1, stdout=b"", stderr=b"daemon failed")
            return self.docker(command, **kwargs)

        with patch.object(MODULE.shutil, "which", return_value="/usr/bin/docker"), \
                patch.object(MODULE.subprocess, "run", side_effect=failed_cleanup):
            with self.assertRaisesRegex(RuntimeError, "清理失败"):
                MODULE.build_rootfs(self.source, self.target, "debian", "arm64", "general")
        self.assertEqual(self.calls[-1][0][1], "image")
        self.assertFalse(self.target.exists())

    def test_nonzero_guest_exit_is_not_published(self):
        self.exit_code = b"2\n"
        with self.assertRaises(RuntimeError):
            self.build()
        self.assertFalse(self.target.exists())

    def test_invalid_export_marker_is_not_published(self):
        self.export_marker["abi"] = "x86_64"
        with self.assertRaises(ValueError):
            self.build()
        self.assertFalse(self.target.exists())

    def test_invalid_arguments_and_existing_output_never_run_docker(self):
        with patch.object(MODULE.subprocess, "run") as run:
            for distro, abi, profile in (("arch", "arm64", "general"),
                                        ("debian", "aarch64", "general"),
                                        ("alpine", "arm64", "napcat"),
                                        ("debian", "arm64", "unknown")):
                with self.assertRaises(ValueError):
                    MODULE.build_rootfs(self.source, self.target, distro, abi, profile)
            self.target.write_bytes(b"original")
            with self.assertRaises(ValueError):
                MODULE.build_rootfs(self.source, self.target, "debian", "arm64", "general")
            self.assertEqual(self.target.read_bytes(), b"original")
            run.assert_not_called()

    def test_missing_docker_fails_without_installing(self):
        with patch.object(MODULE.shutil, "which", return_value=None), \
                patch.object(MODULE.subprocess, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "缺少 Docker"):
                MODULE.build_rootfs(self.source, self.target, "debian", "arm64", "general")
            run.assert_not_called()

    def test_cli_requires_explicit_build(self):
        arguments = ["--source", str(self.source), "--destination", str(self.target),
                     "--distro", "debian", "--abi", "arm64", "--profile", "general"]
        with patch.object(MODULE, "build_rootfs", return_value={}) as build, \
                patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(MODULE.main(arguments), 0)
            build.assert_not_called()
            self.assertEqual(MODULE.main(arguments + ["--build"]), 0)
            build.assert_called_once_with(self.source, self.target, "debian", "arm64", "general")


if __name__ == "__main__":
    unittest.main()
