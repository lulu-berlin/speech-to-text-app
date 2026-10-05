#!/usr/bin/env python3
"""transcribe — local GPU-accelerated speech-to-text with speaker diarization.

Transcribes an audio file (conversation, dictation, meeting, ...) using
whisper.cpp, an MIT-licensed C/C++ port of OpenAI's Whisper model. The devenv
build uses the Vulkan backend, so GPU acceleration works on both AMD (Mesa
RADV) and NVIDIA (proprietary driver) hardware with the same binary; when no
GPU is available it falls back to CPU automatically.

Speaker diarization labels each transcribed segment with the likely speaker.
It uses speechbrain's ECAPA-TDNN speaker embedding model
(https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb, Apache-2.0,
trained on VoxCeleb), downloaded on first use, and clusters the per-segment
embeddings with average-linkage agglomerative clustering, choosing the
speaker count from the top of the merge tree. The number of speakers
is detected automatically (bounded by --max-speakers) or fixed via
--speakers N; consecutive segments of the same speaker are emitted as a
single turn (one .txt line, one .srt/.vtt cue). Embedding extraction runs
on CPU — it is cheap compared with transcription, which stays on the GPU.

Models are GGML quantized Whisper weights downloaded from Hugging Face
(https://huggingface.co/ggerganov/whisper.cpp) on first use and cached under
~/.cache/whisper-cpp/models (or $WHISPER_MODELS_DIR).

Examples:
    transcribe meeting.mp3
    transcribe --model small.en --speakers 2 --format txt,srt,vtt conversation.m4a
    transcribe --speakers off --device cpu --threads 8 notes.ogg
    transcribe --device 1 podcast.mp3   # pick Vulkan GPU 1 (e.g. an NVIDIA card)
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_MODEL = "large-v3-turbo-q5_0"  # ~550 MB, best quality/speed tradeoff
VALID_FORMATS = {
    "txt": "-otxt",
    "srt": "-osrt",
    "vtt": "-ovtt",
    "json": "-oj",
}
EMBEDDING_SOURCE = "speechbrain/spkrec-ecapa-voxceleb"
MAX_SPEAKERS = 8
EMBED_CHUNK = 16
SHORT_SEGMENT_SECONDS = 0.5  # whisper fragments shorter than this are not embedded (near-silent)
MIN_EMBED_SECONDS = 2.0  # windows shorter than this are zero-padded (short windows lose speaker signal)
MAX_EMBED_SECONDS = 20   # windows longer than this use a centered 20 s slice
EMBED_BATCH_SECONDS = 60  # cap total audio per batch so STFT/conv transients stay small

_embedder = None  # lazy speechbrain EncoderClassifier, reused across files


# --------------------------------------------------------------------------- models


def cache_home() -> Path:
    """Base cache directory: ~/.cache/whisper-cpp (or $XDG_CACHE_HOME/whisper-cpp)."""
    base = Path(os.environ.get("XDG_CACHE_HOME") or (Path.home() / ".cache"))
    return base / "whisper-cpp"


def models_dir() -> Path:
    env = os.environ.get("WHISPER_MODELS_DIR")
    if env:
        return Path(env)  # custom dir holds the .bin files directly
    return cache_home() / "models"


def embedding_dir() -> Path:
    return cache_home() / "speaker-embedding"


def model_alias(name: str) -> str:
    """whisper.cpp alias for a user-supplied bare model name.

    The download script takes the short alias (large-v3-turbo-q5_0) while
    files on disk are ggml-<alias>.bin; accept both spellings.
    """
    if name.startswith("ggml-"):
        name = name[len("ggml-"):]
    if name.endswith(".bin"):
        name = name[: -len(".bin")]
    return name


def model_file(name: str) -> Path:
    """On-disk path for a bare model name: models_dir/ggml-<alias>.bin."""
    return models_dir() / f"ggml-{model_alias(name)}.bin"


def resolve_model(name: str) -> Path | None:
    """Model path for `name`; explicit paths pass through, bare names live in models_dir."""
    if os.sep in name or (os.altsep and os.altsep in name):
        p = Path(name)
        return p if p.is_file() else None
    p = model_file(name)
    return p if p.is_file() else None


def download_model(name: str) -> Path:
    """Fetch a GGML model via whisper-cpp's download script (patched to write to CWD)."""
    script = shutil.which("whisper-cpp-download-ggml-model")
    if script is None:
        sys.exit(
            "error: whisper-cpp is not installed. Run `devenv` / `direnv allow` in the "
            "project to install it, then retry."
        )
    dst = models_dir()
    dst.mkdir(parents=True, exist_ok=True)
    alias = model_alias(name)
    print(f"Downloading model {alias} into {dst} ...", file=sys.stderr)
    result = subprocess.run([script, alias], cwd=dst)
    target = dst / f"ggml-{alias}.bin"
    if result.returncode != 0 or not target.is_file():
        sys.exit(f"error: failed to download model {alias}")
    return target


def whisper_cli() -> str:
    cli = shutil.which("whisper-cli")
    if cli is None:
        sys.exit(
            "error: whisper-cli is not installed. Run `devenv` / `direnv allow` in the "
            "project to install it, then retry."
        )
    return cli


# --------------------------------------------------------------------------- whisper-cli invocation


def device_args(device: str | int) -> list[str]:
    """whisper.cpp 1.9.x flags: omitted = auto (first GPU), --no-gpu = CPU,
    --device N = explicit GPU/Vulkan device index (list devices with whisper-cli)."""
    if device == "cpu":
        return ["--no-gpu"]
    if device == "gpu":
        return ["--device", "0"]
    if isinstance(device, int):
        return ["--device", str(device)]
    return []  # auto


# --------------------------------------------------------------------------- diarization


def decode_pcm(audio: str) -> "np.ndarray":
    """Decode the whole file to mono 16 kHz float32 PCM via ffmpeg."""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        sys.exit("error: ffmpeg not found; it is part of the devenv environment.")
    proc = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-i", audio, "-f", "f32le", "-ac", "1", "-ar", "16000", "-"],
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.exit(f"error: ffmpeg failed to decode {audio}: {proc.stderr.decode(errors='replace').strip()}")
    import numpy as np

    samples = np.frombuffer(proc.stdout, dtype=np.float32)
    if samples.size == 0:
        sys.exit(f"error: no audio decoded from {audio}")
    return samples


def load_embedder():
    """Lazy speechbrain ECAPA-TDNN embedder; downloads weights on first use."""
    global _embedder
    if _embedder is not None:
        return _embedder
    try:
        from speechbrain.inference.speaker import EncoderClassifier
    except ImportError:
        sys.exit(
            "error: speaker diarization needs speechbrain/torch (numpy): re-enter the "
            "devenv shell (`devenv shell` / `direnv allow`) so the python environment "
            "with speechbrain is on PATH, or use --speakers off."
        )
    savedir = embedding_dir()
    savedir.mkdir(parents=True, exist_ok=True)
    try:
        import torch
        device = "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        device = "cpu"
    if device == "cuda":
        print(f"Preparing speaker embedding model ({EMBEDDING_SOURCE}) on GPU ...", file=sys.stderr)
    else:
        print(f"Preparing speaker embedding model ({EMBEDDING_SOURCE}) ...", file=sys.stderr)
    _embedder = EncoderClassifier.from_hparams(
        source=EMBEDDING_SOURCE,
        savedir=str(savedir),
        run_opts={"device": device},
    )
    return _embedder


def embed_windows(pcm: "np.ndarray", segments: list[dict]) -> "np.ndarray":
    """L2-normalized ECAPA embedding per segment (192-d).

    Each segment is embedded as ONE window, zero-padded up to
    MIN_EMBED_SECONDS when shorter (never borrowing neighbor audio) and
    center-capped at MAX_EMBED_SECONDS when longer. Sub-second windows carry
    almost no speaker signal (measured: lag-1 cosine 0.16 at 0.6 s vs 0.51 at
    3 s on real audio), so shorter analysis windows are padded, not shrunk.
    """
    import numpy as np
    import torch

    classifier = load_embedder()
    sr = 16000
    total = pcm.shape[0]
    if total <= 0:
        return np.zeros((len(segments), 192), dtype=np.float32)

    short = int(MIN_EMBED_SECONDS * sr)
    cap = int(MAX_EMBED_SECONDS * sr)
    windows = []
    lens = []
    for seg in segments:
        t0 = int(seg["start"] * sr)
        t1 = int(seg["end"] * sr)
        if t1 <= t0 or t0 >= total:  # (near-)past the end of the audio: use the tail
            t0 = max(0, total - short)
            t1 = total
        dur = min(t1, total) - t0
        if dur <= 0:
            windows.append(np.zeros(short, dtype=np.float32))
            lens.append(short)
            continue
        if dur < short:  # zero-pad to the minimum analysis length
            w = np.zeros(short, dtype=np.float32)
            off = (short - dur) // 2
            w[off : off + dur] = pcm[t0 : t0 + dur]
            windows.append(w)
            lens.append(short)
        elif dur > cap:  # center-capped long window
            mid = (t0 + t1) // 2
            st = max(t0, mid - cap // 2)
            en = min(t1, st + cap)
            windows.append(np.ascontiguousarray(pcm[st:en]))
            lens.append(en - st)
        else:
            windows.append(np.ascontiguousarray(pcm[t0 : t0 + dur]))
            lens.append(dur)

    lens = np.array(lens)
    order = np.argsort(lens)
    inv = np.argsort(order)
    windows = [windows[i] for i in order]
    lens = lens[order]

    # Batch similar durations and pad each batch only to its own longest
    # window; shrink the batch for long windows (torch.stft/ECAPA transients
    # on long batches peak at several GB).
    nwin = len(windows)
    embs = []
    i = 0
    while i < nwin:
        chunk = windows[i : i + EMBED_CHUNK]
        chunk_lens = lens[i : i + EMBED_CHUNK]
        step = max(1, min(len(chunk), int(EMBED_BATCH_SECONDS * sr / max(chunk_lens))))
        chunk = chunk[:step]
        m = int(chunk_lens[:step].max())
        buf_np = np.zeros((step, m), dtype=np.float32)  # (B, T), speechbrain shape
        for r, w in enumerate(chunk):
            buf_np[r, : w.shape[0]] = w
        buf = torch.from_numpy(buf_np)
        blens = torch.tensor(np.asarray(chunk_lens[:step], dtype=np.float32) / m)
        print(f"  embedded {min(i + step, nwin)}/{nwin} segments ...", end="\r", file=sys.stderr, flush=True)
        with torch.no_grad():
            out = classifier.encode_batch(buf, blens)  # (B, 1, emb_dim)
        embs.append(out.squeeze(1).numpy())
        i += step
    print(file=sys.stderr)  # close the progress line
    embs = np.concatenate(embs, axis=0)[inv]
    norms = np.linalg.norm(embs, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return embs / norms


def cluster_segments(embs: "np.ndarray", k: int | None, max_speakers: int) -> list[int]:
    """Average-linkage agglomerative clustering on cosine distance.

    k=None picks the number of clusters from the largest gap between merge
    heights, searched only among the last `max_speakers` merges (the top of
    the dendrogram) so near-duplicate first merges can't dominate.
    Returns per-segment speaker index 0..k-1, ordered by first appearance.
    """
    import numpy as np

    n = embs.shape[0]
    if n <= 1:
        return [0] * n

    sim = embs @ embs.T
    D = (1.0 - sim).astype(np.float64)
    D[np.diag_indices(n)] = np.inf

    heights: list[float] = []
    merges: list[tuple[int, int]] = []
    size = [1] * n
    active = list(range(n))

    while len(active) > 1:
        sub = D[np.ix_(active, active)]
        i_rel, j_rel = np.unravel_index(int(np.argmin(sub)), sub.shape)
        i, j = active[i_rel], active[j_rel]
        heights.append(float(D[i, j]))
        merges.append((i, j))
        # Unweighted average (UPGMA): merge at the mean distance of all pairs.
        ni, nj = size[i], size[j]
        row = (ni * D[i, :] + nj * D[j, :]) / (ni + nj)
        size[i] = ni + nj
        D[i, :] = row
        D[:, i] = row
        D[i, i] = np.inf
        D[j, :] = np.inf
        D[:, j] = np.inf
        active.remove(j)

    heights_arr = np.array(heights)
    auto = k is None
    if auto:
        if n == 2:
            k = 1 if heights_arr[0] < 0.35 else 2  # similarity > 0.65 → same speaker
        else:
            # Largest gap among the last max_speakers merges (top of the tree):
            # k clusters means the cut sits between the k-th and (k+1)-th last merges.
            gaps = np.diff(heights_arr)
            start = max(0, gaps.size - max_speakers)
            idx = int(np.argmax(gaps[start:])) + start
            k = n - (idx + 1)
            k = min(max(k, 1), max_speakers)
    else:
        k = min(max(k, 1), n)

    if k == 1:
        return [0] * n

    # Cut after (n - k) merges; label by union-find connectivity.
    uf = list(range(n))
    def find(x: int) -> int:
        while uf[x] != x:
            uf[x] = uf[uf[x]]
            x = uf[x]
        return x

    for i, j in merges[: n - k]:
        ri, rj = find(i), find(j)
        if ri != rj:
            uf[rj] = ri

    labels_raw = [find(i) for i in range(n)]
    order: dict[int, int] = {}
    out = []
    for r in labels_raw:
        if r not in order:
            order[r] = len(order)
        out.append(order[r])
    return out


# --------------------------------------------------------------------------- output writers


def fmt_srt(t: float) -> str:
    ms = int(round(t * 1000))
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def fmt_vtt(t: float) -> str:
    ms = int(round(t * 1000))
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"


def speaker_turns(segments: list[dict], speakers: list[int]) -> "list[tuple[str, float, float, str]]":
    """Collapse consecutive segments of the same speaker into single turns.

    Returns (label, start, end, text) per turn: one line in .txt, one cue in
    .srt/.vtt, instead of repeating the speaker name on every segment.
    """
    turns: list[tuple[str, float, float, str]] = []
    for seg, spk in zip(segments, speakers):
        name = f"Speaker {spk + 1}"
        text = seg["text"].strip()
        if turns and turns[-1][0] == name:
            start, _, acc = turns[-1][1], turns[-1][2], turns[-1][3]
            turns[-1] = (name, start, seg["end"], f"{acc} {text}".strip())
        else:
            turns.append((name, seg["start"], seg["end"], text))
    return turns


def write_diarized_outputs(prefix: Path, segments: list[dict], speakers: list[int], formats: list[str]) -> None:
    """Write the requested formats for diarized segments (speaker per segment)."""
    turns = speaker_turns(segments, speakers)
    full_text = " ".join(seg["text"].strip() for seg in segments).strip()

    if "txt" in formats:
        with open(f"{prefix}.txt", "w", encoding="utf-8") as f:
            prev = None
            for seg, spk in zip(segments, speakers):
                if spk != prev:
                    if prev is not None:
                        f.write("\n")
                    f.write(f"[speaker {spk + 1}]\n")
                    prev = spk
                f.write(f"{seg['text'].strip()}\n")

    if "srt" in formats:
        with open(f"{prefix}.srt", "w", encoding="utf-8") as f:
            for i, (name, start, end, text) in enumerate(turns, 1):
                f.write(f"{i}\n{fmt_srt(start)} --> {fmt_srt(end)}\n{name}: {text}\n\n")

    if "vtt" in formats:
        with open(f"{prefix}.vtt", "w", encoding="utf-8") as f:
            f.write("WEBVTT\n\n")
            for name, start, end, text in turns:
                f.write(f"{fmt_vtt(start)} --> {fmt_vtt(end)}\n{name}: {text}\n\n")

    if "json" in formats:
        out = {
            "text": full_text,
            "speakers": len(set(speakers)),
            "segments": [
                {k: v for k, v in seg.items() if k in ("id", "seek", "start", "end", "text")}
                | {"speaker": s}
                for seg, s in zip(segments, speakers)
            ],
        }
        with open(f"{prefix}.json", "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)


def parse_segments(data: dict) -> list[dict]:
    """Normalize whisper-cli JSON to [{start, end, text}] with times in seconds.

    whisper.cpp >= 1.7 writes "transcription" with offsets in milliseconds;
    older versions write "segments" with start/end in seconds.
    """
    if "segments" in data:
        return data["segments"]
    out = []
    for seg in data.get("transcription", []):
        off = seg.get("offsets", {})
        out.append(
            {
                "start": off.get("from", 0) / 1000.0,
                "end": off.get("to", 0) / 1000.0,
                "text": seg.get("text", ""),
            }
        )
    return out


def _print_speaker_summary(embs: "np.ndarray", speakers: list[int]) -> None:
    """Print per-cluster cohesion/confusion numbers to stderr.

    within-cos low (< ~0.55) while another cluster's is high: that cluster may
    hold two voices merged. Cluster-to-cluster cos ~0.6+: those two voices
    overlap in embedding space and cannot be separated by clustering.
    """
    import numpy as np

    labels = np.asarray(speakers)
    k = labels.max() + 1
    ids = np.unique(labels)
    rows = []
    for c in ids:
        m = labels == c
        n = int(m.sum())
        e = embs[m]
        if n > 1:
            s = e @ e.T
            wc = (s.sum() - np.trace(s)) / (n * (n - 1))
        else:
            wc = 1.0
        rows.append((c, n, float(wc)))
    for c, n, wc in rows:
        print(f"  speaker {c + 1}: {n} segments, within-cos {wc:.3f}", file=sys.stderr)
    if k > 1:
        pair = []
        for a, _, _ in rows:
            for b, _, _ in rows:
                if b <= a:
                    continue
                ma, mb = labels == a, labels == b
                pair.append((a, b, float((embs[ma] @ embs[mb].T).mean())))
        print(
            "  cluster-to-cluster cosine: "
            + "  ".join(f"{a + 1}-{b + 1} {v:.3f}" for a, b, v in pair),
            file=sys.stderr,
        )


def _rescue_short_segments(
    segments: list[dict], speakers: list[int], embs: "np.ndarray"
) -> list[int]:
    """Label ultra-short whisper fragments by nearest cluster centroid.

    Whisper emits 50-500 ms fragments ("Right.", "Yeah."); zero-padded to the
    2 s embed window they are mostly silence, yet they still carry a weak but
    real voice signal (measured: a 60 ms backchannel shows +0.124 cosine to
    the right speaker's centroid vs -0.048 to the wrong side). Fragments are
    excluded from clustering so they can't fabricate [speaker N] or steal a
    --speakers N slot, then assigned the cluster whose centroid their own
    embedding is closest to — including non-neighbor speakers, so a genuine
    short interjection stays with its own voice.
    """
    import numpy as np

    ok = {i for i, s in enumerate(segments) if s["end"] - s["start"] >= SHORT_SEGMENT_SECONDS}
    if not ok:
        return list(speakers)
    n_clusters = max(speakers) + 1
    cents = np.zeros((n_clusters, embs.shape[1]))
    for c in range(n_clusters):
        mem = np.array([j for j in ok if speakers[j] == c])
        if mem.size:
            s = embs[mem].sum(axis=0)
            nr = np.linalg.norm(s)
            cents[c] = s / (nr if nr else 1.0)
    out = list(speakers)
    for i in range(len(segments)):
        if i in ok:
            continue
        sims = embs[i] @ cents.T
        valid = np.flatnonzero(np.abs(cents).sum(axis=1) > 0)
        if valid.size == 0:
            continue
        out[i] = int(valid[int(np.argmax(sims[valid]))])
    return out


def diarize_and_write(
    prefix: Path,
    audio: str,
    segments: list[dict],
    speakers_arg: str | int,
    max_speakers: int,
    formats: list[str],
) -> None:
    """Assign speakers and write the requested formats for the given segments."""
    try:
        import numpy as np  # noqa: F401
    except ImportError:
        sys.exit(
            "error: speaker diarization needs numpy (in the devenv python environment); "
            "re-enter the shell or use --speakers off."
        )

    pcm = decode_pcm(audio)
    idxs = [i for i, s in enumerate(segments) if s["end"] - s["start"] >= SHORT_SEGMENT_SECONDS]
    if not idxs:  # everything is ultra-short: cluster it all anyway
        idxs = list(range(len(segments)))
    embs = embed_windows(pcm, segments)  # all segments: micros needed for rescue side-choice
    k = None if speakers_arg == "auto" else int(speakers_arg)
    labels = cluster_segments(embs[idxs], k, max_speakers)
    speakers = [0] * len(segments)
    for pos, i in enumerate(idxs):
        speakers[i] = labels[pos]
    speakers = _rescue_short_segments(segments, speakers, embs)
    try:
        _print_speaker_summary(embs[idxs], [speakers[i] for i in idxs])
    except Exception:  # diagnostics must never break the run
        pass
    write_diarized_outputs(prefix, segments, speakers, formats)


# --------------------------------------------------------------------------- main


def transcribe_one(
    audio: str,
    *,
    model: str,
    device: str | int,
    lang: str,
    formats: list[str],
    out_dir: str | None,
    threads: int | None,
    verbose: bool,
    speakers: str | int,
    max_speakers: int,
) -> int:
    if not os.path.isfile(audio):
        print(f"error: no such file: {audio}", file=sys.stderr)
        return 1

    model_path = resolve_model(model)
    if model_path is None:
        model_path = download_model(model)

    prefix = Path(out_dir) / Path(audio).stem if out_dir else Path(audio).with_suffix("")
    if out_dir:
        Path(out_dir).mkdir(parents=True, exist_ok=True)

    cmd = [
        whisper_cli(),
        "-m", str(model_path),
        "-f", audio,
        "-l", lang,
        *device_args(device),
    ]
    if threads is not None:
        cmd += ["-t", str(threads)]
    cmd += ["-of", str(prefix)]

    diarizing = speakers != "off"
    if diarizing:
        # whisper-cli writes the segments JSON; we rewrite all outputs with speakers
        cmd += ["-oj"]
    else:
        for fmt in formats:
            cmd.append(VALID_FORMATS[fmt])

    if verbose:
        print("+ " + " ".join(cmd), file=sys.stderr)

    print(f"Transcribing {audio} ...", file=sys.stderr)
    try:
        result = subprocess.run(cmd)
    except KeyboardInterrupt:
        return 130
    if result.returncode != 0:
        print(
            f"error: transcription failed for {audio} (whisper-cli exit {result.returncode}). "
            f"Add --verbose to see the full command.",
            file=sys.stderr,
        )
        return result.returncode

    if not diarizing:
        missing = [f for f in formats if not Path(f"{prefix}.{f}").is_file()]
        if missing:
            print(f"error: expected output file(s) not produced: {', '.join(missing)}", file=sys.stderr)
            return 1
        for f in formats:
            print(f"  wrote {prefix}.{f}")
        return 0

    # Diarization: parse whisper-cli's JSON, then rewrite outputs with speaker labels.
    src = Path(f"{prefix}.json")
    if not src.is_file():
        print(f"error: whisper-cli did not produce its JSON output ({src})", file=sys.stderr)
        return 1
    try:
        with open(src, encoding="utf-8") as f:
            data = json.load(f)
        segments = parse_segments(data)
    except (json.JSONDecodeError, ValueError) as e:
        print(f"error: cannot parse whisper-cli JSON output: {e}", file=sys.stderr)
        return 1

    if not segments:
        print("  (no speech segments detected)", file=sys.stderr)
        for fmt in formats:
            if fmt == "json":
                Path(f"{prefix}.json").write_text('{"text": "", "segments": []}\n', encoding="utf-8")
            else:
                Path(f"{prefix}.{fmt}").touch()
        if "json" not in formats:
            src.unlink(missing_ok=True)
        return 0

    print(f"Detecting speakers across {len(segments)} segments ...", file=sys.stderr)
    diarize_and_write(prefix, audio, segments, speakers, max_speakers, formats)
    if "json" not in formats:
        src.unlink(missing_ok=True)  # whisper-cli's plain JSON; diarized JSON written by us only on request
    for fmt in formats:
        if Path(f"{prefix}.{fmt}").is_file():
            print(f"  wrote {prefix}.{fmt}")
    return 0


def parse_speakers(raw: str) -> str | int:
    if raw in ("off", "auto"):
        return raw
    try:
        n = int(raw)
    except ValueError:
        raise argparse.ArgumentTypeError(f"invalid --speakers value {raw!r} (use off, auto, or a number)")
    if n < 1:
        raise argparse.ArgumentTypeError("--speakers must be >= 1")
    return n


def parse_device(raw: str) -> str | int:
    if raw in ("auto", "cpu", "gpu"):
        return raw
    try:
        n = int(raw)
    except ValueError:
        raise argparse.ArgumentTypeError(
            f"invalid --device value {raw!r} (use auto, cpu, gpu, or a GPU index like 0 or 1)"
        )
    if n < 0:
        raise argparse.ArgumentTypeError("--device GPU index must be >= 0")
    return n


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="transcribe",
        description=(
            "Transcribe audio locally with whisper.cpp (GPU via Vulkan; AMD + NVIDIA), "
            "with optional speaker diarization."
        ),
        epilog=(
            "Models: GGML weights from ggerganov/whisper.cpp on Hugging Face, cached in "
            "$WHISPER_MODELS_DIR (default ~/.cache/whisper-cpp/models). Set WHISPER_MODEL "
            "for a different default model. Multi-GPU: pick the Vulkan device with the "
            "WHISPER_ARG_DEVICE environment variable (0, 1, ...)."
        ),
    )
    parser.add_argument("audio", nargs="+", help="audio file(s) to transcribe (any format: mp3, m4a, wav, ogg, ...)")
    parser.add_argument(
        "-m", "--model",
        default=os.environ.get("WHISPER_MODEL", DEFAULT_MODEL),
        help=f"GGML model name (auto-downloaded on first use) or path to a .bin file (default: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--device", type=parse_device,
        default=os.environ.get("WHISPER_ARG_DEVICE", "auto"),
        help=(
            "compute device: auto = first GPU (default), cpu, gpu (GPU 0), or a GPU index "
            "(0, 1, ...) to pick a discrete NVIDIA card over an integrated AMD iGPU; "
            "env WHISPER_ARG_DEVICE sets the default"
        ),
    )
    parser.add_argument("-l", "--lang", default="en", help="spoken language (default: en)")
    parser.add_argument(
        "--speakers", type=parse_speakers, default="auto",
        help="speaker diarization: auto (default, detect speaker count), N (force N speakers), or off",
    )
    parser.add_argument(
        "--max-speakers", type=int, default=MAX_SPEAKERS,
        help=f"upper bound for auto speaker detection (default: {MAX_SPEAKERS})",
    )
    parser.add_argument(
        "-f", "--format", default="txt,srt",
        help="comma-separated output formats: txt,srt,vtt,json (default: txt,srt)",
    )
    parser.add_argument("-o", "--out-dir", help="directory for output files (default: next to each input)")
    parser.add_argument(
        "-t", "--threads", type=int, default=os.cpu_count() or 4,
        help=f"CPU threads for inference (default: all {os.cpu_count() or 4} cores)",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="print the exact whisper-cli command")
    args = parser.parse_args(argv)

    formats = [f for f in args.format.split(",") if f]
    unknown = [f for f in formats if f not in VALID_FORMATS]
    if unknown:
        print(f"error: unknown format(s): {', '.join(unknown)} (valid: {','.join(VALID_FORMATS)})", file=sys.stderr)
        return 1
    if not formats:
        print("error: --format is empty", file=sys.stderr)
        return 1

    status = 0
    for audio in args.audio:
        rc = transcribe_one(
            audio,
            model=args.model,
            device=args.device,
            lang=args.lang,
            formats=formats,
            out_dir=args.out_dir,
            threads=args.threads,
            verbose=args.verbose,
            speakers=args.speakers,
            max_speakers=args.max_speakers,
        )
        status = status or rc
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))