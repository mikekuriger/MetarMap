#!/usr/bin/env python3

import os
import requests
from lxml import html
from urllib.parse import urljoin

# Base URLs
TFR_LIST_URL = "https://tfr.faa.gov/tfr2/list.jsp"
BASE_URL = "https://tfr.faa.gov"
OUTPUT_DIR = "tfr_xml_files"

# Ensure output directory exists
os.makedirs(OUTPUT_DIR, exist_ok=True)

def get_tfr_detail_pages():
    """Fetches the TFR list page and extracts unique links to TFR detail pages."""
    print(f"Fetching TFR list: {TFR_LIST_URL}")
    response = requests.get(TFR_LIST_URL, headers={"User-Agent": "Mozilla/5.0"})
    response.raise_for_status()
    
    tree = html.fromstring(response.content)

    # Extract detail page links (they contain 'save_pages/detail' in the href)
    tfr_links = set(tree.xpath('//a[contains(@href, "save_pages/detail")]/@href'))  # Use a set to avoid duplicates
    tfr_links = {urljoin(BASE_URL, link) for link in tfr_links}  # Convert to full URLs

    print(f"Found {len(tfr_links)} unique TFR detail pages.")
    return tfr_links

def convert_to_xml_url(detail_page_url):
    """Converts a detail page URL to its corresponding XML file URL."""
    xml_url = detail_page_url.replace(".html", ".xml")
    return xml_url

def download_xml(xml_url):
    """Downloads and saves the XML file if it hasn't been downloaded yet."""
    if not xml_url:
        return

    filename = os.path.join(OUTPUT_DIR, os.path.basename(xml_url))

    if os.path.exists(filename):  # Skip if already downloaded
        print(f"Skipping (already exists): {filename}")
        return

    print(f"Downloading XML: {xml_url}")
    response = requests.get(xml_url, headers={"User-Agent": "Mozilla/5.0"})

    if response.status_code == 404:
        print(f"Error: XML not found for {xml_url}")
        return
    
    response.raise_for_status()

    with open(filename, "wb") as file:
        file.write(response.content)
    
    print(f"Saved XML: {filename}")

def main():
    """Main function to scrape and download unique TFR XML files."""
    tfr_detail_links = get_tfr_detail_pages()

    xml_urls = {convert_to_xml_url(url) for url in tfr_detail_links}  # Use set to remove duplicates

    print(f"Found {len(xml_urls)} unique XML files to download.")

    for xml_url in xml_urls:
        download_xml(xml_url)

    print("All available TFR XML files downloaded.")

if __name__ == "__main__":
    main()

