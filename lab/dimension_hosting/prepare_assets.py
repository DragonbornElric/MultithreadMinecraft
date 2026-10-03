#!/usr/bin/env python3
"""Pin real Fabric/server mods; preparation never uses an EndInv stand-in."""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "MTMC-dimension-hosting-lab/0.1"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    assets = []
    launcher = "https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.5/1.1.2/server/jar"
    data = fetch(launcher)
    path = args.out / "fabric-server-launch.jar"
    path.write_bytes(data)
    assets.append({"id": "launcher", "path": str(path.resolve()), "version": "26.2/0.19.5/1.1.2", "sha256": hashlib.sha256(data).hexdigest(), "url": launcher})
    for project, version in [("fabric-api", "0.161.0+26.2"), ("lithium", "mc26.2-0.25.3-fabric"), ("servercore", "1.5.19+26.2")]:
        url = "https://api.modrinth.com/v2/project/" + project + "/version"
        versions = json.loads(fetch(url))
        matches = [v for v in versions if "26.2" in v["game_versions"] and "fabric" in v["loaders"] and (v["version_number"] == version or v["version_number"].startswith(version + "+"))]
        if len(matches) != 1:
            raise RuntimeError(f"Expected one exact {project} {version} Fabric26.2 version; found {len(matches)}")
        candidate = matches[0]
        primary = [f for f in candidate["files"] if f["primary"]]
        if len(primary) != 1:
            raise RuntimeError("Ambiguous primary mod JAR")
        file = primary[0]
        data = fetch(file["url"])
        if hashlib.sha512(data).hexdigest() != file["hashes"]["sha512"]:
            raise RuntimeError("Downloaded mod checksum mismatch")
        path = args.out / file["filename"]
        path.write_bytes(data)
        assets.append({"id": project, "path": str(path.resolve()), "version": candidate["version_number"], "version_id": candidate["id"], "sha256": hashlib.sha256(data).hexdigest(), "sha512": file["hashes"]["sha512"], "url": file["url"]})
    (args.out / "assets.json").write_text(json.dumps(assets, indent=2) + "\n")
    print(json.dumps({"assets": len(assets), "manifest": str(args.out / "assets.json")}))


if __name__ == "__main__":
    main()
