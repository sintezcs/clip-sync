"""Compile source-level audit probes; no network or real clipboard access."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
sources = [
    root / "mac/ClipSync/Clipboard/ClipPayload.swift",
    root / "mac/ClipSync/Security/HMACValidator.swift",
    root / "mac/ClipSync/Server/RateLimiter.swift",
    Path(__file__).with_name("AuditProbeRunner.swift"),
]
with tempfile.TemporaryDirectory(prefix="clipsync-audit-") as directory:
    temp = Path(directory)
    source = temp / "AuditProbe.swift"
    binary = temp / "audit-probe"
    source.write_text("\n".join(p.read_text() for p in sources))
    subprocess.run([
        "swiftc", "-module-cache-path", str(temp / "module-cache"),
        "-parse-as-library", str(source), "-o", str(binary),
    ], check=True)
    subprocess.run([str(binary)], check=True)
    result = subprocess.run(
        [str(binary), "--extreme-timestamp"], capture_output=True, text=True
    )
    print(f"EXTREME TIMESTAMP: subprocess return code {result.returncode}")
    print("A negative return code denotes a signal; reviewed baseline returned -5.")
