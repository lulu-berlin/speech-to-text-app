# Attributions

Third-party data used by the tests. The files are fetched by hash through Nix ([nix/test-fixtures.nix](../nix/test-fixtures.nix)) and never committed to this repository.

## LibriSpeech ASR corpus

- Work: LibriSpeech ASR corpus, subset `test-clean` — <https://www.openslr.org/12>
- Copyright: LibriSpeech (c) 2014 by Vassil Panayotov
- License: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- Reference: V. Panayotov, G. Chen, D. Povey, S. Khudanpur, "LibriSpeech: an ASR corpus based on public domain audio books", ICASSP 2015.
- Based on LibriVox public-domain audio book recordings; readers of the clips used here: speaker 1089 (Peter Bobbe), speaker 1188 (Duncan Murrell).
- Used: utterances `1089-134686-0002`, `1089-134691-0023`, `1188-133604-0025`, unmodified (test fixtures of roadmap item 08, speaker embeddings; exported as `TEST_LIBRISPEECH_CLIPS`).
