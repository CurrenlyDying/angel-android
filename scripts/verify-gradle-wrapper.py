#!/usr/bin/env python3
"""Verify the wrapper JAR and distribution pin against Gradle's HTTPS checksums."""

import hashlib
from pathlib import Path
import re
import urllib.request


root = Path(__file__).resolve().parent.parent
properties = dict(
    line.split("=", 1)
    for line in (root / "gradle/wrapper/gradle-wrapper.properties").read_text().splitlines()
    if line and not line.startswith("#") and "=" in line
)
url = properties.get("distributionUrl", "").replace("\\:", ":")
match = re.fullmatch(
    r"https://services\.gradle\.org/distributions/gradle-(\d+\.\d+\.\d+)-(bin|all)\.zip",
    url,
)
if not match:
    raise SystemExit("Expected an official HTTPS Gradle release distribution URL")
version = match.group(1)


def official_checksum(artifact):
    endpoint = f"https://services.gradle.org/distributions/{artifact}.sha256"
    with urllib.request.urlopen(endpoint, timeout=30) as response:
        digest = response.read(1024).decode("ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise SystemExit(f"Invalid checksum response for {artifact}")
    return digest


jar = root / "gradle/wrapper/gradle-wrapper.jar"
actual = hashlib.sha256(jar.read_bytes()).hexdigest()
expected = official_checksum(f"gradle-{version}-wrapper.jar")
if actual != expected:
    raise SystemExit(f"Wrapper JAR does not match official Gradle {version}: {actual}")
expected_distribution = official_checksum(f"gradle-{version}-{match.group(2)}.zip")
if properties.get("distributionSha256Sum") != expected_distribution:
    raise SystemExit(f"Distribution checksum pin does not match official Gradle {version}")
print(f"Gradle {version}: official wrapper JAR and distribution checksum verified")
