#!/bin/sh
set -eu

data_dir=${1:-/var/opentripplanner}
script_dir=$(dirname "$(readlink -f "$0")")
capture=org.opentripplanner.ext.traffic511.feed.Traffic511Capture
snapshot="$data_dir/511-snapshot"

mkdir -p "$data_dir"
for config in build-config.json router-config.json otp-config.json; do
    if [ ! -e "$data_dir/$config" ]; then
        cp "$script_dir/config/$config" "$data_dir/$config"
    fi
done
if [ ! -f "$snapshot/captured-at.txt" ]; then
    java -cp /app/app.jar "$capture" --regional-transit "$snapshot"
fi
exec java -cp /app/app.jar "$capture" --refresh-transit "$data_dir/511-live"
