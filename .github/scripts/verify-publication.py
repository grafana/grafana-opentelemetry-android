"""Check the local Maven publication, including its detached signatures."""

import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def verify():
    version = Path("version.txt").read_text().strip()
    if json.loads(Path(".release-please-manifest.json").read_text())["."] != version:
        raise ValueError("Release Please manifest differs from version.txt")
    directory = Path("build/test-repository/com/grafana/opentelemetry/android") / version
    prefix = f"android-{version}"
    for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar"):
        artifact = directory / f"{prefix}{suffix}"
        if not artifact.is_file():
            raise ValueError(f"Missing artifact: {artifact}")
        subprocess.run(["gpg", "--batch", "--verify", f"{artifact}.asc", str(artifact)], check=True)
        for algorithm in ("md5", "sha1"):
            expected = Path(f"{artifact}.{algorithm}").read_text().strip()
            actual = hashlib.new(algorithm, artifact.read_bytes()).hexdigest()
            if actual != expected:
                raise ValueError(f"Invalid {algorithm} checksum: {artifact}")

    pom = ET.parse(directory / f"{prefix}.pom")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for field, expected in {"groupId": "com.grafana.opentelemetry", "artifactId": "android",
                            "version": version, "packaging": "aar"}.items():
        if pom.findtext(f"m:{field}", namespaces=ns) != expected:
            raise ValueError(f"Incorrect POM {field}")
    for field in ("name", "description", "url", "licenses/license/name", "licenses/license/url",
                  "developers/developer/id", "developers/developer/name", "scm/url", "scm/connection"):
        if not pom.findtext("/".join(f"m:{part}" for part in field.split("/")), namespaces=ns):
            raise ValueError(f"Missing POM {field}")
    metadata = json.loads((directory / f"{prefix}.module").read_text())
    if metadata["component"]["version"] != version:
        raise ValueError("Gradle metadata version differs from POM")
    for suffix, member in ((".aar", "classes.jar"), ("-sources.jar", "GrafanaOtel.kt"),
                           ("-javadoc.jar", "README.md")):
        with zipfile.ZipFile(directory / f"{prefix}{suffix}") as archive:
            if not any(name.split("/")[-1] == member for name in archive.namelist()):
                raise ValueError(f"Missing {member} in {suffix}")
    print(f"Verified signed local publication com.grafana.opentelemetry:android:{version}")


if __name__ == "__main__":
    verify()
