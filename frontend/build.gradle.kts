import com.github.gradle.node.pnpm.task.PnpmInstallTask
import com.github.gradle.node.pnpm.task.PnpmTask
import groovy.json.JsonSlurper

// Roadmap item 33: builds the Vite frontend through the node plugin, which
// downloads the node/pnpm versions versions.json pins (the same nixpkgs rev the
// flake builds with) and runs `pnpm install --frozen-lockfile` + `pnpm build` like
// the Nix build does.
plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

val manifest = JsonSlurper().parse(rootProject.file("versions.json")) as Map<*, *>

node {
    download.set(true)
    version.set(manifest["node"] as String)
    pnpmVersion.set(manifest["pnpm"] as String)
    npmInstallCommand.set("pnpm")
}

tasks.named<PnpmInstallTask>("pnpmInstall") {
    args.add("--frozen-lockfile")
}

// The task the root `run` task depends on for the app's static files. Explicit
// pnpm exec (`pnpm run build` = `tsc --noEmit && vite build`) instead of relying on
// the plugin's lazily-created script-task names.
tasks.register<PnpmTask>("frontendBuild") {
    group = "build"
    description = "Install the frontend dependencies (frozen lockfile) and build the Vite bundle"
    args.set(listOf("run", "build"))
    dependsOn("pnpmInstall")
}