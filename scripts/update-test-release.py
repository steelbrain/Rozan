#!/usr/bin/env python3
"""Writes a release for testing "Update automatically" against a local server.

usage: scripts/update-test-release.py <dir> <version> [port]

<dir> must hold rozan-launcher-<version>.apk. The script writes, next to it,
SHA256SUMS in the format of `sha256sum ./*.apk` (scripts/release/collect.sh)
and latest.json in the shape of GitHub's GET /repos/{owner}/{repo}/releases/latest,
with download URLs on http://127.0.0.1:<port> (8765 by default). Serve <dir> with
`python3 -m http.server <port> --bind 127.0.0.1 --directory <dir>` and point a
debug build at it (docs/DEVELOPMENT.md, "Testing automatic updates").
"""

import hashlib
import json
import os
import sys


def asset(directory, asset_id, name, content_type, base):
    return {
        "url": f"https://api.github.com/repos/steelbrain/Rozan/releases/assets/{asset_id}",
        "id": asset_id,
        "name": name,
        "label": None,
        "content_type": content_type,
        "state": "uploaded",
        "size": os.path.getsize(os.path.join(directory, name)),
        "download_count": 0,
        "created_at": "2026-01-01T00:00:00Z",
        "updated_at": "2026-01-01T00:00:00Z",
        "browser_download_url": f"{base}/{name}",
    }


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    directory, version = sys.argv[1], sys.argv[2]
    port = sys.argv[3] if len(sys.argv) == 4 else "8765"
    base = f"http://127.0.0.1:{port}"
    apk = f"rozan-launcher-{version}.apk"

    with open(os.path.join(directory, apk), "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    with open(os.path.join(directory, "SHA256SUMS"), "w") as f:
        f.write(f"{digest}  ./{apk}\n")

    release = {
        "url": "https://api.github.com/repos/steelbrain/Rozan/releases/1",
        "html_url": f"https://github.com/steelbrain/Rozan/releases/tag/v{version}",
        "id": 1,
        "tag_name": f"v{version}",
        "target_commitish": "main",
        "name": f"Rozan {version}",
        "draft": False,
        "prerelease": False,
        "created_at": "2026-01-01T00:00:00Z",
        "published_at": "2026-01-01T00:00:00Z",
        "assets": [
            asset(directory, 1, apk, "application/vnd.android.package-archive", base),
            asset(directory, 2, "SHA256SUMS", "application/octet-stream", base),
        ],
        "body": "Local update test.",
    }
    with open(os.path.join(directory, "latest.json"), "w") as f:
        json.dump(release, f, indent=2)
    print(f"{base}/latest.json: v{version}, {apk}, sha256 {digest}")


if __name__ == "__main__":
    main()
