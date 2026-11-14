"""Static Dockerfile tests — item 3.

Verifies the Dockerfile is correct for repo-root build context:
  docker build -f services/mcp-server/Dockerfile .

Checks:
- Runtime stage has ENV PATH=/app/.venv/bin:$PATH so the system python
  is not used in the ENTRYPOINT.
- COPY instructions reference the services/mcp-server/ prefix so they
  work from the repo root (not the services/mcp-server/ directory).
- ENTRYPOINT uses the venv python via the PATH env.
"""
from __future__ import annotations

from pathlib import Path

_DOCKERFILE = Path(__file__).resolve().parents[4] / "services/mcp-server/Dockerfile"


def _lines() -> list[str]:
    return _DOCKERFILE.read_text().splitlines()


def test_dockerfile_runtimeStage_hasVenvPathEnv():
    """Runtime stage must set ENV PATH=/app/.venv/bin:$PATH.

    Without this the ENTRYPOINT 'python -m …' uses the system python
    instead of the venv python, which has no installed packages.

    RED: runtime stage has no PATH env.
    GREEN: ENV PATH=/app/.venv/bin:$PATH present in runtime stage.
    """
    lines = _lines()
    runtime_started = False
    for line in lines:
        s = line.strip()
        if s.startswith("FROM") and "AS runtime" in s:
            runtime_started = True
        if runtime_started and s.startswith("ENV") and "/app/.venv/bin" in s:
            return  # found it
    raise AssertionError(
        "Runtime stage must contain ENV PATH=/app/.venv/bin:$PATH; "
        f"searched {_DOCKERFILE}"
    )


def test_dockerfile_copyPaths_haveServicesMcpServerPrefix():
    """COPY paths must use services/mcp-server/ prefix for repo-root build context.

    When building with 'docker build -f services/mcp-server/Dockerfile .' from
    the repo root, paths like 'pyproject.toml' resolve to the repo root, not to
    services/mcp-server/.  The correct prefix is 'services/mcp-server/'.

    RED: COPY pyproject.toml uv.lock ./ (bare paths, works only from subdirectory).
    GREEN: COPY services/mcp-server/pyproject.toml services/mcp-server/uv.lock ./
    """
    lines = _lines()
    for line in lines:
        s = line.strip()
        if not s.startswith("COPY") or "--from=" in s:
            continue
        # Skip lines that are just COPY --from= (inter-stage copies are fine)
        parts = s.split()
        # e.g. COPY pyproject.toml uv.lock ./
        # source paths are everything before the last token (the destination)
        sources = parts[1:-1]
        for src in sources:
            if src.startswith("--"):
                continue
            # Sources must start with services/mcp-server/ or be from an COPY --from
            if not src.startswith("services/mcp-server/"):
                raise AssertionError(
                    f"COPY source {src!r} must start with 'services/mcp-server/' "
                    f"for repo-root build context. Line: {s!r}"
                )


def test_dockerfile_entrypoint_usesPythonM():
    """ENTRYPOINT must invoke the module via 'python -m ohmytradeagent_mcp.gateway'."""
    content = _DOCKERFILE.read_text()
    assert "ohmytradeagent_mcp.gateway" in content, (
        "ENTRYPOINT must reference ohmytradeagent_mcp.gateway"
    )
    assert 'ENTRYPOINT ["python"' in content or "ENTRYPOINT [" in content, (
        "ENTRYPOINT must be in exec form"
    )
