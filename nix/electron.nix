# The Electron release the desktop app ships (roadmap item 50, ADR 0053): one pinned version in
# every build. desktop/package.json pins it for the .deb (electron-builder downloads it); this file
# pins the same upstream release for the flake package. nix/desktop.nix refuses to evaluate when
# the two versions differ, and a sha256 of another release fails the download.
# Hash: SHASUMS256.txt of the GitHub release, electron-v<version>-linux-x64.zip.
{
  version = "44.7.1";
  x86_64-linux = "fd4349ce666251f66acba2c88064ae6d65d5c61c0a44e68dd028d7c7f30eaa47";
}
