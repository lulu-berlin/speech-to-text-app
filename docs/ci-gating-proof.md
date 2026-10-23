# Proof: a documentation-only pull request skips the heavy jobs

Throwaway branch for roadmap item 80. Its pull request changes this file only, so `Changes (code vs
 documentation)` must report `code=false`, every heavy job must be skipped, and `CI (all checks)`
must stay green. Close the pull request without merging.
