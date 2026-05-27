#!/usr/bin/env bash
set -euo pipefail

APPDIR="/data/chartmaker"
CHARTCACHE="${APPDIR}/chartcache"

# === 1) Pick the best chart date using the same rules as getBestChartDate() ===
BEST_DATE="$(
python3 - << 'PY'
import json, os, sys
from datetime import datetime, date

appdir = "/data/chartmaker"  # adjust if needed
json_path = os.path.join(appdir, "chartdates.json")

try:
    with open(json_path, "r") as f:
        data = json.load(f)
except FileNotFoundError:
    print(f"ERROR: {json_path} not found", file=sys.stderr)
    sys.exit(1)

dates = []
for s in data.get("ChartDates", []):
    # JS uses new Date(cdate); assuming MM-DD-YYYY strings
    try:
        dt = datetime.strptime(s, "%m-%d-%Y").date()
        dates.append(dt)
    except ValueError:
        # skip any weird entries
        continue

if not dates:
    print("ERROR: No valid dates in ChartDates", file=sys.stderr)
    sys.exit(1)

# Sort newest → oldest
dates.sort(reverse=True)

now = date.today()
selected = None
expiredate = None

for dt in dates:
    # JS: tdiff = now - date; tdays = tdiff / (1000 * 3600 * 24); diffdays = round(tdays)
    # Here: use integer days difference (close enough for this purpose)
    diffdays = (now - dt).days

    if selected is None:
        # JS condition: if (diffdays >= -20 && diffdays <= 36)
        if -20 <= diffdays <= 36:
            selected = dt
    elif expiredate is None:
        # Next earlier date becomes expiredate in the JS code
        expiredate = dt

if selected is None:
    print("ERROR: No suitable chart date was found!", file=sys.stderr)
    sys.exit(1)

# Output selectedDate as MM-DD-YYYY for the shell script
print(selected.strftime("%m-%d-%Y"))
PY
)"

if [[ -z "$BEST_DATE" ]]; then
  echo "ERROR: getBestChartDate logic returned an empty date"
  exit 1
fi

echo "Selected chart cycle (from chartdates.json): $BEST_DATE"

# === 2) Check local cache for that cycle ===

SECTIONAL_ZIP="${CHARTCACHE}/Sectional-${BEST_DATE}.zip"

if [[ -f "$SECTIONAL_ZIP" ]]; then
  echo "Already have Sectional ZIP for ${BEST_DATE}:"
  echo "  $SECTIONAL_ZIP"
  echo "Nothing to do."
  exit 0
fi

echo "No local Sectional ZIP for ${BEST_DATE}, starting Docker chart build..."

# === 3) Run your existing Docker command ===

