# The Electron release the desktop app ships (roadmap item 50, ADR 0053): one pinned version in
# every build. desktop/package.json pins it for the .deb (electron-builder downloads it); this file
# pins the same upstream release for the flake package. nix/desktop.nix refuses to evaluate when
# the two versions differ, and a sha256 of another release fails the download.
# Hash: SHASUMS256.txt of the GitHub release, electron-v<version>-linux-x64.zip.
{
  version = "44.9.1";
  x86_64-linux = "994bc3664cc3ac55b50c21a58d345cf8322003d37f5439a81801e3267e6145e2";
}
