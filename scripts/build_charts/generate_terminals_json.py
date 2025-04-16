#!/usr/bin/env python3

import os
import json

# 🔹 Base URL where the ZIPs are hosted
BASE_URL = "https://regiruk.netlify.app/zips2/"

# 🔹 Directory containing the ZIP files
ZIP_DIRECTORY = "../metarmap/zips2"

# 🔹 Output JSON file
OUTPUT_JSON = "../metarmap/zips2/terminals.json"

# 🔹 Input text file containing the allowed ZIP filenames
ZIP_LIST_FILE = "terminals.txt"

# 🔹 Minimum file size (64 KB = 64 * 1024 bytes)
MIN_SIZE_BYTES = 64 * 1024

def get_file_size_mb(filepath):
    """Return file size in MB (rounded)."""
    size_bytes = os.path.getsize(filepath)
    return round(size_bytes / (1024 * 1024), 2)  # Convert to MB

def generate_sectionals_json():
    sectionals = []

    # Read allowed ZIP filenames from sectionals.txt
    if not os.path.exists(ZIP_LIST_FILE):
        print(f"❌ Error: ZIP list file '{ZIP_LIST_FILE}' not found.")
        return

    with open(ZIP_LIST_FILE, "r") as f:
        allowed_files = sorted({line.strip() for line in f if line.strip()})  # Read and sort

    # Process only ZIP files listed in sectionals.txt
    for filename in allowed_files:
        filepath = os.path.join(ZIP_DIRECTORY, filename)

        if os.path.exists(filepath) and filename.lower().endswith(".zip"):
            file_size = os.path.getsize(filepath)
            if file_size < MIN_SIZE_BYTES:
                print(f"🗑️ Deleting {filename} (too small: {file_size} bytes)")
                os.remove(filepath)
                continue  # Skip small files

            sectionals.append({
                "name": os.path.splitext(filename)[0],  # Remove .zip extension
                "fileName": filename,
                "size": f"{get_file_size_mb(filepath)} MB",
                "url": f"{BASE_URL}{filename}"
            })
        else:
            print(f"⚠️ Skipping missing or invalid file: {filename}")

    # Save JSON (sorted alphabetically)
    with open(OUTPUT_JSON, "w") as json_file:
        json.dump(sectionals, json_file, indent=4)

    print(f"✅ Generated {OUTPUT_JSON} with {len(sectionals)} entries.")

if __name__ == "__main__":
    generate_sectionals_json()

