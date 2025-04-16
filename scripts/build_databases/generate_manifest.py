import hashlib
import json
import os
from datetime import date

# Config — file paths and corresponding URLs
files = {
    "faa_navigation": {
        "filename": "faa_navigation.db",
        "url": "https://regiruk.netlify.app/sqlite/faa_navigation.db"
    },
    "faa_airports": {
        "filename": "faa_airports.db",
        "url": "https://regiruk.netlify.app/sqlite/faa_airports.db"
    },
    "faa_fixes": {
        "filename": "faa_fixes.db",
        "url": "https://regiruk.netlify.app/sqlite/faa_fixes.db"
    },
    "faa_frequencies": {
        "filename": "faa_frequencies.db",
        "url": "https://regiruk.netlify.app/sqlite/faa_frequencies.db"
    }
}

def sha256_of_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def generate_manifest(files, version=None):
    if version is None:
        version = str(date.today())  # Default to today's date

    manifest = {}
    for key, info in files.items():
        filepath = info["filename"]
        if not os.path.exists(filepath):
            print(f"❌ File not found: {filepath}")
            continue

        manifest[key] = {
            "version": version,
            "url": info["url"],
            "sha256": sha256_of_file(filepath)
        }

    with open("db_manifest.json", "w") as f:
        json.dump(manifest, f, indent=2)

    print("✅ db_manifest.json generated")

if __name__ == "__main__":
    generate_manifest(files)

