package stt

import java.nio.file.Path

/**
 * The path in the test-only environment variable [name] (a `TEST_*` fixture exported by `devenv.nix` from `nix/`).
 * A missing variable fails the test naming it, so the failure says how to fix the environment.
 */
internal fun requireTestEnv(name: String): Path =
    Path.of(
        System.getenv(name)
            ?: throw IllegalStateException(
                "Integration test needs $name set: enter the devenv shell that exports it, or pass it explicitly " +
                    "(see workspaces/README.md § Environment for workspace agents)",
            ),
    )
