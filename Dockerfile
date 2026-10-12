# Multi-stage Docker image (roadmap item 19). The app is built inside Nix from the
# flake's own inputs — flake.nix and devenv.nix are the only dependency sources
# (AGENTS.md), so there is no apt/apk anywhere — and the runtime image is exactly the
# closure of item 16's stt-app package plus the image-only Mesa Vulkan drivers
# (flake output docker-vulkan-drivers) on scratch, plus a handful of OS files
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
# `docker-vulkan-drivers` is the image-only Mesa Vulkan driver list (Intel/AMD GPUs; the
# image has no host userspace driver). The out-links give stable handles for readlink -f;
# the union of both closures feeds the rootfs assembly below.
RUN set -euxo pipefail; \
    mkdir -p /out; \
    nix build --option sandbox false .#stt-app --out-link /out/app; \
    nix build --option sandbox false .#docker-vulkan-drivers --out-link /out/vulkan-drivers; \
    nix-store -qR "$(readlink -f /out/app)" "$(readlink -f /out/vulkan-drivers)" | sort -u > /out/closure.txt

# Assemble the runtime rootfs from the closure and the OS scaffolding. bash's store
# path is read from the wrapper script's own shebang (makeWrapper emits an absolute
# path, so the interpreter is guaranteed to be in the closure); curl - only in the
# closure because the package PATH prefix references it (flake.nix) - is found by
# scanning the closure list, NOT the whole store, which also holds the builder
# image's own packages. `/rootfs` must exist before `cp --parents` (it needs the
# destination directory).
RUN set -eux; \
    mkdir -p /rootfs; \
    appBin="$(readlink -f /out/app)/bin/stt-app"; \
    IFS= read -r shebang < "$appBin"; \
    interp=${shebang#\#!}; \
    interp=${interp# }; \
    interp=${interp%% *}; \
    [ -x "$interp" ] || { echo "no interpreter in $appBin: $shebang" >&2; exit 1; }; \
    curlBin=""; \
    while read -r p; do case "${p##*/}" in *-curl-*) [ -e "$p/bin/curl" ] && { curlBin="$p/bin/curl"; break; } ;; esac; done < /out/closure.txt; \
    [ -n "$curlBin" ] || { echo "no curl in the closure" >&2; exit 1; }; \
    while read -r p; do cp -a --parents "$p" /rootfs; done < /out/closure.txt; \
    mkdir -p /rootfs/bin /rootfs/etc /rootfs/usr/local/bin /rootfs/tmp /rootfs/data /rootfs/home/stt; \
    ln -s "$interp" /rootfs/bin/sh; \
    ln -s "$curlBin" /rootfs/usr/local/bin/curl; \
    printf 'root:x:0:0:root:/root:%s\nstt:x:1000:1000:stt:/home/stt:%s\n' /bin/sh /bin/sh > /rootfs/etc/passwd; \
    printf 'root:x:0:\nstt:x:1000:\n' > /rootfs/etc/group; \
    printf 'passwd: files\nshadow: files\ngroup: files\nhosts: files dns\nnetworks: files\nservices: files\n' > /rootfs/etc/nsswitch.conf; \
    chmod 1777 /rootfs/tmp; \
    chown -R 1000:1000 /rootfs/data /rootfs/home/stt; \
    vkDriverFiles="$(cat /out/vulkan-drivers)"; \
    printf '#!%s\nexport VK_ADD_DRIVER_FILES=%s\nexec %s "$@"\n' "$interp" "$vkDriverFiles" "$appBin" > /rootfs/entrypoint.sh; \
    chmod 755 /rootfs/entrypoint.sh

FROM scratch

COPY --from=builder /rootfs/ /

# /usr/local/bin/curl is a symlink into the closure, for the compose healthcheck;
# /bin/sh is a symlink to the closure's bash (a login shell for `docker compose exec`).
ENV PATH=/usr/local/bin:/bin \
    HOME=/home/stt

EXPOSE 8080

# The stt user from /etc/passwd above; /data (volume) and /home/stt are its own, the
# store is read-only, so the app can write only to /data (STT_DATA_DIR) and scratch.
USER 1000:1000

# docker-init (compose init: true) is PID 1 and execs this generated shim, which
# execs the wrapper, which execs the JVM — one process chain, signals pass through.
ENTRYPOINT ["/entrypoint.sh"]