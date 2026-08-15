#!/usr/bin/env python3
"""Serve a minimal Modrinth-compatible version API and local mrpack downloads."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse


def file_hash(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def version_payload(
    version_id: str,
    number: str,
    version_type: str,
    published: str,
    package: Path,
    base_url: str,
    minecraft_version: str,
    loader: str,
) -> dict[str, object]:
    file_name = package.name
    return {
        "id": version_id,
        "version_number": number,
        "version_type": version_type,
        "date_published": published,
        "environment": "client_only",
        "game_versions": [minecraft_version],
        "loaders": [loader],
        "files": [
            {
                "url": f"{base_url}/files/{quote(file_name)}",
                "filename": file_name,
                "hashes": {
                    "sha1": file_hash(package, "sha1"),
                    "sha512": file_hash(package, "sha512"),
                },
                "size": package.stat().st_size,
                "primary": True,
            }
        ],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8763)
    parser.add_argument("--public-base-url", default="")
    parser.add_argument("--project", default="363fan")
    parser.add_argument("--minecraft-version", default="26.1.2")
    parser.add_argument("--loader", default="fabric")
    parser.add_argument("--old-version", default="0.20.2")
    parser.add_argument("--old-type", default="release", choices=("release", "beta", "alpha"))
    parser.add_argument("--old-package", type=Path, required=True)
    parser.add_argument("--target-version", default="0.21.1")
    parser.add_argument("--target-type", default="beta", choices=("release", "beta", "alpha"))
    parser.add_argument("--target-package", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    packages = {
        args.old_package.resolve().name: args.old_package.resolve(),
        args.target_package.resolve().name: args.target_package.resolve(),
    }
    for package in packages.values():
        if not package.is_file():
            raise SystemExit(f"mrpack does not exist: {package}")

    base_url = args.public_base_url.rstrip("/") or f"http://127.0.0.1:{args.port}"
    versions = [
        version_payload(
            "mock-target",
            args.target_version,
            args.target_type,
            datetime(2026, 8, 12, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z"),
            args.target_package.resolve(),
            base_url,
            args.minecraft_version,
            args.loader,
        ),
        version_payload(
            "mock-current",
            args.old_version,
            args.old_type,
            datetime(2026, 8, 6, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z"),
            args.old_package.resolve(),
            base_url,
            args.minecraft_version,
            args.loader,
        ),
    ]
    project_path = f"/v2/project/{quote(args.project)}/version"

    class Handler(BaseHTTPRequestHandler):
        server_version = "363UpdaterMock/0.1"

        def do_GET(self) -> None:
            request_path = unquote(urlparse(self.path).path)
            if request_path == "/health":
                self.send_json({"ok": True, "project": args.project})
                return
            if request_path == unquote(project_path):
                self.send_json(versions)
                return
            if request_path == "/":
                self.send_json(
                    {
                        "service": "363Updater mock Modrinth server",
                        "apiRoot": f"{base_url}/v2",
                        "versions": f"{base_url}{project_path}",
                    }
                )
                return
            if request_path.startswith("/files/"):
                file_name = request_path.removeprefix("/files/")
                package = packages.get(file_name)
                if package is not None:
                    self.send_file(package)
                    return
            self.send_error(404, "Not found")

        def send_json(self, value: object) -> None:
            body = (json.dumps(value, ensure_ascii=True, separators=(",", ":")) + "\n").encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def send_file(self, path: Path) -> None:
            self.send_response(200)
            self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
            self.send_header("Content-Length", str(path.stat().st_size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    self.wfile.write(chunk)

        def log_message(self, message: str, *values: object) -> None:
            print(f"[{self.log_date_time_string()}] {message % values}", flush=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Mock Modrinth API: {base_url}/v2", flush=True)
    print(f"Project versions: {base_url}{project_path}", flush=True)
    print(f"Health check: {base_url}/health", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
