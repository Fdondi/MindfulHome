# LM Playground emulator tests

**Last run (2026-08-28, Pixel Fold API 34):** Playground connected (`1.9.1-francesco-debug`). Fallback 2/2 and live probe 3/3 passed. `simpleChat` returned `pong`. `nudgeToolChat` was a tool-only reply (empty `content`, `finishReason=tool_calls`) — that is valid, not the generic think failure.

## What exists

| Piece | Purpose |
|-------|---------|
| `LocalLmFallbackInstrumentedTest` | Fake local client returns “can't think”; asserts script fallback + “N more minutes”. **Does not need** LM Playground or a model. Passed earlier on Pixel 4 (API 29). |
| `LmPlaygroundProbeInstrumentedTest` | Talks to a real LM Playground install; dumps error type/message so we can see why the on-device path fails. Skips chat tests if Playground is missing. **Not completed** after Playground was installed. |
| `scripts/test/run_lm_playground_instrumented.sh` | Runs both classes; writes `results/test/lm-playground-probe.log`. |
| `scripts/test/install_lm_playground_emulator.sh` | Builds `com.druk.lmplayground.debug` from `LMPlayground-server/` (gitignored clone) and installs it. |

## Machine / AVD

- LM Playground **minSdk is 30**. Pixel 4 (API 29) cannot run it. Use **Pixel_10_Pro_Fold** (API 37) or another API 30+ AVD.
- Compiling llama.cpp **and** running the Fold emulator at the same time pegged every core on a 26 GB / 8-thread-class laptop. RAM was not the obvious bottleneck. Prefer a stronger CPU, and do not compile native code while the AVD is up.
- If the debug APK is already built, skip Gradle and sideload:

  `LMPlayground-server/app/build/intermediates/apk/debug/app-x86_64-debug.apk`

- Guest RAM of 2–4 GB is enough; do not try to throw host RAM at qemu. Cap compile with `--max-workers=2` / `CMAKE_BUILD_PARALLEL_LEVEL=2` (already in the install script).

## After install, before the live probe

1. Open **LM Playground** (debug) on the emulator.
2. Settings → Advanced → **Allow other apps**.
3. Download / load a GGUF model. Chat tests will hang or fail without one.

Then:

```
scripts/test/run_lm_playground_instrumented.sh
```

Logcat tag `LmPlaygroundProbe` is the dump of connect/listModels/simpleChat/nudgeToolChat.
