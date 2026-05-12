#!/usr/bin/env python3

import os
import json

MIN_SIZE_BYTES = 64 * 1024  # Minimum file size to include (64 KB)

# Chart configurations
CHARTS = {
    "Terminal": {
        "base_url": "https://regiruk.netlify.app/zips/Terminal/",
        "zip_dir": "/data/metarmap/zips/Terminal",
        "zip_list_file": "terminals.txt"
    },
    "Sectional": {
        "base_url": "https://regiruk.netlify.app/zips/Sectional/",
        "zip_dir": "/data/metarmap/zips/Sectional",
        "zip_list_file": "sectionals.txt"
    },
    "Enroute_Low": {
        "base_url": "https://regiruk.netlify.app/zips/Enroute_Low/",
        "zip_dir": "/data/metarmap/zips/Enroute_Low",
        "zip_list_file": "sectionals.txt"
    }
}

def get_file_size_mb(filepath):
    size_bytes = os.path.getsize(filepath)
    return round(size_bytes / (1024 * 1024), 2)

def process_chart(chart_name, config):
    print(f"🔍 Processing {chart_name}...")

    zip_dir = config["zip_dir"]
    zip_list_file = config["zip_list_file"]
    base_url = config["base_url"]
    entries = []

    # Get the series (valid date) from metadata.json
    try:
        metadata_path = f"/data/chartmaker/workarea/{chart_name}/6_quantized/metadata.json"
        with open(metadata_path, "r") as meta_file:
            metadata = json.load(meta_file)
            series_valid = metadata.get("valid", "unknown")
    except Exception as e:
        print(f"⚠️ Could not read metadata for {chart_name}: {e}")
        series_valid = "unknown"

    # Read list of allowed zip files
    if not os.path.exists(zip_list_file):
        print(f"❌ Skipping {chart_name}: ZIP list file '{zip_list_file}' not found.")
        return None

    with open(zip_list_file, "r") as f:
        allowed_files = sorted({line.strip() for line in f if line.strip()})

    for filename in allowed_files:
        filepath = os.path.join(zip_dir, filename)
        if os.path.exists(filepath) and filename.lower().endswith(".zip"):
            file_size = os.path.getsize(filepath)
            if file_size < MIN_SIZE_BYTES:
                print(f"🗑️ Deleting {filename} (too small: {file_size} bytes)")
                os.remove(filepath)
                continue

            entries.append({
                "name": os.path.splitext(filename)[0],
                "fileName": filename,
                "size": f"{get_file_size_mb(filepath)} MB",
                "url": f"{base_url}{filename}"
            })
        else:
            print(f"⚠️ Skipping missing or invalid file: {filename}")

    print(f"✅ Processed {chart_name}: {len(entries)} entries")

    return {
        "series": series_valid,
        "charts": entries
    }


def main():
    all_charts = {}

    for chart_name, config in CHARTS.items():
        result = process_chart(chart_name, config)
        if result:
            all_charts[chart_name] = result

    output_path = "/data/metarmap/zips/all_charts.json"
    with open(output_path, "w") as f:
        json.dump(all_charts, f, indent=4)

    print(f"📦 Combined output written to {output_path}")

if __name__ == "__main__":
    main()

