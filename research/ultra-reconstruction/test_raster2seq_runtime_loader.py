#!/usr/bin/env python3
"""Fail-closed tests for the pinned Raster2Seq runtime loader compatibility exceptions."""

from __future__ import annotations

import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import run_raster2seq_teacher as runner  # noqa: E402


class Raster2SeqRuntimeLoaderContractTest(unittest.TestCase):
    def test_only_exact_runtime_cache_buffers_are_accepted(self) -> None:
        accepted = [
            "transformer.decoder.layers.0.kv_cache.k_cache",
            "transformer.decoder.layers.5.kv_cache.v_cache",
            "transformer.decoder.layers.2.cross_attn.cache.v_cache",
        ]
        for key in accepted:
            with self.subTest(key=key):
                self.assertTrue(runner.is_runtime_cache_state_key(key))

    def test_learned_or_near_miss_keys_remain_rejected(self) -> None:
        rejected = [
            "transformer.decoder.layers.0.kv_cache.weight",
            "transformer.decoder.layers.0.cross_attn.cache.weight",
            "transformer.decoder.layers.0.cross_attn.cache.k_cache",
            "transformer.decoder.layers.0.self_attn.in_proj_weight",
            "backbone.0.body.layer1.0.conv1.weight",
            "room_class_embed.weight",
            "kv_cache",
            "v_cache",
        ]
        for key in rejected:
            with self.subTest(key=key):
                self.assertFalse(runner.is_runtime_cache_state_key(key))


if __name__ == "__main__":
    unittest.main()
