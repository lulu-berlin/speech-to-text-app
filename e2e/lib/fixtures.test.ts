// The fixture generator needs only the app's ffmpeg surface (roadmap item 69): the audio-only
// build has the decoders and the f32le muxer, but no `wav` muxer and no `concat` filter. Only
// macOS CI enforces that surface — `./gradlew e2e` puts the pinned slim artifact first on PATH
// there, while on Linux it runs bytedeco's full build. The test checks the generator with
// whichever ffmpeg is first on PATH; set TEST_JFK_WAV as `./gradlew e2e` does.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { FIXTURE_CLIPS, MERGE_CLIP } from './backend.ts';
import { writeFixtures } from './fixtures.ts';

interface Wav {
  format: number;
  channels: number;
  sampleRate: number;
  bitsPerSample: number;
  riffSize: number;
  byteRate: number;
  blockAlign: number;
  samples: number;
}

// The fmt and data chunks of a RIFF/WAVE file; other chunks (LIST) are skipped.
function readWav(bytes: Buffer): Wav {
  assert.equal(bytes.toString('latin1', 0, 4), 'RIFF');
  assert.equal(bytes.toString('latin1', 8, 12), 'WAVE');
  let fmt: Omit<Wav, 'samples' | 'riffSize'> | undefined;
  let dataBytes: number | undefined;
  for (let at = 12; at + 8 <= bytes.length;) {
    const id = bytes.toString('latin1', at, at + 4);
    const size = bytes.readUInt32LE(at + 4);
    if (id === 'fmt ') {
      fmt = {
        format: bytes.readUInt16LE(at + 8),
        channels: bytes.readUInt16LE(at + 10),
        sampleRate: bytes.readUInt32LE(at + 12),
        byteRate: bytes.readUInt32LE(at + 16),
        blockAlign: bytes.readUInt16LE(at + 20),
        bitsPerSample: bytes.readUInt16LE(at + 22),
      };
    } else if (id === 'data') {
      dataBytes = size;
    }
    at += 8 + size + (size % 2);
  }
  assert.ok(fmt, 'no fmt chunk');
  assert.ok(dataBytes !== undefined, 'no data chunk');
  return {
    ...fmt,
    riffSize: bytes.readUInt32LE(4),
    samples: dataBytes / (fmt.channels * (fmt.bitsPerSample / 8)),
  };
}

type WavFormat = Pick<Wav, 'format' | 'channels' | 'sampleRate' | 'bitsPerSample' | 'samples'>;

const PCM_16K_MONO_16 = { format: 1, channels: 1, sampleRate: 16000, bitsPerSample: 16 };

test('the fixture generator writes 16 kHz mono PCM WAV clips with the app ffmpeg alone', () => {
  const dir = mkdtempSync(join(tmpdir(), 'stt-e2e-fixtures-test-'));
  try {
    writeFixtures(dir);
    // RIFF: the declared size is the file minus the 8-byte header; the byte rate and block align
    // follow the format (sample rate × channels × bytes per sample; channels × bytes per sample).
    const clip = (file: string | undefined): WavFormat => {
      assert.ok(file, 'a clip index past the end of FIXTURE_CLIPS');
      const bytes = readFileSync(join(dir, file));
      const wav = readWav(bytes);
      const bytesPerSample = wav.bitsPerSample / 8;
      assert.deepEqual(
        {
          riffSize: wav.riffSize,
          byteRate: wav.byteRate,
          blockAlign: wav.blockAlign,
        },
        {
          riffSize: bytes.length - 8,
          byteRate: wav.sampleRate * wav.channels * bytesPerSample,
          blockAlign: wav.channels * bytesPerSample,
        },
        `${file}'s WAV header is inconsistent`,
      );
      const { format, channels, sampleRate, bitsPerSample, samples } = wav;
      return { format, channels, sampleRate, bitsPerSample, samples };
    };
    // The pinned jfk.wav is itself 16 kHz mono 16-bit; its length comes from its own header.
    const jfk = process.env.TEST_JFK_WAV;
    assert.ok(jfk, 'TEST_JFK_WAV is unset');
    const header = readWav(readFileSync(jfk));
    const n = header.samples;
    assert.deepEqual(
      {
        format: header.format,
        channels: header.channels,
        sampleRate: header.sampleRate,
        bitsPerSample: header.bitsPerSample,
      },
      PCM_16K_MONO_16,
    );

    assert.deepEqual(clip(FIXTURE_CLIPS[0]), { ...PCM_16K_MONO_16, samples: n }); // the full sample
    assert.deepEqual(clip(FIXTURE_CLIPS[2]), { ...PCM_16K_MONO_16, samples: 7 * 16000 }); // 3-10 s
    assert.deepEqual(clip(FIXTURE_CLIPS[36]), { ...PCM_16K_MONO_16, samples: 2 * 16000 }); // 9-11 s
    assert.deepEqual(clip(FIXTURE_CLIPS[6]), { ...PCM_16K_MONO_16, samples: 12 * n }); // 12 copies
    // The three LibriSpeech clips back to back; their FLAC STREAMINFO totals at 16 kHz mono.
    assert.deepEqual(clip(MERGE_CLIP), {
      ...PCM_16K_MONO_16,
      samples: 106000 + 119200 + 123760,
    });

    // The backend deduplicates uploads by content sha256, so every fixture needs its own bytes.
    const files = readdirSync(dir);
    const hashes = files.map((file) =>
      createHash('sha256')
        .update(readFileSync(join(dir, file)))
        .digest('hex'),
    );
    assert.equal(new Set(hashes).size, files.length);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
