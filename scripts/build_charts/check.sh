#!/usr/bin/env bash
set -euo pipefail

VISUAL_URL="https://aeronav.faa.gov/visual/"
CHARTCACHE="/data/chartmaker/chartcache"

echo "Checking FAA for latest valid chart cycle..."

# Get all date-looking folder names and sort newest first.
# Format is MM-DD-YYYY; sort by year, then month, then day.
all_dates="$(
  curl -fsSL "$VISUAL_URL" \
    | grep -Eo '[0-9]{2}-[0-9]{2}-[0-9]{4}' \
    | sort -u -t- -k3,3 -k1,1 -k2,2
)"

if [[ -z "$all_dates" ]]; then
  echo "ERROR: Could not find any date folders on $VISUAL_URL"
  exit 1
fi

latest_remote_date=""

# Walk dates from newest to oldest until we find one that actually has Sectional.zip
while read -r d; do
  [[ -z "$d" ]] && continue

  files_url="${VISUAL_URL}${d}/All_Files/"
  echo "  Checking ${files_url} for Sectional.zip ..."
  if curl -fsSL "$files_url" 2>/dev/null | grep -q 'Sectional.zip'; then
    latest_remote_date="$d"
    echo "  Found Sectional.zip for cycle ${d}"
    break
  fi
done < <(echo "$all_dates" | tac)

if [[ -z "$latest_remote_date" ]]; then
  echo "ERROR: No valid cycle with Sectional.zip found under $VISUAL_URL"
  exit 1
fi

echo "Latest VALID FAA chart cycle: $latest_remote_date"

sectional_zip="${CHARTCACHE}/Sectional-${latest_remote_date}.zip"

if [[ -f "$sectional_zip" ]]; then
  echo "Already up to date:"
  echo "  $sectional_zip exists."
  echo "Nothing to do."
  exit 0
fi

echo "No local Sectional ZIP for ${latest_remote_date}."
echo "Will run chart generation in Docker."
