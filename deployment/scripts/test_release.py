"""Локальные проверки release без GHCR, SSH, контейнеров и изменений БД."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("candidates", HERE / "release-candidates.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class Versions(unittest.TestCase):
    def test_xml_ignores_dependency_and_parent_versions(self):
        xml = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <parent><version>0.1.0-SNAPSHOT</version></parent>
          <dependencies><dependency><version>99</version></dependency></dependencies>
          <version>0.1.1</version></project>'''
        self.assertEqual(release.version(xml), "0.1.1")
        xml = xml.replace("<version>0.1.1</version>", "")
        self.assertEqual(release.version(xml), "")
        self.assertEqual(release.version(xml, inherited=True), "0.1.0-SNAPSHOT")

    def test_first_release(self):
        self.assertTrue(release.increased("0.1.0-SNAPSHOT", "0.1.1"))
        self.assertTrue(release.increased("0.1.1-SNAPSHOT", "0.1.1"))
        self.assertTrue(release.increased(None, "0.1.1"))

    def test_regular_release(self):
        self.assertTrue(release.increased("0.1.9", "0.1.10"))
        self.assertFalse(release.increased("0.1.1", "0.1.1"))

    def test_invalid_or_lower_versions(self):
        for old, new in [("0.2", "0.1"), ("0.2-SNAPSHOT", "0.1"),
                         ("1", "1.0"), ("0.1", ""), ("0.1", "0.2-SNAPSHOT")]:
            with self.subTest(old=old, new=new), self.assertRaises(ValueError):
                release.increased(old, new)


DOCKER = r'''#!/usr/bin/env bash
echo "$*" >> "$MOCK_LOG"
if [[ "$1" == inspect ]]; then
  if [[ "${@: -1}" == "${UNHEALTHY_CONTAINER:-}" ]]; then echo unhealthy; else echo healthy; fi
  exit 0
fi
if [[ "$1" == compose ]]; then
  shift 5
  case "$1" in
    ps) if [[ "${2:-}" == -q ]]; then echo "container-$3"; fi ;;
    pull) if [[ "${FAIL_PULL:-}" == "$2" ]]; then exit 19; fi ;;
    up) if [[ "${FAIL_SERVICE:-}" == "${@: -1}" ]]; then exit 17; fi ;;
  esac
fi
'''


class Deployment(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fitness-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "app"
        self.stage = self.root / "stage"
        self.bin = self.root / "bin"
        for path in (self.app, self.stage, self.bin):
            path.mkdir()
        self.bash = os.environ.get("RELEASE_TEST_BASH", "bash")
        self.env = os.environ.copy()
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env["PATH"]
        self.env["MOCK_LOG"] = (self.root / "docker.log").as_posix()
        self.write(self.bin / "docker", DOCKER)
        # Git Bash не содержит flock. В Linux используется настоящий flock.
        if os.name == "nt":
            self.write(self.bin / "flock", "#!/usr/bin/env bash\nexit 0\n")
        self.services = list(release.SERVICES)
        self.write(self.app / "compose.env", "".join(
            f"{s.removesuffix('-service').replace('-', '_').upper()}_IMAGE=ghcr.io/returnaction/fitness-{s}:0.1.1\n"
            for s in self.services))
        self.write(self.stage / "compose.yaml", "new compose\n")
        self.plan(self.services)

    def write(self, path, text):
        path.write_text(text, encoding="utf-8", newline="\n")
        path.chmod(0o700)

    def plan(self, services, version="0.1.2"):
        self.write(self.stage / "candidates.tsv", "".join(f"{s}\t{version}\n" for s in services))

    def run_deploy(self, number="2"):
        result = subprocess.run([self.bash, str(HERE / "deploy-release.sh"),
                                 self.app.as_posix(), self.stage.as_posix(), number],
                                env=self.env, capture_output=True, text=True, encoding="utf-8")
        log = self.root / "docker.log"
        return result, log.read_text() if log.exists() else ""

    def test_order_and_success(self):
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        launched = [line.split()[-1] for line in log.splitlines() if " up " in line]
        self.assertEqual(launched, self.services)
        self.assertEqual(log.count("image prune"), len(self.services))
        self.assertLess(launched.index("exercise-catalog-service"), launched.index("gateway-service"))
        self.assertIn("EXERCISE_CATALOG_IMAGE=ghcr.io/returnaction/fitness-exercise-catalog-service:0.1.2",
                      (self.app / "compose.env").read_text())

    def test_failure_stops_remaining_services_without_rollback(self):
        self.env["FAIL_SERVICE"] = "coins-service"
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertNotIn("pull identity-service", log)
        self.assertIn("logs --tail=200 coins-service", log)
        self.assertEqual(log.count("image prune"), 1)
        self.assertNotIn("down", log)

    def test_pull_failure_stops_release(self):
        self.env["FAIL_PULL"] = "accounts-service"
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 19)
        self.assertNotIn(" up ", log)
        self.assertNotIn("image prune", log)

    def test_single_service_checks_existing_dependency(self):
        self.plan(["gateway-service"])
        self.env["UNHEALTHY_CONTAINER"] = "container-identity-service"
        result, log = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("pull gateway-service", log)

    def test_gateway_waits_for_healthy_catalog(self):
        self.plan(["gateway-service"])
        self.env["UNHEALTHY_CONTAINER"] = "container-exercise-catalog-service"
        result, log = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("pull gateway-service", log)

    def test_old_version_and_compose_cannot_replace_newer(self):
        self.plan(["accounts-service"], "0.1.0")
        self.write(self.app / ".compose-run", "3\n")
        self.write(self.app / "compose.yaml", "newer compose\n")
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("pull accounts-service", log)
        self.assertEqual((self.app / "compose.yaml").read_text(), "newer compose\n")

    def test_equal_version_can_retry_failed_first_start(self):
        self.plan(["accounts-service"], "0.1.1")
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("pull accounts-service", log)

    def test_only_selected_service_changes(self):
        self.plan(["accounts-service"])
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        values = (self.app / "compose.env").read_text()
        self.assertIn("fitness-accounts-service:0.1.2", values)
        for service in self.services[1:]:
            self.assertIn(f"fitness-{service}:0.1.1", values)
            self.assertNotIn(f"pull {service}", log)

    def test_older_run_can_deliver_service_not_changed_by_newer_run(self):
        self.write(self.app / ".compose-run", "3\n")
        self.write(self.app / "compose.yaml", "newer compose\n")
        self.plan(["accounts-service"])
        result, log = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("pull accounts-service", log)
        self.assertEqual((self.app / "compose.yaml").read_text(), "newer compose\n")

    def test_invalid_order_rejected_before_changes(self):
        self.plan(list(reversed(self.services)))
        result, log = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(" up ", log)
        self.assertNotIn(" pull ", log)
        self.assertFalse((self.app / "compose.yaml").exists())


if __name__ == "__main__":
    unittest.main()
