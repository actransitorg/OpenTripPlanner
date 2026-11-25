"""Deprecated compatibility launcher for the Traffic511Capture command in the OTP JAR."""

import os
import subprocess
import sys
from pathlib import Path


def main():
    capture = "org.opentripplanner.ext.traffic511.feed.Traffic511Capture"
    print(
        f"Deprecated: use java -cp <otp-shaded.jar> {capture} with the same arguments.",
        file=sys.stderr,
    )
    default_jar = (
        Path(__file__).resolve().parents[1]
        / "otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar"
    )
    jar = Path(os.environ.get("OTP_JAR", default_jar))
    if not jar.is_file():
        sys.exit("Build the OTP shaded JAR or set OTP_JAR to its location.")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    try:
        return subprocess.run(
            [java, "-cp", str(jar), capture, *sys.argv[1:]], check=False
        ).returncode
    except OSError:
        sys.exit("Set JAVA_HOME or add Java 25 to PATH.")
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
