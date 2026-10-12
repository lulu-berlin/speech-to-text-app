// Checks that the architecture overview's diagram matches the running system (roadmap
// item 35): every box of the Mermaid diagram is a real component and every arrow a real
// call or event. Each box and arrow has its evidence here — the files and code that make
// it real, down to where the running app wires the real implementation in. A box or arrow
// in the diagram without evidence fails, evidence for a box or arrow no longer in the
// diagram fails, and evidence that no longer matches the code fails. Exits 1 on any failure.
//
//   node docs/check-architecture-diagram.mjs [docs/architecture.md]
import { existsSync, readFileSync } from 'node:fs';

const STT = 'backend/src/main/kotlin/stt';
const CLIENT = 'frontend/src/shared/api/client.ts';
const ENGINE = `${STT}/engine/ProcessingEngine.kt`;
const ENGINE_MODULE = `${STT}/engine/EngineModule.kt`;

// Box id → what makes it a real component: [file, pattern] pairs that must all match.
const boxes = {
  Browser: [['frontend/index.html', /id="root"/]],
  UI: [['frontend/src/main.tsx', /createRoot\(/]],
  JVM: [[`${STT}/Main.kt`, /embeddedServer\(/]],
  Routes: [[`${STT}/http/SttApp.kt`, /taskRoutes\(\)/]],
  SSE: [[`${STT}/http/EventsRoutes.kt`, /get\("\/api\/events"\)/]],
  Engine: [
    [ENGINE, /class ProcessingEngine/],
    [ENGINE_MODULE, /launch \{ engine\.start\(\) \}/],
  ],
  DB: [[`${STT}/db/Database.kt`, /resolve\("stt\.db"\)/]],
  Speaker: [[`${STT}/diarization/SpeakerModel.kt`, /OrtEnvironment\.getEnvironment\(\)/]],
  Files: [[`${STT}/db/TaskWorkDirectories.kt`, /resolve\("work"\)[\s\S]*resolve\("tmp"\)/]],
  FFmpeg: [[`${STT}/audio/Ffmpeg.kt`, /"ffmpeg"/]],
  Whisper: [[`${STT}/transcribe/ChunkTranscriber.kt`, /"whisper-cli"/]],
};

// "from->to" → what makes the arrow a real call or event.
const arrows = {
  'UI->Routes': [
    [CLIENT, /fetch\('\/api\/tasks'\)/],
    [CLIENT, /xhr\.open\('POST', '\/api\/tasks'\)/],
  ],
  'SSE->UI': [
    [CLIENT, /new EventSource\('\/api\/events'\)/],
    [`${STT}/http/EventsRoutes.kt`, /"snapshot"/],
  ],
  'Routes->Engine': [
    [`${STT}/http/TaskCommandRoutes.kt`, /engine\.pauseTask\(/],
    [`${STT}/http/QueueRoutes.kt`, /engine\./],
  ],
  'Routes->DB': [[`${STT}/http/TaskRoutes.kt`, /TaskRepository by dependencies/]],
  'Routes->Files': [
    [`${STT}/http/UploadRoutes.kt`, /spoolChannel/],
    [`${STT}/http/AudioRoutes.kt`, /cache\.getOrBuild\(/],
  ],
  'Routes->FFmpeg': [
    [`${STT}/http/AudioRoutes.kt`, /joiner\.join\(/],
    [`${STT}/http/SttApp.kt`, /FfmpegAudioJoiner\(/],
    [`${STT}/audio/FfmpegAudioJoiner.kt`, /FFMPEG/],
  ],
  'Engine->DB': [[ENGINE, /private val tasks: TaskRepository/]],
  'Engine->SSE': [
    [ENGINE, /_events\.emit\(/],
    [`${STT}/http/EventStream.kt`, /ProcessingEngine/],
    [`${STT}/http/EventsRoutes.kt`, /EventStream by dependencies/],
  ],
  'Engine->Files': [[ENGINE, /workDirectories\.workDir\(/]],
  'Engine->FFmpeg': [
    [ENGINE, /chunker\.chunk\(/],
    [ENGINE_MODULE, /AudioChunker\(/],
    [`${STT}/audio/AudioChunker.kt`, /FFMPEG/],
  ],
  'Engine->Whisper': [
    [ENGINE, /ChunkTranscription\(transcriber/],
    [ENGINE_MODULE, /ChunkTranscriber\(/],
    [`${STT}/transcribe/ChunkTranscriber.kt`, /"whisper-cli"/],
  ],
  'Engine->Speaker': [
    [ENGINE, /diarizer\.diarize\(/],
    [ENGINE_MODULE, /SpeakerDiarizer\(/],
    [`${STT}/diarization/SpeakerDiarizer.kt`, /SpeakerModel\.load\(/],
  ],
};

// The boxes (nodes and subgraphs) and the labelled arrows of the first Mermaid block.
function diagramOf(markdown) {
  const block = /```mermaid\n([\s\S]*?)```/.exec(markdown);
  if (block === null) {
    return null;
  }
  const found = { boxes: new Set(), arrows: new Map() };
  for (const line of block[1].split('\n')) {
    const arrow = /^\s*(\w+)\s+--\s+"([^"]+)"\s+-->\s+(\w+)\s*$/.exec(line);
    if (arrow !== null) {
      found.arrows.set(`${arrow[1]}->${arrow[3]}`, arrow[2]);
      continue;
    }
    const subgraph = /^\s*subgraph\s+(\w+)/.exec(line);
    const node = /^\s*(\w+)\[/.exec(line);
    if (subgraph !== null) found.boxes.add(subgraph[1]);
    else if (node !== null) found.boxes.add(node[1]);
  }
  return found;
}

function failedEvidence(evidence) {
  return evidence
    .filter(([path, pattern]) => !existsSync(path) || !pattern.test(readFileSync(path, 'utf8')))
    .map(([path, pattern]) => `${path} ${pattern}`);
}

const file = process.argv[2] ?? 'docs/architecture.md';
const diagram = diagramOf(readFileSync(file, 'utf8'));
const failures = [];
if (diagram === null) {
  failures.push(`${file}: no Mermaid diagram`);
} else {
  for (const box of diagram.boxes) {
    if (!(box in boxes)) failures.push(`box ${box}: no evidence that it is a real component`);
  }
  for (const [arrow, label] of diagram.arrows) {
    if (!(arrow in arrows)) failures.push(`arrow ${arrow} ("${label}"): no evidence of the call`);
  }
  for (const [box, evidence] of Object.entries(boxes)) {
    if (!diagram.boxes.has(box)) failures.push(`box ${box}: has evidence but is not in the diagram`);
    for (const missing of failedEvidence(evidence)) failures.push(`box ${box}: ${missing} does not match`);
  }
  for (const [arrow, evidence] of Object.entries(arrows)) {
    if (!diagram.arrows.has(arrow)) failures.push(`arrow ${arrow}: has evidence but is not in the diagram`);
    for (const missing of failedEvidence(evidence)) failures.push(`arrow ${arrow}: ${missing} does not match`);
  }
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(
  `The diagram in ${file} matches the code: ${diagram.boxes.size} boxes, ${diagram.arrows.size} arrows.`,
);
