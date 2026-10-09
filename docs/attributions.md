# Attributions

Third-party data committed to this repository as test fixtures (ADR 0026). Each directory also holds the licence notice.

## LibriSpeech ASR corpus

- Work: LibriSpeech ASR corpus, subset `test-clean` — <https://www.openslr.org/12>
- Copyright: LibriSpeech (c) 2014 by Vassil Panayotov
- License: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- Reference: V. Panayotov, G. Chen, D. Povey, S. Khudanpur, "LibriSpeech: an ASR corpus based on public domain audio books", ICASSP 2015.
- Based on LibriVox public-domain audio book recordings; readers of the clips used here: speaker 1089 (Peter Bobbe), speaker 1188 (Duncan Murrell), speaker 260 (Brad Bush), speaker 121 (Nikolle Doolin), speaker 237 (rachelellen), speaker 1221 (Dianne).
- Used, unmodified, in [backend/src/test/resources/librispeech/](../backend/src/test/resources/librispeech/):
  - utterances `1089-134686-0002`, `1089-134691-0023`, `1188-133604-0025` (test fixtures of roadmap item 08, speaker embeddings);
  - utterances `1089-134686-0000`, `1188-133604-0000`, `1188-133604-0001`, `260-123286-0000`, `260-123288-0001`, `260-123440-0010`, `121-121726-0000`, `121-123859-0003`, `121-127105-0000`, `237-126133-0001`, `237-134493-0001`, `237-134500-0000`, `1221-135766-0004`, `1221-135766-0006`, `1221-135767-0005` (test fixtures of roadmap item 09, speaker-distance threshold).
