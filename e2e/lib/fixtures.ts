// The e2e suite's fixture clips (FIXTURE_CLIPS, MERGE_CLIP), written into a fresh directory by
// global-setup.ts and by lib/fixtures.test.ts.
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  BROKEN2_CLIP,
  BROKEN_CLIP,
  FIXTURE_CLIPS,
  MERGE_CLIP,
  MERGE_SOURCE_IDS,
  librispeechDir,
} from './backend.ts';

const SAMPLE_RATE = 16000;

// The managed ffmpeg is the app's audio-only build: it has the decoders and the f32le muxer but
// no `wav` muxer and no `concat` filter (roadmap item 69). It decodes a source to 16 kHz mono
// f32le — the app's own decoder path — and the clips are cut, looped and written as PCM WAV here.
function decodeSamples(file: string): Float32Array {
  const bytes = execFileSync(
    'ffmpeg',
    [
      '-v',
      'error',
      '-i',
      file,
      '-vn',
      '-ac',
      '1',
      '-ar',
      String(SAMPLE_RATE),
      '-f',
      'f32le',
      'pipe:1',
    ],
    { maxBuffer: 64 * 1024 * 1024 },
  );
  const samples = new Float32Array(bytes.length / 4);
  for (let i = 0; i < samples.length; i++) samples[i] = bytes.readFloatLE(i * 4);
  return samples;
}

function samplesOf(samples: Float32Array, startSeconds = 0, seconds?: number): Float32Array {
  const from = Math.round(startSeconds * SAMPLE_RATE);
  const to =
    seconds === undefined
      ? samples.length
      : Math.min(samples.length, from + Math.round(seconds * SAMPLE_RATE));
  return samples.slice(from, to);
}

function repeatSamples(samples: Float32Array, times: number): Float32Array {
  const out = new Float32Array(samples.length * times);
  for (let i = 0; i < times; i++) out.set(samples, i * samples.length);
  return out;
}

function concatSamples(parts: Float32Array[]): Float32Array {
  const out = new Float32Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

// 16 kHz mono 16-bit PCM WAV.
function wavPcm16(samples: Float32Array): Buffer {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((value, i) => {
    const sample = Math.max(-1, Math.min(1, value));
    data.writeInt16LE(Math.round(sample < 0 ? sample * 0x8000 : sample * 0x7fff), i * 2);
  });
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(SAMPLE_RATE, 24);
  header.writeUInt32LE(SAMPLE_RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

export function writeFixtures(fixturesDir: string): void {
  // The journey's clips: the committed LibriSpeech sample plus distinct trims and loops of it —
  // every fixture must have unique bytes (the backend deduplicates by content sha256 and the
  // tests share one backend). Short trims still transcribe as real speech.
  // `seconds` is the window after `start`; `loops` repeats the whole sample (the old
  // `-stream_loop N` meant N+1 copies).
  const recipes = [
    { file: FIXTURE_CLIPS[0] }, // the full sample
    { seconds: 6, file: FIXTURE_CLIPS[1] }, // 0-6s
    { start: 3, seconds: 7, file: FIXTURE_CLIPS[2] }, // 3-10s
    { seconds: 2, file: FIXTURE_CLIPS[3] }, // 0-2s
    { start: 2, seconds: 2, file: FIXTURE_CLIPS[4] }, // 2-4s
    { start: 4, seconds: 2, file: FIXTURE_CLIPS[5] }, // 4-6s
    { loops: 12, file: FIXTURE_CLIPS[6] }, // the sample 12 times (~132 s)
    { loops: 20, file: FIXTURE_CLIPS[8] }, // the sample 20 times (~220 s)
    { start: 6, seconds: 5, file: FIXTURE_CLIPS[10] }, // 6-11s
    { seconds: 3, file: FIXTURE_CLIPS[11] }, // 0-3s, item 12
    { start: 7, seconds: 4, file: FIXTURE_CLIPS[12] }, // 7-11s, item 12
    { seconds: 7, file: FIXTURE_CLIPS[13] }, // 0-7s, item 13
    { start: 4, seconds: 7, file: FIXTURE_CLIPS[14] }, // 4-11s, item 13
    { loops: 8, file: FIXTURE_CLIPS[16] }, // ~88s, screenshots
    { seconds: 4, file: FIXTURE_CLIPS[17] }, // 0-4s, screenshots' upload-in-flight
    { seconds: 5, file: FIXTURE_CLIPS[18] }, // 0-5s, screenshots' second running seed
    { loops: 9, file: FIXTURE_CLIPS[19] }, // ~99s, the header/nav e2e (item 31)
    { start: 5, seconds: 2, file: FIXTURE_CLIPS[20] }, // 5-7s, item 14's contract check
    { start: 1, seconds: 7, file: FIXTURE_CLIPS[21] }, // 1-8s, the settings e2e (item 32)
    { loops: 14, file: FIXTURE_CLIPS[22] }, // ~154s, item 36's follow-mode e2e
    { start: 3, seconds: 2, file: FIXTURE_CLIPS[23] }, // 3-5s, item 37's completed player clip
    { loops: 6, file: FIXTURE_CLIPS[24] }, // ~66s, item 37's background transcription
    { loops: 10, file: FIXTURE_CLIPS[25] }, // ~110s, item 40's atomic-transitions race clip
    { start: 6, seconds: 3, file: FIXTURE_CLIPS[26] }, // 6-9s, item 40's queue-is-live follow-up
    { loops: 11, file: FIXTURE_CLIPS[27] }, // ~121s, item 40's pause-queued running clip
    { start: 2, seconds: 4, file: FIXTURE_CLIPS[28] }, // 2-6s, item 40's pause-queued clip
    { start: 5, seconds: 6, file: FIXTURE_CLIPS[29] }, // 5-11s, item 42's queue-survives-a-failure clip
    { start: 8, seconds: 3, file: FIXTURE_CLIPS[31] }, // 8-11s, item 41's concurrent-upload race
    { start: 2, seconds: 6, file: FIXTURE_CLIPS[32] }, // 2-8s, item 59's favicon typing clip
    { start: 3, seconds: 3, file: FIXTURE_CLIPS[33] }, // 3-6s, item 59's upload-options clip
    { loops: 16, file: FIXTURE_CLIPS[34] }, // ~176s, item 59's running-row clip
    { start: 1, seconds: 2, file: FIXTURE_CLIPS[35] }, // 1-3s, item 59's queued-row clip
    { start: 9, seconds: 2, file: FIXTURE_CLIPS[36] }, // 9-11s, item 59's history-row clip
    { loops: 4, file: FIXTURE_CLIPS[38] }, // ~44s, item 59's row-controls clip
    { start: 7, seconds: 2, file: FIXTURE_CLIPS[39] }, // 7-9s, item 59's row-controls second clip
    { loops: 3, file: FIXTURE_CLIPS[40] }, // ~33s, item 59's single-bar row clip
    { loops: 13, file: FIXTURE_CLIPS[41] }, // ~143s, item 59's elapsed-time row clip
    { loops: 5, file: FIXTURE_CLIPS[42] }, // ~55s, item 59's phase-color row clip
    { loops: 17, file: FIXTURE_CLIPS[44] }, // ~187s, item 59's header-typing clip
    { loops: 2, file: FIXTURE_CLIPS[45] }, // ~22s, item 48's Metal app e2e
    { loops: 15, file: FIXTURE_CLIPS[46] }, // ~165s, item 81's held-pause running clip
    { start: 1, seconds: 4, file: FIXTURE_CLIPS[47] }, // 1-5s, item 81's held-pause queued clip
  ];
  const jfk = process.env.TEST_JFK_WAV;
  if (!jfk) throw new Error('TEST_JFK_WAV is unset');
  const sample = decodeSamples(jfk);
  for (const { file, start = 0, seconds, loops = 1 } of recipes) {
    const clip = repeatSamples(samplesOf(sample, start, seconds), loops);
    writeFileSync(join(fixturesDir, file), wavPcm16(clip));
  }
  // The two-speaker recording: the three committed LibriSpeech clips back to back.
  const merge = MERGE_SOURCE_IDS.map((id) => decodeSamples(join(librispeechDir, `${id}.flac`)));
  writeFileSync(join(fixturesDir, MERGE_CLIP), wavPcm16(concatSamples(merge)));
  // A .wav whose bytes are not audio: it passes the upload's extension check and fails
  // when the engine decodes it (CHUNKING).
  writeFileSync(
    join(fixturesDir, BROKEN_CLIP),
    "not audio — the e2e suite's undecodable fixture\n".repeat(256),
  );
  writeFileSync(
    join(fixturesDir, BROKEN2_CLIP),
    "also not audio — the history test's own undecodable fixture\n".repeat(256),
  );
  writeFileSync(
    join(fixturesDir, FIXTURE_CLIPS[30]),
    "not audio either — item 42's queue-survives-a-failure fixture\n".repeat(256),
  );
  writeFileSync(
    join(fixturesDir, FIXTURE_CLIPS[37]),
    "still not audio — item 59's history-row retry fixture\n".repeat(256),
  );
  writeFileSync(
    join(fixturesDir, FIXTURE_CLIPS[43]),
    "still not audio — item 59's status-page delete fixture\n".repeat(256),
  );
}
