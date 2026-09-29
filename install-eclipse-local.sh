#!/usr/bin/env bash
# Runs the latest Eclipse installer script from GitHub with all arguments passed through.
# Keep this small launcher; the installer script it runs is updated through the GitHub release.
# The last downloaded script is cached and used when GitHub cannot be reached.
#   --script-url <url|path>  runs another version, for example from a branch
#   --no-update              uses the cached script without looking for a newer one
set -euo pipefail

script_url="https://github.com/vogellacompany/eclipse-installer/releases/latest/download/install-eclipse-server.sh"
update=true
args=()
while [ $# -gt 0 ]; do
	case "$1" in
	--script-url) script_url="$2"; shift 2 ;;
	--no-update) update=false; shift ;;
	*) args+=("$1"); shift ;;
	esac
done

cache="${XDG_CACHE_HOME:-$HOME/.cache}/eclipse-installer"
cached="$cache/install-eclipse-server.sh"
mkdir -p "$cache"
if $update; then
	if { [ -f "$script_url" ] && cp "$script_url" "$cached.part"; } || curl -fsSL -o "$cached.part" "$script_url"; then
		mv -f "$cached.part" "$cached"
	else
		rm -f "$cached.part"
		[ -f "$cached" ] || { echo "ERROR: Cannot download $script_url and no cached copy exists" >&2; exit 1; }
		echo "WARNING: Cannot download $script_url, using the cached copy" >&2
	fi
fi
[ -f "$cached" ] || { echo "ERROR: No cached installer script in $cache, run without --no-update first" >&2; exit 1; }

# ${args[@]+...} because bash before 4.4 treats an empty array as unset under set -u.
exec bash "$cached" ${args[@]+"${args[@]}"}
