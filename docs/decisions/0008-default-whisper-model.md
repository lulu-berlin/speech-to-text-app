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

## Update (2026-10-15)
- The file is now fetched from this repository's own GitHub Release
  (`desktop-models-v1/ggml-large-v3-turbo-q5_0.bin`, uploaded by the human — agents never publish
  to GitHub, ADR 0050), a byte-identical mirror of the upstream file: same sha256 and same
  574041195 bytes, so the Nix store path is unchanged and existing caches keep hitting. Upstream,
  for provenance: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin`
  (MIT, the GGML conversion of OpenAI's whisper weights). Reason: the desktop app downloads this
  file on first run and may only reach GitHub hosts (ADR 0055's provisioning allowlist), so one
  host serves every artifact.
- Consequence 1 above holds for the Nix paths; the **desktop app** does download this model at
  runtime, on first start, into its per-user cache (item 50's decision, ADR 0053), sha256-verified
  against this pin.
