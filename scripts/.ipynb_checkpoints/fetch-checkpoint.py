#!/usr/local/bin/python3


import requests
from bs4 import BeautifulSoup
import pandas as pd
import xml.etree.ElementTree as ET
from datetime import datetime

# URL of FAA TFR list
tfr_list_url = "https://tfr.faa.gov/tfr2/list.html"
xml_prefix = "https://tfr.faa.gov/save_pages/detail_"
xml_suffix = ".xml"

# Fetch the TFR list page
response = requests.get(tfr_list_url)
response.raise_for_status()  # Ensure request is successful
soup = BeautifulSoup(response.content, "html.parser")

# Extract all NOTAM IDs (linked to detailed XML pages)
#tfr_links = soup.find_all("a", href=True)
notam_ids = []
for link in soup.find_all("a", href=True):
    font_tag = link.find("font", {"color": "blue"})  # Find inner <font color="blue">
    if font_tag:
        notam_id = link.text.strip().replace("/", "_")  # Convert 5/8672 → 5_8672
        notam_ids.append(notam_id)

# Print extracted NOTAM IDs
#print("Extracted NOTAM IDs:", notam_ids)

# Generate XML URLs
xml_urls = [f"https://tfr.faa.gov/save_pages/detail_{notam}.xml" for notam in notam_ids]

# Print the generated XML URLs
#print("Generated XML URLs:", xml_urls)

#exit(0)

# Data storage
tfr_data = []

# Loop through NOTAM IDs to fetch XML data
for notam in notam_ids:
    xml_url = f"{xml_prefix}{notam}{xml_suffix}"
    try:
        xml_response = requests.get(xml_url)
        if xml_response.status_code != 200:
            print(f"Skipping {notam} (XML not found)")
            continue

        # Parse XML
        root = ET.fromstring(xml_response.content)

        # Extract data using XPath
        def extract_text(xpath):
            element = root.find(xpath)
            return element.text.strip() if element is not None else None

        tfr_entry = {
            "NOTAM": notam,
            "GUID": extract_text(".//NotUid/codeGUID"),
            "Date Issued": extract_text(".//NotUid/dateIssued"),
            "Effective Date": extract_text(".//Not/dateEffective"),
            "Expiration Date": extract_text(".//Not/dateExpire"),
            "Time Zone": extract_text(".//Not/codeTimeZone"),
            "City": extract_text(".//Not/AffLocGroup/txtNameCity"),
            "Type": extract_text(".//Not/TfrNot/codeType"),
            "Purpose": extract_text(".//Not/txtDescrPurpose"),
        #    "Full Text": extract_text(".//Not/txtDescrUSNS"),
            "Timestamp": datetime.utcnow().strftime("%Y%m%d%H")
        }

        # Append extracted data
        tfr_data.append(tfr_entry)
        #print(tfr_entry)
    
    except Exception as e:
        print(f"Error processing {notam}: {e}")

# Convert data to a DataFrame
df = pd.DataFrame(tfr_data)

# Save to CSV
csv_filename = f"newTFRids-export.csv"
df.to_csv(csv_filename, index=False, encoding="utf-8")

print(f"TFR data saved to {csv_filename}")

