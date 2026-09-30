#!/usr/bin/env bash
# Downloads the Eclipse installer for Linux x86_64 and runs it, with its window or headless.
# The install-eclipse-local.sh launcher runs this script from the latest GitHub release.
#
# The installer extracts an Eclipse application archive into the target folder if that folder is missing or
# empty, and installs or updates the latest version of a set of features in it.
# This script only checks Java, fetches and caches the installer, and starts it: with --headless it runs in
# the terminal, otherwise it opens the installer window.
# Options that are not given fall back to the installer's defaults.
# Java 21 or newer must be on the PATH, in JAVA_HOME, or given with --java-home.
set -euo pipefail

usage() {
	cat <<'EOF'
Usage: install-eclipse-local.sh [options]
  --installer-url <url|path>   installer archive, by default the latest GitHub release
  --install-dir <dir>          installation folder
  --headless                   run without a window
  --application-url <url|path> application archive, used if the installation folder is missing or empty
  --name <name>                application name shown by the installer
  --repositories <a,b>         update sites; local folders and zipped sites are allowed
  --features <a,b>             feature IUs, for example org.eclipse.egit.feature.group
  --cache-dir <dir>            download cache
  --java-home <dir>            Java 21 or newer
  --clean                      delete the installation first; downloaded archives are kept
EOF
}

fail() {
	echo "ERROR: $*" >&2
	exit 1
}

release_url="https://github.com/vogellacompany/eclipse-installer/releases/latest/download"
installer_url=""
install_dir=""
headless=false
application_url=""
name=""
repositories=""
features=""
cache_dir=""
java_home="${JAVA_HOME:-}"
clean=false

# An empty variable in a calling script must not silently select a default, which --clean would delete.
value() {
	[ -n "$2" ] && [ "${2#--}" = "$2" ] || fail "Missing or empty value for $1"
	printf '%s' "$2"
}

while [ $# -gt 0 ]; do
	case "$1" in
	--installer-url) installer_url="$(value "$1" "${2-}")"; shift 2 ;;
	--install-dir) install_dir="$(value "$1" "${2-}")"; shift 2 ;;
	--headless) headless=true; shift ;;
	--application-url) application_url="$(value "$1" "${2-}")"; shift 2 ;;
	--name) name="$(value "$1" "${2-}")"; shift 2 ;;
	--repositories) repositories="$(value "$1" "${2-}")"; shift 2 ;;
	--features) features="$(value "$1" "${2-}")"; shift 2 ;;
	--cache-dir) cache_dir="$(value "$1" "${2-}")"; shift 2 ;;
	--java-home) java_home="$(value "$1" "${2-}")"; shift 2 ;;
	--clean) clean=true; shift ;;
	-h | --help) usage; exit 0 ;;
	*) usage >&2; fail "Unknown option $1" ;;
	esac
done

case "$(uname -s)/$(uname -m)" in
Linux/x86_64)
	asset="eclipse-installer-linux.gtk.x86_64.tar.gz"
	launcher="eclipse"
	default_cache="${XDG_CACHE_HOME:-$HOME/.cache}/eclipse-installer"
	;;
*) fail "No installer build for $(uname -s) $(uname -m)" ;;
esac
installer_url="${installer_url:-$release_url/$asset}"
installer_cache="${cache_dir:-$default_cache}"

# The installer cannot report an old Java itself: its launcher jar needs Java 21 to load at all.
if [ -n "$java_home" ]; then
	java="${java_home%/}/bin/java"
	[ -x "$java" ] || fail "No Java in $java_home"
else
	java="$(command -v java || true)"
	[ -n "$java" ] || fail "No Java on the PATH, install Java 21 or newer or pass --java-home"
fi
version="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
major="${version#1.}"
major="${major%%.*}"
[ -n "$major" ] && [ "$major" -ge 21 ] 2>/dev/null ||
	fail "Java ${version:-of unknown version} ($java) is too old, the installer needs Java 21 or newer; pass --java-home"
echo "Using Java $version ($java)"

mkdir -p "$installer_cache"
# Archive and extracted installer belong to one installer URL, so changing the URL never reuses another installer.
key="$(printf '%s' "$installer_url" | sha256sum | cut -c1-16)"
installer_dir="$installer_cache/installer-$key"
if [ -f "$installer_url" ]; then
	archive="$(cd "$(dirname "$installer_url")" && pwd)/$(basename "$installer_url")"
else
	archive="$installer_cache/installer-$key.tar.gz"
	# Downloads only if the release is newer than the cached archive.
	condition=()
	[ -f "$archive" ] && condition=(--time-cond "$archive")
	status="$(curl --fail --location --silent --show-error --remote-time ${condition[@]+"${condition[@]}"} \
		--output "$archive.part" --write-out '%{http_code}' "$installer_url")" ||
		fail "Download of $installer_url failed"
	if [ "$status" = 200 ] && [ -s "$archive.part" ]; then
		echo "Downloaded $installer_url"
		mv -f "$archive.part" "$archive"
	else
		rm -f "$archive.part"
	fi
fi

# Records which archive the folder came from, so a failed extraction is retried and a newer archive replaces it.
marker="$installer_dir/.extracted-from"
stamp="$(stat -c '%n %Y %s' "$archive")"
if $clean || [ ! -x "$installer_dir/$launcher" ] || [ "$(cat "$marker" 2>/dev/null)" != "$stamp" ]; then
	echo "Extracting $archive"
	rm -rf "$installer_dir.part"
	mkdir -p "$installer_dir.part"
	tar -xzf "$archive" -C "$installer_dir.part"
	root="$installer_dir.part"
	entries=("$root"/*)
	# Drops the single top-level folder.
	if [ ${#entries[@]} -eq 1 ] && [ -d "${entries[0]}" ]; then
		root="${entries[0]}"
	fi
	rm -rf "$installer_dir"
	mv "$root" "$installer_dir"
	rm -rf "$installer_dir.part"
	printf '%s' "$stamp" > "$marker"
fi

# The installer takes URIs only: a local path becomes a file: URI, and a zipped p2 update site a jar:file:...!/ URI.
# Percent-encodes every byte outside the unreserved URI characters and "/", so "#", "%" or "!" stay part of the path.
encode_path() {
	local LC_ALL=C value="$1" encoded="" char i
	for ((i = 0; i < ${#value}; i++)); do
		char="${value:i:1}"
		case "$char" in
		[a-zA-Z0-9._~/-]) encoded+="$char" ;;
		*) encoded+="$(printf '%%%02X' "'$char")" ;;
		esac
	done
	printf '%s' "$encoded"
}

to_location() {
	local location="$1" repository="${2:-}" path
	if [ ! -e "$location" ]; then
		# Only a name that is not an existing file and starts with a URI scheme is taken as a URL.
		if [[ "$location" =~ ^[A-Za-z][A-Za-z0-9+.-]*: ]]; then
			echo "$location"
			return
		fi
		fail "Not found: $location"
	fi
	path="$(encode_path "$(cd "$(dirname "$location")" && pwd)/$(basename "$location")")"
	if [ -n "$repository" ] && [ -f "$location" ]; then
		case "$location" in
		*.zip | *.jar) echo "jar:file://$path!/" ;;
		*) fail "$location is neither a folder nor a zipped p2 update site" ;;
		esac
	else
		echo "file://$path"
	fi
}

# The script has just fetched the latest release, so the installer need not look for one.
args=(--no-update-check)
[ -n "$install_dir" ] && args+=(--target "$(mkdir -p "$(dirname "$install_dir")" && cd "$(dirname "$install_dir")" && pwd)/$(basename "$install_dir")")
$headless && args+=(--headless)
$clean && args+=(--clean)
[ -n "$application_url" ] && args+=(--application-url "$(to_location "$application_url")")
[ -n "$name" ] && args+=(--name "$name")
if [ -n "$repositories" ]; then
	sites=()
	IFS=',' read -ra entries <<<"$repositories"
	for entry in "${entries[@]}"; do
		entry="$(echo "$entry" | sed 's/^ *//; s/ *$//')"
		[ -n "$entry" ] && sites+=("$(to_location "$entry" repository)")
	done
	args+=(--repositories "$(IFS=,; echo "${sites[*]}")")
fi
[ -n "$features" ] && args+=(--features "$features")
[ -n "$cache_dir" ] && args+=(--cache-dir "$cache_dir")

exec "$installer_dir/$launcher" -vm "$java" "${args[@]}"
