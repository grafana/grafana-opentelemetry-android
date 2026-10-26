import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check-release-ref.sh").resolve()


class ReleaseRefTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.cwd = Path(self.directory.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Release test")
        (self.cwd / "version.txt").write_text("0.1.0\n")
        self.git("add", "version.txt")
        self.git("commit", "-m", "Release fixture")
        self.sha = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.sha)
        self.git("tag", "v0.1.0")
        self.env = dict(os.environ, GITHUB_REPOSITORY="grafana/grafana-opentelemetry-android",
                        MAVEN_CENTRAL_ENABLED="true", GITHUB_REF="refs/tags/v0.1.0", GITHUB_SHA=self.sha)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.cwd, stderr=subprocess.DEVNULL, text=True).strip()

    def check(self, success):
        result = subprocess.run(["bash", str(SCRIPT)], cwd=self.cwd, env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)

    def test_matching_tag_on_main(self):
        self.check(True)

    def test_disabled_upload(self):
        self.env.pop("MAVEN_CENTRAL_ENABLED")
        self.check(False)

    def test_fork(self):
        self.env["GITHUB_REPOSITORY"] = "someone/fork"
        self.check(False)

    def test_branch_instead_of_tag(self):
        self.env["GITHUB_REF"] = "refs/heads/main"
        self.check(False)

    def test_version_mismatch(self):
        (self.cwd / "version.txt").write_text("0.2.0\n")
        self.check(False)

    def test_unreleased_versions(self):
        for version in ("0.0.0-dev", "1.0.0-SNAPSHOT", "1.0.0-rc.1", "01.2.3", "../1.0.0"):
            with self.subTest(version=version):
                (self.cwd / "version.txt").write_text(version)
                self.env["GITHUB_REF"] = f"refs/tags/v{version}"
                self.check(False)

    def test_checkout_mismatch(self):
        self.env["GITHUB_SHA"] = "0" * 40
        self.check(False)

    def test_tag_moved(self):
        self.git("commit", "--allow-empty", "-m", "Moved tag")
        self.git("tag", "-f", "v0.1.0")
        self.git("checkout", "--detach", self.sha)
        self.check(False)

    def test_tag_outside_main(self):
        self.git("commit", "--allow-empty", "-m", "Unmerged commit")
        self.git("tag", "-f", "v0.1.0")
        self.env["GITHUB_SHA"] = self.git("rev-parse", "HEAD")
        self.check(False)


if __name__ == "__main__":
    unittest.main()
