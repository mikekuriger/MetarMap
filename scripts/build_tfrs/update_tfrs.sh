#!/bin/bash

I=`whoami`
if [[ "$I" != "mk7193" ]]; then
  echo "must be mk7193"
  exit 1
fi


export HOME="/Users/mk7193"
export PATH="/usr/local/bin:/usr/bin:/bin"
export DATE=$(date "+%y%m%d_%H%M%S")

#eval $(ssh-agent -s)
#ssh-add ~/.ssh/id_rsa

cd /data/build_tfrs || exit
#ruby faa_get_tfrs -f geojson tfrs.geojson
#python3 faa_get_tfrs.py tfrs.geojson

# geojson files
#curl 'https://tfr.faa.gov/geoserver/TFR/ows?service=WFS&version=1.0.0&request=GetFeature&typeName=TFR%3AV_TFR_LOC&maxFeatures=50&outputFormat=application%2Fjson' > /data/MetarMap/scripts/tfrs.geojson

python3 faa_get_tfrs.py tfrs.geojson
/bin/cp tfrs.geojson /data/MetarMap/scripts

cd /data/MetarMap/scripts

git add tfrs.geojson
git commit -m "auto-commit $DATE"
git push origin main
