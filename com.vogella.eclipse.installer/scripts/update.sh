#!/bin/sh
# Swaps in the new installer once the old one has exited, then starts it.
# Usage: update.sh "<pids>" <installer folder> <new folder> [installer arguments...]
# The new folder lies in an update folder of its own, which also takes the old installer and is deleted at the end.
pids=$1
root=$2
next=$3
shift 3
staging=$(dirname "$next")
for pid in $pids; do
  while kill -0 "$pid" 2>/dev/null; do sleep 0.3; done
done
mv "$root" "$staging/old" && mv "$next" "$root" || { [ -d "$root" ] || mv "$staging/old" "$root"; exit 1; }
nohup "$root/eclipse" "$@" >/dev/null 2>&1 &
rm -rf "$staging" "$0"
