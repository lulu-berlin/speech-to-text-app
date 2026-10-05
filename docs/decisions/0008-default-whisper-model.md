# 0008 — Default whisper model

Status: accepted · Date: 2026-10-05

## Context
whisper-cli needs a GGML model file. The oracle downloads models on first use and defaults to `DEFAULT_MODEL = "large-v3-turbo-q5_0"` (see [transcribe.py](../oracle/transcribe.py)). The app is packaged with Nix (ADR 0001), so the model must be fetched reproducibly with a pinned hash rather than downloaded at runtime.

## Decision
The default whisper model is `large-v3-turbo-q5_0`, the oracle's default:
- URL: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin`
- sha256: `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2` (Hugging Face `x-linked-etag`)
- Size: 574041195 bytes

## Consequences
- The model is fetched by Nix (`fetchurl` with the hash above) for both the dev environment and the package; the app never downloads models at runtime.
- The package closure grows by about 574 MB.
- Quality/speed matches the oracle's default, keeping oracle comparisons meaningful.
- Changing the default model requires a new ADR and new hash.
