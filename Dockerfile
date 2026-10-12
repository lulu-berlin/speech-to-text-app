# Multi-stage Docker image (roadmap item 19). The app is built inside Nix from the
# flake's own inputs — flake.nix and devenv.nix are the only dependency sources
# (AGENTS.md), so there is no apt/apk anywhere — and the runtime image is exactly the
# closure of the flake output docker-root on scratch: item 16's stt-app package, the
# GPU userspace the host cannot lend a container, and the image's root files (the
# entrypoint, /bin/sh, curl for the healthcheck, the users) — plus /data, /home/stt and
# /tmp.
#
# The builder copies each closure store path 1:1 to its absolute /nix/store location,
# so every embedded reference (the dynamic linker, RPATHs, the makeWrapper script,
# the model paths) stays valid after the copy. `nix build` runs with the sandbox off:
# Docker's default seccomp profile can block nix's namespace sandbox, and the gradle
# build fetches (pinned via flake.lock and backend/deps.json) inside the derivation.
# The build needs network: nixpkgs at the locked rev, the gradle deps and both models
# (~600 MB) are downloaded on the first build.
#
# PID 1 is docker-init (docker-compose.yml sets init: true), a static tini, so
# orphaned process trees are reaped (ADR 0020). The app runs as the non-root stt user
# (uid/gid 1000) with STT_DATA_DIR=/data (compose) on a named volume; /data is the
# only writable location. x86_64-linux only (the flake's system).

FROM nixos/nix:2.35.2 AS builder

# Make flake support explicit (the image enables it; this keeps it robust).
RUN echo 'experimental-features = nix-command flakes' >> /etc/nix/nix.conf

WORKDIR /build
COPY . .

# The out-link gives a stable handle for readlink -f; the closure list feeds the rootfs
# assembly below (and lets a check compare it with the finished image's store).
RUN set -euxo pipefail; \
    mkdir -p /out; \
    nix build --option sandbox false .#docker-root --out-link /out/root; \
    nix-store -qR "$(readlink -f /out/root)" > /out/closure.txt

# The closure goes to its own absolute paths, docker-root's contents become the root.
# `cp --parents` needs /rootfs to exist; --no-preserve=mode keeps the store's read-only
# directory modes off the image's /, /bin, /etc and /usr. /data and /home/stt belong to
# the app user, /tmp is the shared scratch dir.
RUN set -eux; \
    mkdir -p /rootfs; \
    while read -r p; do cp -a --parents "$p" /rootfs; done < /out/closure.txt; \
    cp -RP --no-preserve=mode,ownership "$(readlink -f /out/root)"/. /rootfs/; \
    mkdir -p /rootfs/tmp /rootfs/data /rootfs/home/stt; \
    chmod 1777 /rootfs/tmp; \
    chown -R 1000:1000 /rootfs/data /rootfs/home/stt

FROM scratch

COPY --from=builder /rootfs/ /

# /usr/local/bin/curl (the compose healthcheck) and /bin/sh (a shell for
# `docker compose exec`) are symlinks into the closure.
ENV PATH=/usr/local/bin:/bin \
    HOME=/home/stt

EXPOSE 8080

# The stt user from docker-root's /etc/passwd; /data (volume) and /home/stt are its own,
# the store is read-only, so the app can write only to /data (STT_DATA_DIR) and scratch.
USER 1000:1000

# docker-init (compose init: true) is PID 1 and execs the entrypoint (docker-root), which
# exports the GPU environment and execs the wrapper, which execs the JVM — one process
# chain, signals pass through.
ENTRYPOINT ["/entrypoint.sh"]
