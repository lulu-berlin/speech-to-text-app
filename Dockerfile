# Multi-stage Docker image (roadmap item 19). The app is built inside Nix from the
# flake's own inputs — flake.nix and devenv.nix are the only dependency sources
# (AGENTS.md), so there is no apt/apk anywhere — and the runtime image is exactly the
# closure of item 16's stt-app package on scratch, plus a handful of OS files
# (users, /tmp, /data) and a generated entrypoint shim.
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

# `stt-app` is an alias of the default package (flake.nix), so the build is explicit;
# the out-link gives a stable handle for readlink -f. The closure list feeds the
# rootfs assembly below.
RUN set -eux; \
    mkdir -p /out; \
    nix build --option sandbox false .#stt-app --out-link /out/app; \
    nix-store -qR "$(readlink -f /out/app)" > /out/closure.txt

# Assemble the runtime rootfs from the closure and the OS scaffolding. bash and curl
# live inside the closure (the wrapper script's shebang and the PATH prefix reference
# them; curl was added to the package for the healthcheck — flake.nix), so they are
# found by glob, not by a hard-coded store hash.
RUN set -eux; \
    appBin="$(readlink -f /out/app)/bin/stt-app"; \
    shopt -s nullglob; \
    bashBin=""; for p in /nix/store/*-bash-*/bin/bash; do [ -e "$p" ] && { bashBin="$p"; break; }; done; \
    curlBin=""; for p in /nix/store/*-curl-*/bin/curl; do [ -e "$p" ] && { curlBin="$p"; break; }; done; \
    [ -n "$bashBin" ] && [ -n "$curlBin" ]; \
    while read -r p; do cp -a --parents "$p" /rootfs; done < /out/closure.txt; \
    mkdir -p /rootfs/bin /rootfs/etc /rootfs/usr/local/bin /rootfs/tmp /rootfs/data /rootfs/home/stt; \
    ln -s "$bashBin" /rootfs/bin/sh; \
    ln -s "$curlBin" /rootfs/usr/local/bin/curl; \
    printf 'root:x:0:0:root:/root:%s\nstt:x:1000:1000:stt:/home/stt:%s\n' /bin/sh /bin/sh > /rootfs/etc/passwd; \
    printf 'root:x:0:\nstt:x:1000:\n' > /rootfs/etc/group; \
    printf 'passwd: files\nshadow: files\ngroup: files\nhosts: files dns\nnetworks: files\nservices: files\n' > /rootfs/etc/nsswitch.conf; \
    chmod 1777 /rootfs/tmp; \
    chown -R 1000:1000 /rootfs/data /rootfs/home/stt; \
    printf '#!%s\nexec %s "$@"\n' "$bashBin" "$appBin" > /rootfs/entrypoint.sh; \
    chmod 755 /rootfs/entrypoint.sh

FROM scratch

COPY --from=builder /rootfs/ /

# /usr/local/bin/curl is a symlink into the closure, for the compose healthcheck;
# /bin/sh is a symlink to the closure's bash (a login shell for `docker compose exec`).
ENV PATH=/usr/local/bin:/bin \
    HOME=/home/stt

EXPOSE 8080

# docker-init (compose init: true) is PID 1 and execs this generated shim, which
# execs the wrapper, which execs the JVM — one process chain, signals pass through.
ENTRYPOINT ["/entrypoint.sh"]