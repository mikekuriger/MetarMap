import os
import sys
import datetime
import requests

DOWNLOAD_DIR = "/data/build_databases/zip"
#FAA_BASE_URL = "https://www.faa.gov/air_traffic/flight_info/aeronav/digital_products/dafd/"
FAA_BASE_URL = "https://nfdc.faa.gov/webContent/28DaySub/"

# Hardcoded known AIRAC date to calculate next (adjust if needed)
REFERENCE_AIRAC = datetime.date(2024, 1, 25)
AIRAC_CYCLE_DAYS = 28

def get_next_airac_date():
    today = datetime.date.today()
    airac_date = REFERENCE_AIRAC
    while airac_date <= today:
        airac_date += datetime.timedelta(days=AIRAC_CYCLE_DAYS)
    return airac_date

def format_date_string(d):
    return d.strftime("%B_%d_%Y").lower()  # e.g. "march_21_2024"

def download_airac_zip(effective_date):
    zip_name = f"28DaySubscription_Effective_{effective_date}.zip"
    download_url = f"{FAA_BASE_URL}{zip_name}"
    output_path = os.path.join(DOWNLOAD_DIR, zip_name)

    if os.path.exists(output_path):
        print(f"✅ Already downloaded: {zip_name}")
        sys.exit(1) 

    print(f"📡 Downloading {zip_name} from {download_url}...")
    try:
        response = requests.get(download_url, stream=True)
        if response.status_code == 200:
            with open(output_path, "wb") as f:
                for chunk in response.iter_content(chunk_size=8192):
                    f.write(chunk)
            print(f"✅ Download complete: {output_path}")
        else:
            print(f"❌ Failed to download: {response.status_code} - {download_url}")
    except Exception as e:
        print(f"❌ Error downloading file: {e}")

def main():
    next_airac = get_next_airac_date()
    print(f"📅 Next AIRAC effective date: {next_airac}")
    download_airac_zip(next_airac)

if __name__ == "__main__":
    main()

