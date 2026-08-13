"""Entrypoint wiring for the /options-chat mirror.

Small surface, but two of these guard mistakes that fail only in production: a mis-set channel URL
would 400 every batch forever at the BFF, and importing this module must not drag in temporalio.
"""

from __future__ import annotations

import subprocess
import sys

import pytest

from ohmytradeagent_sidecar.chat_main import _channel_id_from_url


def test_channel_id_is_taken_from_the_url():
    assert (
        _channel_id_from_url("https://discord.com/channels/769790224921395200/786109983065505792")
        == "786109983065505792"
    )
    assert (
        _channel_id_from_url("https://discord.com/channels/769790224921395200/786109983065505792/")
        == "786109983065505792"
    )


@pytest.mark.parametrize(
    "bad",
    [
        "https://discord.com/channels/769790224921395200",  # no channel segment
        "https://discord.com/channels/guild/channel",  # non-numeric
        "not-a-url",
        "",
    ],
)
def test_a_malformed_channel_url_fails_fast_at_boot(bad):
    # The BFF rejects a mismatched channel_id for the WHOLE batch, so a bad URL would otherwise
    # 400 forever with no local signal. Crash at startup instead.
    with pytest.raises(SystemExit):
        _channel_id_from_url(bad)


def test_the_chat_entrypoint_does_not_import_temporalio():
    """This pod never dials Temporal. Importing `main` (or `.emitter`) would pull tens of MB of
    Temporal SDK into a memory-budgeted Chromium pod for nothing."""
    code = (
        "import sys; import ohmytradeagent_sidecar.chat_main; "
        "print('temporalio' in sys.modules)"
    )
    out = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, check=True
    )
    assert out.stdout.strip() == "False"
