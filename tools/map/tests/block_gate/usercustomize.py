"""Test-only gate that holds the block stage open, loaded by Python at startup.

``test_build_example`` puts this folder on ``PYTHONPATH`` and names the blocks
that may finish in ``SEERAH_TEST_FINISHING_BLOCKS`` (comma-separated IDs).
Every other block waits for a signal before rendering, in the worker that
picked it up, so the build can never leave the block stage and the test can
kill it there whatever the machine's speed. Without the variable, nothing is
changed.
"""

import functools
import os
import signal

FINISHING_BLOCKS_ENV = "SEERAH_TEST_FINISHING_BLOCKS"

if FINISHING_BLOCKS_ENV in os.environ:
    from seerah_map import blockstage

    _finishing = frozenset(os.environ[FINISHING_BLOCKS_ENV].split(","))
    _render_block = blockstage.render_block

    @functools.wraps(_render_block)
    def _gated_render_block(block, *args, **kwargs):
        if block.id not in _finishing:
            signal.pause()
        return _render_block(block, *args, **kwargs)

    blockstage.render_block = _gated_render_block
