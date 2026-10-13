"""Filesystem fixtures for the reset controller; no Docker daemon is required."""

from pathlib import Path
import json
import os
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch

import controller


WORLD = "11111111-1111-4111-8111-111111111111"
OTHER_WORLD = "22222222-2222-4222-8222-222222222222"
RUN = "33333333-3333-4333-8333-333333333333"
OTHER_RUN = "44444444-4444-4444-8444-444444444444"
BOOT = "55555555-5555-4555-8555-555555555555"
RUNNER = "66666666-6666-4666-8666-666666666666"
NOW = 1_800_000_000


class ResetFixtures(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        # On macOS /tmp and /var are symlink aliases. Use their real paths.
        self.root = Path(self.temporary.name).resolve()
        self.backend = controller.ResetController(self.root, "a", clock=lambda: NOW)
        self.world = self.backend.data_dir / "world"
        self.world.mkdir()
        (self.world / "level.dat").write_bytes(b"world generation one")
        for name in ("ops.json", "whitelist.json"):
            (self.backend.data_dir / name).write_text("[]")
        self.config = self.backend.data_dir / "config"
        self.config.mkdir()
        (self.config / "operator-setting.txt").write_text("preserve this setting")
        (self.backend.data_dir / "server.properties").write_text(
            "level-seed=old-seed\nlevel-name=world\nmax-players=12\n")
        self.request = {"schemaVersion": 1, "worldId": WORLD, "runId": RUN}
        self.status = {"schemaVersion": 1, "serverId": "a", "worldId": WORLD,
                       "bootId": BOOT, "phase": "RESETTING", "runId": RUN,
                       "runnerId": RUNNER, "runnerName": "Runner", "outcome": "RUNNER_DIED",
                       "playerCount": 0, "updatedAt": NOW * 1000}

    def tearDown(self):
        self.temporary.cleanup()

    def write_mailbox(self, request=None, status=None):
        self.backend.request_file.write_text(json.dumps(request or self.request))
        self.backend.status_file.write_text(json.dumps(status or self.status))

    def assert_preserved(self):
        self.assertEqual(b"world generation one", (self.world / "level.dat").read_bytes())
        self.assertFalse((self.backend.data_dir / "archive" / ("world-" + WORLD)).exists())

    def test_exact_ack_archives_only_world_and_clears_generation_mailboxes(self):
        self.write_mailbox()
        (self.backend.network_dir / "start.request.json").write_text("{}")
        (self.backend.network_dir / "reservation.json").write_text("{}")
        transaction = self.backend.prepare()
        self.assert_preserved()
        self.assertTrue(self.backend.finish(transaction, process_alive=False))
        archive = self.backend.data_dir / "archive" / ("world-" + WORLD)
        self.assertEqual(b"world generation one", (archive / "level.dat").read_bytes())
        self.assertFalse(self.world.exists())
        self.assertEqual("[]", (self.backend.data_dir / "ops.json").read_text())
        self.assertEqual("[]", (self.backend.data_dir / "whitelist.json").read_text())
        self.assertEqual("preserve this setting", (self.config / "operator-setting.txt").read_text())
        properties = (self.backend.data_dir / "server.properties").read_text()
        self.assertIn("level-seed=\n", properties)
        self.assertIn("max-players=12\n", properties)
        for name in ("status.json", "reset.request.json", "reset.transaction.json",
                     "start.request.json", "reservation.json"):
            self.assertFalse((self.backend.network_dir / name).exists())

    def test_no_request_preserves_world_on_restart(self):
        self.backend.status_file.write_text(json.dumps(self.status))
        self.assertFalse(self.backend.recover(process_alive=False))
        self.assert_preserved()

    def test_request_without_ack_never_resets(self):
        self.backend.request_file.write_text(json.dumps(self.request))
        with self.assertRaises(controller.UnsafeReset):
            self.backend.recover(process_alive=False)
        self.assert_preserved()

    def test_rejects_each_mismatching_or_malformed_ack(self):
        changes = [{"phase": "RUNNING"}, {"phase": "COMPLETED"}, {"playerCount": 1},
                   {"playerCount": False}, {"worldId": OTHER_WORLD}, {"runId": OTHER_RUN},
                   {"serverId": "b"}, {"schemaVersion": 2}, {"schemaVersion": True},
                   {"bootId": None}, {"runnerId": None}, {"runnerName": None},
                   {"outcome": None}, {"updatedAt": NOW * 1000 - 5001},
                   {"updatedAt": NOW * 1000 + 5001}, {"updatedAt": "now"}]
        for change in changes:
            with self.subTest(change=change):
                self.write_mailbox(status=self.status | change)
                with self.assertRaises(controller.UnsafeReset):
                    self.backend.prepare()
                self.assert_preserved()
                self.assertFalse(self.backend.journal_file.exists())

    def test_rejects_malformed_requests(self):
        for content in ("null", "[]", "{", '{"schemaVersion":1,"schemaVersion":1}',
                        json.dumps(self.request | {"worldId": "../../world"}),
                        json.dumps(self.request | {"runId": None})):
            with self.subTest(content=content):
                self.write_mailbox()
                self.backend.request_file.write_text(content)
                with self.assertRaises(controller.UnsafeReset):
                    self.backend.prepare()
                self.assert_preserved()

    def test_live_jvm_blocks_archive(self):
        self.write_mailbox()
        transaction = self.backend.prepare()
        with self.assertRaises(controller.UnsafeReset):
            self.backend.finish(transaction, process_alive=True)
        self.assert_preserved()

    def test_new_generation_after_ack_blocks_archive(self):
        self.write_mailbox()
        transaction = self.backend.prepare()
        self.backend.status_file.write_text(json.dumps(self.status | {"worldId": OTHER_WORLD}))
        with self.assertRaises(controller.UnsafeReset):
            self.backend.finish(transaction, process_alive=False)
        self.assert_preserved()

    def test_new_boot_after_ack_blocks_archive(self):
        self.write_mailbox()
        transaction = self.backend.prepare()
        self.backend.status_file.write_text(json.dumps(self.status | {"bootId": OTHER_WORLD}))
        with self.assertRaises(controller.UnsafeReset):
            self.backend.finish(transaction, process_alive=False)
        self.assert_preserved()

    def test_request_replaced_after_ack_blocks_archive(self):
        self.write_mailbox()
        transaction = self.backend.prepare()
        self.backend.request_file.write_text(json.dumps(self.request | {"runId": OTHER_RUN}))
        with self.assertRaises(controller.UnsafeReset):
            self.backend.finish(transaction, process_alive=False)
        self.assert_preserved()

    def test_boot_recovers_old_exact_ack_without_a_live_jvm(self):
        self.write_mailbox(status=self.status | {"updatedAt": NOW * 1000 - 60_000})
        self.assertTrue(self.backend.recover(process_alive=False))
        self.assertFalse(self.world.exists())
        self.assertTrue((self.backend.data_dir / "archive" / ("world-" + WORLD)).is_dir())

    def test_boot_recovery_rejects_live_jvm(self):
        self.write_mailbox()
        with self.assertRaises(controller.UnsafeReset):
            self.backend.recover(process_alive=True)
        self.assert_preserved()

    def test_boot_recovers_after_ack_journal_before_rename(self):
        self.write_mailbox()
        self.backend.prepare()
        self.assertTrue(self.backend.recover(process_alive=False))
        self.assertFalse(self.world.exists())

    def test_boot_recovers_after_rename_before_cleanup(self):
        self.write_mailbox()
        self.backend.prepare()
        archive_dir = self.backend.data_dir / "archive"
        archive_dir.mkdir()
        self.world.rename(archive_dir / ("world-" + WORLD))
        self.assertTrue(self.backend.recover(process_alive=False))
        self.assertFalse(self.backend.journal_file.exists())
        self.assertFalse(self.backend.recover(process_alive=False))

    def test_boot_recovers_after_mailbox_cleanup_before_journal_cleanup(self):
        self.write_mailbox()
        self.backend.prepare()
        archive_dir = self.backend.data_dir / "archive"
        archive_dir.mkdir()
        self.world.rename(archive_dir / ("world-" + WORLD))
        self.backend.request_file.unlink()
        self.backend.status_file.unlink()
        self.assertTrue(self.backend.recover(process_alive=False))
        self.assertFalse(self.backend.journal_file.exists())

    def test_existing_archive_cannot_be_overwritten(self):
        self.write_mailbox()
        archive = self.backend.data_dir / "archive" / ("world-" + WORLD)
        archive.mkdir(parents=True)
        (archive / "level.dat").write_bytes(b"previous archive")
        with self.assertRaises(controller.UnsafeReset):
            self.backend.prepare()
        self.assertEqual(b"previous archive", (archive / "level.dat").read_bytes())
        self.assertEqual(b"world generation one", (self.world / "level.dat").read_bytes())

    def test_archive_and_world_present_during_recovery_is_ambiguous(self):
        self.write_mailbox()
        self.backend.prepare()
        (self.backend.data_dir / "archive" / ("world-" + WORLD)).mkdir(parents=True)
        with self.assertRaises(controller.UnsafeReset):
            self.backend.recover(process_alive=False)
        self.assertTrue(self.world.exists())

    def test_world_symlink_cannot_be_followed(self):
        self.write_mailbox()
        self.world.rename(self.backend.data_dir / "original-world")
        external = self.root / "external-world"
        external.mkdir()
        (external / "level.dat").write_bytes(b"external")
        self.world.symlink_to(external, target_is_directory=True)
        with self.assertRaises(controller.UnsafeReset):
            self.backend.prepare()
        self.assertEqual(b"external", (external / "level.dat").read_bytes())

    def test_archive_symlink_cannot_be_followed(self):
        self.write_mailbox()
        external = self.root / "external-archive"
        external.mkdir()
        (self.backend.data_dir / "archive").symlink_to(external, target_is_directory=True)
        with self.assertRaises(controller.UnsafeReset):
            self.backend.prepare()
        self.assert_preserved()

    def test_mailbox_symlink_is_rejected(self):
        self.write_mailbox()
        external = self.root / "external-request.json"
        external.write_text(json.dumps(self.request))
        self.backend.request_file.unlink()
        self.backend.request_file.symlink_to(external)
        with self.assertRaises(controller.UnsafeReset):
            self.backend.prepare()
        self.assert_preserved()

    def test_server_id_rejects_path_traversal(self):
        for server_id in ("../a", "a/b", "a\\b", "try", "A", ""):
            with self.subTest(server_id=server_id):
                with self.assertRaises(controller.UnsafeReset):
                    controller.ResetController(self.root, server_id)

    def test_property_updates_preserve_operator_settings(self):
        path = self.backend.data_dir / "server.properties"
        path.write_text("# settings\nonline-mode=true\nonline-mode: true\nserver-port 25566\n"
                        "level-name=custom\nlevel-seed=42\nmax-players=12\n")
        controller.update_properties(self.backend.data_dir, {"online-mode": "false",
                                     "server-port": "25565", "level-name": "world"})
        result = path.read_text()
        self.assertEqual(1, result.count("online-mode"))
        self.assertEqual(1, result.count("server-port"))
        self.assertIn("max-players=12", result)
        self.assertIn("level-seed=42", result)


class EnvironmentFixtures(unittest.TestCase):
    def test_process_shutdown_waits_for_actual_exit(self):
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            self.assertIsNone(process.poll())
            controller.stop_process(process)
            self.assertIsNotNone(process.poll())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()

    def test_explicit_eula_and_private_secret_required(self):
        for environment in ({}, {"EULA": "true"},
                            {"EULA": "false", "FORWARDING_SECRET": "a" * 64},
                            {"EULA": "true", "FORWARDING_SECRET": "replace-with-a-private-random-secret"},
                            {"EULA": "true", "FORWARDING_SECRET": "linkedhardcore-dev-secret-change-me"},
                            {"EULA": "true", "FORWARDING_SECRET": "a" * 64, "MC_MEMORY": "3G -bad"}):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                with self.assertRaises(ValueError):
                    controller.validate_environment()
        with patch.dict(os.environ, {"EULA": "true", "FORWARDING_SECRET": "a" * 64,
                                     "MC_MEMORY": "512M"}, clear=True):
            self.assertEqual(("a" * 64, "512M"), controller.validate_environment())


if __name__ == "__main__":
    unittest.main()
