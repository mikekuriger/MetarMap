#!/usr/bin/env python3
import os
import zipfile
import json
import multiprocessing
from concurrent.futures import ProcessPoolExecutor

# Configuration - update these paths
SOURCE_DIRS = {
    #"Sectional": "/data/metarmap/Sectional/60",
    #"Terminal": "/data/metarmap/Terminal"
    "Sectional": "/data/chartmaker/workarea/Sectional/6_quantized",
    "Terminal": "/data/chartmaker/workarea/Terminal/6_quantized",
    "Grand_Canyon": "/data/chartmaker/workarea/Grand_Canyon/6_quantized"
}
ZIP_DIRS = {
    "Sectional": "/data/metarmap/zips/Sectional",
    "Terminal": "/data/metarmap/zips/Terminal",
    "Grand_Canyon": "/data/metarmap/zips/Terminal"
}
ZOOM_LEVEL = {
    "Sectional": ["5", "6", "7", "8", "9", "10", "11"],
    "Terminal": ["10", "11", "12", "13"],
    "Grand_Canyon": ["8", "9", "10", "11", "12", "13"]
}
JSON_FILE = "/data/metarmap/sectionalFiles.json"
LOG_MISSING_FILES = False  # Set to True to log missing files instead of printing

def process_chart(base_name, tile_names, chart_type):
    """
    Creates a zip file containing only tiles from allowed zoom-level folders.
    """
    allowed_zooms = set(ZOOM_LEVEL[chart_type])
    zip_path = os.path.join(ZIP_DIRS[chart_type], f"{base_name}.zip")
    source_dir = SOURCE_DIRS[chart_type]
    missing_files = []

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for tile in tile_names:
            parts = tile.split('/')
            if not parts or parts[0] not in allowed_zooms:
                continue  # Skip tile not in the specified zoom levels

            tile_path = os.path.join(source_dir, tile)
            if os.path.exists(tile_path):
                arcname = os.path.relpath(tile_path, source_dir)
                zf.write(tile_path, arcname=arcname)
            else:
                if LOG_MISSING_FILES:
                    missing_files.append(tile_path)

    print(f"Created {zip_path}")

    if LOG_MISSING_FILES and missing_files:
        log_file = f"/data/zips/missing_tiles_{chart_type}.log"
        with open(log_file, "a") as log:
            log.write("\n".join(missing_files) + "\n")


#def process_chart(base_name, tile_names, chart_type):
#    """
#    Creates a zip file containing the specified tiles.
#    """
#    zip_path = os.path.join(ZIP_DIRS[chart_type], f"{base_name}.zip")
#    source_dir = SOURCE_DIRS[chart_type]
#    missing_files = []
#
#    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
#        for tile in tile_names:
#            tile_path = os.path.join(source_dir, tile)
#            if os.path.exists(tile_path):
#                arcname = os.path.relpath(tile_path, source_dir)
#                zf.write(tile_path, arcname=arcname)
#            else:
#                if LOG_MISSING_FILES:
#                    missing_files.append(tile_path)
#
#    print(f"Created {zip_path}")
#
#    # Log missing files if enabled
#    if LOG_MISSING_FILES and missing_files:
#        log_file = f"/data/zips/missing_tiles_{chart_type}.log"
#        with open(log_file, "a") as log:
#            log.write("\n".join(missing_files) + "\n")

def process_json(json_data, chart_type):
    """
    Uses multiprocessing to process charts in parallel.
    """
    num_workers = min(multiprocessing.cpu_count(), len(json_data))
    print(f"Processing {chart_type} charts using {num_workers} workers...")

    with ProcessPoolExecutor(max_workers=num_workers) as executor:
        futures = [executor.submit(process_chart, base_name, tile_names, chart_type)
                   for base_name, tile_names in json_data.items()]

        for future in futures:
            try:
                future.result()
            except Exception as e:
                print(f"Error processing {chart_type} chart: {e}")

def main():
    # Ensure output directories exist
    for dir_path in ZIP_DIRS.values():
        os.makedirs(dir_path, exist_ok=True)

    # Load JSON data and process it
    if os.path.exists(JSON_FILE):
        print(f"Processing JSON data from {JSON_FILE}...")
        with open(JSON_FILE, "r") as f:
            json_data = json.load(f)

        # Process for both Sectionals and Terminals using multiprocessing
        process_json(json_data, "Sectional")
        process_json(json_data, "Terminal")
    else:
        print(f"Error: JSON file {JSON_FILE} not found.")

if __name__ == "__main__":
    main()

