#!/usr/bin/env python3

import requests
import json
import argparse
import re
import xml.etree.ElementTree as ET
from bs4 import BeautifulSoup  # Requires `pip install beautifulsoup4`

LIST_URL = 'https://tfr.faa.gov/tfr2/list.html'

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
    return response.text

def parse_tfr_list(html):
    """Parse the FAA HTML page to extract TFR metadata"""
    soup = BeautifulSoup(html, 'html.parser')
    rows = soup.select('table table')[2].find_all('tr')[7:-4]

    tfrs = []
    for row in rows:
        cols = row.find_all('a')
        if len(cols) < 2:
            continue

        tfrs.append({
            "notam": cols[1].text.strip(),
            "facility": cols[2].text.strip(),
            "state": cols[3].text.strip(),
            "type": cols[4].text.strip(),
            "short_description": cols[5].text.strip(),
            "links": {
                "xml": cols[1]['href'].replace('..', 'https://tfr.faa.gov').replace('html', 'xml')
            }
        })

    return tfrs

def fetch_tfr_xml(url):
    """Retrieve XML data for a specific TFR"""
    print(f"Fetching XML: {url}")
    response = requests.get(url)
    if response.status_code != 200:
        print(f"Error fetching XML: {response.status_code}")
        return None
    return response.text

def parse_tfr_xml(xml_data, tfr_metadata):
    """Parse the TFR XML and extract relevant details"""
    tfrs = []
    root = ET.fromstring(xml_data)

    for notam in root.findall(".//{*}Not"):
        tfr = {
            "notam": tfr_metadata["notam"],
            "facility": tfr_metadata["facility"],
            "type": tfr_metadata["type"],
            "short_description": tfr_metadata["short_description"],
            "dateIssued": notam.find(".//{*}NotUid/{*}dateIssued").text if notam.find(".//{*}NotUid/{*}dateIssued") is not None else None,
            "dateEffective": None,
            "dateExpire": None,
            "upperVal": None,
            "lowerVal": None,
            "area_group": {"boundary_areas": []}
        }

        # Extract schedule times
        schedule_group = notam.find(".//{*}ScheduleGroup")
        if schedule_group is not None:
            is_time_separate = schedule_group.find(".//{*}isTimeSeparate")
            if is_time_separate is not None and is_time_separate.text == "TRUE":
                tfr["dateEffective"] = schedule_group.find(".//{*}startTime").text if schedule_group.find(".//{*}startTime") is not None else None
                tfr["dateExpire"] = schedule_group.find(".//{*}endTime").text if schedule_group.find(".//{*}endTime") is not None else None
            else:
                tfr["dateEffective"] = schedule_group.find(".//{*}dateEffective").text if schedule_group.find(".//{*}dateEffective") is not None else None
                tfr["dateExpire"] = schedule_group.find(".//{*}dateExpire").text if schedule_group.find(".//{*}dateExpire") is not None else None

        # Extract altitude information
        boundary = notam.find(".//{*}TFRAreaGroup/{*}aseTFRArea")
        if boundary is not None:
            upper_val = boundary.find(".//{*}valDistVerUpper")
            lower_val = boundary.find(".//{*}valDistVerLower")
            upper_unit = boundary.find(".//{*}uomDistVerUpper")

            tfr["upperVal"] = int(upper_val.text) if upper_val is not None else None
            tfr["lowerVal"] = int(lower_val.text) if lower_val is not None else None

            # ✅ Multiply by 100 if upper unit is "FL" (Flight Level)
            if upper_unit is not None and upper_unit.text == "FL" and tfr["upperVal"] is not None:
                tfr["upperVal"] *= 100

        # Extract polygons
        for area in notam.findall(".//{*}abdMergedArea"):
            points = []
            for avx in area.findall(".//{*}Avx"):
                lat = avx.find(".//{*}geoLat")
                lon = avx.find(".//{*}geoLong")
                if lat is not None and lon is not None:
                    points.append([convert_wgs_string_to_decimal(lon.text), convert_wgs_string_to_decimal(lat.text)])

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

    html = fetch_tfr_list()
    if html:
        tfrs = parse_tfr_list(html)
        full_tfrs = []

        for tfr in tfrs:
            xml_data = fetch_tfr_xml(tfr["links"]["xml"])
            if xml_data:
                full_tfrs.extend(parse_tfr_xml(xml_data, tfr))  # ✅ Pass `tfr` metadata

        save_geojson(args.output_file, full_tfrs)

if __name__ == "__main__":
    main()

