#!/bin/bash
runtime=$(date +%s)
P="/Users/mk7193/python/tfr/scripts"

while IFS="," read  notam guid di ed ex tz city type pur timestamp
do
    temp_guid="${guid%\"}"
    temp_notam="${notam%\"}"
    temp_guid="${temp_guid#\"}"
    temp_notam="${temp_notam#\"}"
    if [[ "$temp_notam" != "NOTAM" ]]; then
      echo wget https://tfr.faa.gov/save_pages/$temp_notam.shp.zip -P /tmp/
    fi
    
done < newTFRids-export.csv
exit 1
# loop over archive file if present to check if shapefile archive has been posted
if [ -f "${P}/newTFRids-archive.csv" ]; then
    while IFS="," read notam guid di ed ex tz city type pur timestamp
    do
        temp_guid="${guid%\"}"
        temp_notam="${notam%\"}"
        temp_guid="${temp_guid#\"}"
        temp_notam="${temp_notam#\"}"
        temp_date="${timestamp%\"}"
        temp_date="${temp_date#\"}"
        wget https://tfr.faa.gov/save_pages/$temp_notam.shp.zip -P /tmp/
    
        if [ -f "/tmp/$temp_notam.shp.zip" ]; then
            unzip /tmp/$temp_notam.shp.zip -d ${P}/shapefiles/$temp_guid
            sed -i "/$temp_guid/d" newTFRids-archive.csv
        else
            # check timestamp to see if TFR should be removed from archive to avoid downloading shapefiles of a future TFR that shares NOTAM identifier
            timestamp_d=$(date -d @$timestamp)
            expire_stamp_d=$(date --date="$timestamp_d +7 days")
            expire_stamp_s=$(date --date="$expire_stamp_d" +%s)
            
            # remove record from archived TFRs if no shapefile has been found within a week
            if [ $runtime -ge $expire_stamp_s ]; then
                sed -i "/$temp_guid/d" newTFRids-archive.csv
            fi
        fi
    done < newTFRids-archive.csv
fi
