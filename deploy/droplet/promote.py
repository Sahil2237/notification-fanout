#!/usr/bin/env python3
"""Root-owned gateway: promote only CI-signed bundles from a restricted upload user."""
import hashlib, os, pathlib, re, shutil, stat, subprocess, sys, tempfile
FILES = ("image.tar.gz", "image-id", "release-sha", "compose.yaml", "init-db.sh", "grants.sql", "Caddyfile", "release.sh", "backup.sh", "verify-image.py", "ha-release.sh")

def stage_bundle(root, incoming, sha, public_key):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid revision")
    root, incoming = pathlib.Path(root), pathlib.Path(incoming)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = pathlib.Path(tempfile.mkdtemp(prefix=".incoming-", dir=root))
    descriptor = os.open(incoming, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for name in FILES + ("SHA256SUMS", "SHA256SUMS.sig"):
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=descriptor)
            with os.fdopen(fd, "rb") as source:
                info = os.fstat(source.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > 1024 ** 3:
                    raise ValueError("Invalid bundle file: " + name)
                with (temporary / name).open("xb") as target:
                    os.chmod(temporary / name, 0o600)
                    shutil.copyfileobj(source, target)
        subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public_key),
                        "-rawin", "-in", str(temporary / "SHA256SUMS"),
                        "-sigfile", str(temporary / "SHA256SUMS.sig")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        manifest = {}
        for line in (temporary / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ", 1)
            if name not in FILES or name in manifest or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Invalid manifest")
            manifest[name] = digest
        if set(manifest) != set(FILES):
            raise ValueError("Incomplete manifest")
        for name, digest in manifest.items():
            with (temporary / name).open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual != digest:
                raise ValueError("Checksum mismatch: " + name)
        if (temporary / "release-sha").read_text().strip() != sha:
            raise ValueError("Revision mismatch")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", (temporary / "image-id").read_text().strip()):
            raise ValueError("Invalid image identity")
        destination = root / sha
        if destination.exists():
            if (destination / "SHA256SUMS").read_bytes() != (temporary / "SHA256SUMS").read_bytes():
                raise ValueError("Revision already exists with different contents")
            # The release script removes the bulky archive after successful deployment.
            shutil.copyfile(temporary / "image.tar.gz", destination / "image.tar.gz")
            return destination
        os.rename(temporary, destination)
        return destination
    finally:
        os.close(descriptor)
        if temporary.exists():
            shutil.rmtree(temporary)

def main():
    if os.geteuid() != 0 or len(sys.argv) != 2 or not re.fullmatch(r"[0-9a-f]{40}", sys.argv[1]):
        raise SystemExit("Run the installed gateway through sudo with one tested revision")
    root = pathlib.Path("/opt/notification-fanout")
    root.mkdir(mode=0o700, exist_ok=True)
    # Serialize staging as well as promotion; release.sh has its own deployment lock.
    import fcntl
    with (root / "promotion.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        sha = sys.argv[1]
        release = stage_bundle(root / "releases", pathlib.Path("/home/notification-deploy/incoming") / sha,
                               sha, "/etc/notification-fanout-release.pub")
        subprocess.run(["bash", str(release / "release.sh"), sha], check=True)

if __name__ == "__main__":
    main()
