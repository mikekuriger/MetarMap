#!/usr/bin/env python3

import requests
import json
import argparse
import re
import xml.etree.ElementTree as ET
from bs4 import BeautifulSoup  # Requires `pip install beautifulsoup4`

LIST_URL = 'https://tfr.faa.gov/tfrapi/exportTfrList'
TFR_DETAIL_URL_TEMPLATE = 'https://tfr.faa.gov/download/detail_{}.xml'

def convert_wgs_string_to_decimal(coord_str):
    """Convert FAA WGS coordinate format to decimal degrees"""
    match = re.match(r"([\d\.]+)([NSEW])", coord_str)
    if match:
        value, direction = match.groups()
        value = float(value)
        if direction in ['S', 'W']:
            value *= -1
        return value
    return None

def fetch_tfr_list():
    """Fetch the list of active TFRs"""
    print("Retrieving list of TFRs...")
    response = requests.get(LIST_URL)
    if response.status_code != 200:
        print(f"Error fetching list: {response.status_code}")
        return None
    return response.json()

def extract_notam_ids(tfr_list_json):
    """Extract NOTAM IDs from the TFR list JSON"""
    notam_ids = []
    for tfr in tfr_list_json:
        notam_id = tfr.get('notam_id') or tfr.get('notamNumber')
        if notam_id:
            # Convert NOTAM ID to the format used in detail URLs
            match = re.match(r'(\d+)/(\d+)', notam_id)
            if match:
                notam_ids.append(f"{match.group(1)}_{match.group(2)}")
    return notam_ids

def fetch_tfr_detail(notam_id):
    """Fetch detailed TFR data for a given NOTAM ID"""
    url = TFR_DETAIL_URL_TEMPLATE.format(notam_id)
    response = requests.get(url)
    if response.status_code != 200:
        print(f"Warning: Failed to fetch details for NOTAM {notam_id}")
        return None
    return response.text

def parse_tfr_xml(xml_data, tfr_metadata):
    """Parse the new-style FAA TFR XML and extract relevant details"""
    def convert_wgs_string_to_decimal(coord_str):
        match = re.match(r"([\d\.]+)([NSEW])", coord_str)
        if match:
            value, direction = match.groups()
            value = float(value)
            if direction in ['S', 'W']:
                value *= -1
            return value
        return None

    tfrs = []
    root = ET.fromstring(xml_data)

    for notam in root.findall(".//Not"):
        tfr = {
            "notam": tfr_metadata.get("notam", None),
            "facility": notam.findtext(".//codeFacility"),
            "type": notam.findtext(".//TfrNot/codeType"),
            "short_description": notam.findtext(".//txtDescrTraditional"),
            "dateIssued": notam.findtext(".//NotUid/dateIssued"),
            "dateEffective": notam.findtext(".//dateEffective"),
            "dateExpire": "PERM",  # New XML doesn't seem to have an explicit expire field
            "upperVal": None,
            "lowerVal": None,
            "area_group": {"boundary_areas": []}
        }

        # Altitude block
        boundary = notam.find(".//aseTFRArea")
        if boundary is not None:
            upper_val = boundary.findtext("valDistVerUpper")
            lower_val = boundary.findtext("valDistVerLower")
            upper_unit = boundary.findtext("uomDistVerUpper")

            tfr["upperVal"] = int(upper_val) if upper_val else None
            tfr["lowerVal"] = int(lower_val) if lower_val else None

            if upper_unit == "FL" and tfr["upperVal"] is not None:
                tfr["upperVal"] *= 100

        # Polygon coordinates
        for area in notam.findall(".//abdMergedArea"):
            points = []
            for avx in area.findall("Avx"):
                lat = avx.findtext("geoLat")
                lon = avx.findtext("geoLong")
                if lat and lon:
                    points.append([
                        convert_wgs_string_to_decimal(lon),
                        convert_wgs_string_to_decimal(lat)
                    ])
            if points:
                tfr["area_group"]["boundary_areas"].append({"points": points})

        tfrs.append(tfr)

    return tfrs


def save_geojson(output_file, tfrs):
    """Save TFR data as GeoJSON"""
    features = []

    for tfr in tfrs:
        for boundary in tfr["area_group"]["boundary_areas"]:
            coordinates = boundary["points"]
            if coordinates:
                features.append({
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [coordinates]
                    },
                    "properties": {
                        "description": tfr["short_description"],  # ✅ Matches Ruby script
                        "notam": tfr["notam"],
                        "dateIssued": tfr["dateIssued"],
                        "dateEffective": tfr["dateEffective"],
                        "dateExpire": tfr["dateExpire"],
                        "upperVal": tfr["upperVal"],
                        "lowerVal": tfr["lowerVal"],
                        "facility": tfr["facility"],  # ✅ Matches Ruby script
                        "type": tfr["type"]  # ✅ Matches Ruby script
                    }
                })

    geojson = {
        "type": "FeatureCollection",
        "features": features
    }

    with open(output_file, "w") as f:
        json.dump(geojson, f, indent=2)
    print(f"Saved {len(features)} features to {output_file}")

def main():
    parser = argparse.ArgumentParser(description="Fetch FAA TFRs and save as GeoJSON")
    parser.add_argument("output_file", help="Path to output file")
    args = parser.parse_args()

    # Step 1: Fetch TFR list
    tfr_list_json = fetch_tfr_list()

    # Step 2: Extract NOTAM IDs
    notam_ids = extract_notam_ids(tfr_list_json)

    # Step 3: Fetch detailed data for each NOTAM ID
    full_tfrs = []
    for notam_id in notam_ids:
        detail_data = fetch_tfr_detail(notam_id)
        if detail_data:
            full_tfrs.extend(parse_tfr_xml(detail_data, {"notam": notam_id}))

    save_geojson(args.output_file, full_tfrs)

if __name__ == "__main__":
    main()

