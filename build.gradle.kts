import groovy.json.JsonSlurper

// Root Gradle build for human developers without Nix (roadmap item 33). This cycle
// only verifies version sync; the runtime downloads, the node/pnpm frontend build
// and the run/dev tasks land with their test-list items.
//
// checkVersions fails when versions.json drifts from the copies the Gradle world
// owns: the wrapper's Gradle version (gradle/wrapper/gradle-wrapper.properties) and
// the locked nixpkgs rev (flake.lock - node, pnpm, gradle, jdk and ffmpeg all come
// from that rev). The flake's versions-sync check covers the manifest-vs-flake side;
// together they close the loop until `nix run .#update-versions` regenerates the
// manifest.
val manifest = JsonSlurper().parse(rootProject.file("versions.json")) as Map<*, *>

tasks.register("checkVersions") {
    group = "verification"
    description = "Fail when the committed versions.json drifts from the Gradle wrapper or the locked nixpkgs (roadmap item 33)"
    doLast {
        // 1. The wrapper's Gradle version must match the manifest (both are committed copies).
        val wrapperProperties = java.util.Properties()
        rootProject.file("gradle/wrapper/gradle-wrapper.properties").inputStream().use(wrapperProperties::load)
        val distributionUrl = wrapperProperties.getProperty("distributionUrl")
        val wrapperVersion = Regex("gradle-([^/]+)-bin\\.zip").find(distributionUrl)?.groupValues?.get(1)
            ?: throw GradleException("Cannot parse the Gradle version from distributionUrl: $distributionUrl")
        val manifestGradle = manifest["gradle"] as String
        if (wrapperVersion != manifestGradle) {
            throw GradleException(
                "versions.json pins Gradle $manifestGradle but the wrapper (gradle/wrapper/gradle-wrapper.properties) is $wrapperVersion. " +
                    "Run 'gradle wrapper --gradle-version $manifestGradle' at the repository root and commit the result."
            )
        }

        // 2. The manifest must be generated from the nixpkgs rev flake.lock pins; a
        //    nixpkgs bump changes every nixpkgs-derived version at once.
        val lock = JsonSlurper().parse(rootProject.file("flake.lock")) as Map<*, *>
        val lockRev = (((lock["nodes"] as Map<*, *>)["nixpkgs"] as Map<*, *>)["locked"] as Map<*, *>)["rev"] as String
        val manifestRev = manifest["nixpkgsRev"] as String
        if (lockRev != manifestRev) {
            throw GradleException(
                "versions.json was generated from nixpkgs ${manifestRev.take(12)} but flake.lock pins ${lockRev.take(12)}. " +
                    "On a machine with Nix, run 'nix run .#update-versions' at the repository root and commit the result."
            )
        }

        logger.lifecycle("checkVersions: versions.json is in sync (Gradle $manifestGradle, nixpkgs ${lockRev.take(12)})")
    }
}