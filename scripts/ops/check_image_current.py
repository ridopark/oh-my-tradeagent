#!/usr/bin/env python3
"""Refuse to roll a pod onto a stale `:latest` image (#951).

`:latest` is "whichever build pushed last", not "the newest commit": re-running an old build run
after a newer merge re-tags `:latest` to the OLDER commit, and any later restart silently
downgrades that service. This check reads the commit an image was built from (the
`org.opencontainers.image.revision` label stamped by build-images.yml) and asks GitHub whether
`main` has gained any build-relevant commit since it.

    CURRENT        the image's revision is on main and no commit after it touches a path that
                   triggers build-images.yml (docs/infra-only merges never rebuild, so "older
                   than main's tip" alone is not stale)
    STALE          main has a build-relevant commit the image does not contain, or the revision
                   is not on main at all
    UNVERIFIABLE   no revision label (an image built before #951), or the registry/GitHub read
                   failed — fail closed: an unknown image is not a verified one

Read-only: anonymous GHCR pulls of manifests + config blobs (no image layers), and GitHub's
compare API (GH_TOKEN/GITHUB_TOKEN used if set, else anonymous: 60 requests/hour). The
build-path list is read from build-images.yml on the same branch, so it cannot drift.

Usage (deploy.yml runs the same thing before a roll; run it by hand before any manual
`kubectl rollout restart`, which matters most for exec-alpaca-live — it is never CI-rolled):

    python3 scripts/ops/check_image_current.py ghcr.io/ridopark/oh-my-tradeagent-exec:latest
    python3 scripts/ops/check_image_current.py $(kubectl -n copytrade get deploy exec-alpaca-live \\
        -o jsonpath='{.spec.template.spec.containers[*].image}')

Exit status: 0 = every image CURRENT, 1 = any STALE/UNVERIFIABLE, 2 = usage error.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REVISION_LABEL = "org.opencontainers.image.revision"
BUILD_WORKFLOW = ".github/workflows/build-images.yml"
MANIFEST_ACCEPT = ", ".join(
    [
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.docker.distribution.manifest.v2+json",
    ]
)
INDEX_TYPES = (
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
)
# GitHub's compare API returns at most this many files; a full page means the list may be cut.
COMPARE_FILE_CAP = 300


# ---------------------------------------------------------------- pure logic (unit-tested)


def parse_image_ref(ref: str) -> tuple[str, str, str]:
    """`ghcr.io/owner/name:tag` -> (registry, repository, tag). Tag defaults to latest."""
    registry, _, rest = ref.partition("/")
    if not rest or "." not in registry:
        raise ValueError(f"not a registry/repository[:tag] reference: {ref!r}")
    if "@" in rest:
        raise ValueError(f"digest references are already pinned, nothing to check: {ref!r}")
    repo, _, tag = rest.partition(":")
    return registry, repo, tag or "latest"


def build_paths(workflow_text: str) -> list[str]:
    """The `on.push.paths` globs of build-images.yml (the stable two-level block layout)."""
    lines = workflow_text.splitlines()
    out: list[str] = []
    in_push = in_paths = False
    for line in lines:
        if not line.strip() or line.strip().startswith("#"):
            continue
        if line.startswith("  push:"):
            in_push = True
            continue
        if in_push and line.startswith("  ") and not line.startswith("   "):
            break  # next trigger (e.g. pull_request:) — the push block is over
        if in_push and line.strip() == "paths:":
            in_paths = True
            continue
        if in_paths:
            item = line.strip()
            if not item.startswith("- "):
                if out:
                    break
                continue
            out.append(item[2:].strip().strip("'\""))
    if not out:
        raise ValueError(f"no on.push.paths found in {BUILD_WORKFLOW}")
    return out


def matches(path: str, patterns: list[str]) -> bool:
    for p in patterns:
        if p.endswith("/**"):
            if path.startswith(p[:-2]):
                return True
        elif path == p:
            return True
    return False


def verdict(compare: dict, patterns: list[str]) -> tuple[str, str]:
    """(CURRENT|STALE|UNVERIFIABLE, detail) from a compare(revision...branch) response."""
    status = compare.get("status")
    if status == "identical":
        return "CURRENT", "built from the branch tip"
    if status != "ahead":
        return "STALE", f"revision is not on the branch (compare status '{status}')"
    files = compare.get("files") or []
    relevant = sorted(
        {
            name
            for f in files
            for name in (f.get("filename"), f.get("previous_filename"))
            if name and matches(name, patterns)
        }
    )
    behind = compare.get("ahead_by", "?")
    if relevant:
        shown = ", ".join(relevant[:5]) + (" …" if len(relevant) > 5 else "")
        return "STALE", f"branch is {behind} commit(s) ahead with build-relevant changes: {shown}"
    if len(files) >= COMPARE_FILE_CAP:
        return "UNVERIFIABLE", f"{behind} commits behind; the changed-file list is truncated"
    return "CURRENT", f"branch is {behind} commit(s) ahead, none build-relevant"


# ---------------------------------------------------------------- I/O


def http_json(url: str, headers: dict[str, str] | None = None) -> tuple[dict, dict]:
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp), dict(resp.headers)


def http_text(url: str, headers: dict[str, str] | None = None) -> str:
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read().decode("utf-8")


def image_revision(ref: str) -> tuple[str | None, str]:
    """(revision label or None, resolved index/manifest digest)."""
    registry, repo, tag = parse_image_ref(ref)
    scope = urllib.parse.quote(f"repository:{repo}:pull")
    token, _ = http_json(f"https://{registry}/token?scope={scope}")
    auth = {"Authorization": f"Bearer {token['token']}"}
    base = f"https://{registry}/v2/{repo}"
    manifest, headers = http_json(f"{base}/manifests/{tag}", {**auth, "Accept": MANIFEST_ACCEPT})
    digest = headers.get("Docker-Content-Digest") or headers.get("docker-content-digest") or "?"
    if manifest.get("mediaType") in INDEX_TYPES or "manifests" in manifest:
        image = next(
            (
                m
                for m in manifest["manifests"]
                if (m.get("platform") or {}).get("os") == "linux"
                and (m.get("platform") or {}).get("architecture") == "amd64"
            ),
            None,
        )
        if image is None:
            raise ValueError("index has no linux/amd64 image manifest")
        manifest, _ = http_json(
            f"{base}/manifests/{image['digest']}", {**auth, "Accept": MANIFEST_ACCEPT}
        )
    config, _ = http_json(f"{base}/blobs/{manifest['config']['digest']}", auth)
    labels = (config.get("config") or {}).get("Labels") or {}
    return labels.get(REVISION_LABEL), digest


def github_headers() -> dict[str, str]:
    h = {"Accept": "application/vnd.github+json", "User-Agent": "check-image-current"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("images", nargs="+", help="image references, e.g. ghcr.io/o/name:latest")
    p.add_argument("--repo", default="ridopark/oh-my-tradeagent", help="GitHub owner/repo")
    p.add_argument("--branch", default="main")
    a = p.parse_args(argv)
    for ref in a.images:
        try:
            parse_image_ref(ref)
        except ValueError as e:
            p.error(str(e))

    gh = github_headers()
    api = f"https://api.github.com/repos/{a.repo}"
    try:
        patterns = build_paths(
            http_text(
                f"{api}/contents/{BUILD_WORKFLOW}?ref={urllib.parse.quote(a.branch)}",
                {**gh, "Accept": "application/vnd.github.raw"},
            )
        )
    except (urllib.error.URLError, ValueError) as e:
        print(f"UNVERIFIABLE  cannot read {BUILD_WORKFLOW}@{a.branch}: {e}")
        return 1

    compares: dict[str, tuple[str, str]] = {}
    bad = 0
    for ref in dict.fromkeys(a.images):  # de-duplicate, keep order
        try:
            rev, digest = image_revision(ref)
        except (urllib.error.URLError, ValueError, KeyError) as e:
            print(f"UNVERIFIABLE  {ref}: registry read failed: {e}")
            bad += 1
            continue
        if not rev:
            print(
                f"UNVERIFIABLE  {ref} ({digest[:19]}): no {REVISION_LABEL} label — built "
                "before #951; rebuild main to stamp it"
            )
            bad += 1
            continue
        if rev not in compares:
            try:
                cmp, _ = http_json(f"{api}/compare/{rev}...{urllib.parse.quote(a.branch)}", gh)
                compares[rev] = verdict(cmp, patterns)
            except urllib.error.HTTPError as e:
                compares[rev] = (
                    ("STALE", "revision unknown to GitHub")
                    if e.code == 404
                    else ("UNVERIFIABLE", f"compare failed: HTTP {e.code}")
                )
            except urllib.error.URLError as e:
                compares[rev] = ("UNVERIFIABLE", f"compare failed: {e}")
        state, detail = compares[rev]
        print(f"{state:<13} {ref} built from {rev[:12]} ({digest[:19]}): {detail}")
        bad += state != "CURRENT"
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
