# Every store path named inside $1 must be $1 itself or one of the paths in $2..; the offending
# paths go to stdout and the exit status is 1 when there are any. Shared by the prebuilt Mesa's
# build (which fails on them) and its flake check (which asserts there are none).
#
# Binary files are scanned as text (`-a`) and NULs dropped: a match may sit inside a NUL run.
root=$1
shift
allowed=$(
  { echo "$root"; printf '%s\n' "$@"; } | cut -d/ -f4 | cut -c1-32 | sort -u
)
status=0
for path in $(grep -rhoaE '/nix/store/[a-z0-9]{32}-[^/]*' "$root" | tr -d '\000' | sort -u); do
  hash=$(echo "$path" | cut -d/ -f4 | cut -c1-32)
  printf '%s\n' "$allowed" | grep -qxF "$hash" || {
    echo "$path"
    status=1
  }
done
exit $status
