# Every store path named inside $1 must be $1 itself or one of the paths in $2..; the offending
# paths go to stdout and the exit status is 1 when there are any. Shared by the prebuilt Mesa's and
# ffmpeg's builds (which fail on them) and their flake checks (which assert there are none).
#
# Binary files are scanned as text (`-a`) and NULs dropped: a match may sit inside a NUL run. The
# name charset (store names cannot contain spaces) keeps a match from running through a text line's
# spaces to the next slash — ffmpeg's `configuration:` line names store paths between shell flags.
# /nix/store/eeee…-<name> is Nix's placeholder for a reference removed by `remove-references-to`,
# not a store path (the all-e hash is reserved), so it is skipped: the cross ffmpeg build strips
# its host compiler from config.h and the configuration line keeps that marker.
root=$1
shift
allowed=$(
  { echo "$root"; printf '%s\n' "$@"; } | cut -d/ -f4 | cut -c1-32 | sort -u
)
status=0
for path in $(grep -rhoaE '/nix/store/[a-z0-9]{32}-[a-zA-Z0-9+._?=-]*' "$root" | tr -d '\000' | sort -u); do
  case "$path" in
    /nix/store/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee-*) continue ;;
  esac
  hash=$(echo "$path" | cut -d/ -f4 | cut -c1-32)
  printf '%s\n' "$allowed" | grep -qxF "$hash" || {
    echo "$path"
    status=1
  }
done
exit $status
