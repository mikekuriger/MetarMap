#!/bin/bash

I=`whoami`
if [[ "$I" != "mk7193" ]]; then
  echo "must be mk7193"
  exit 1
fi


export HOME="/home/mk7193"
#export PATH="/usr/local/bin:/usr/bin:/bin"
export DATE=$(date "+%y%m%d_%H%M%S")
PATH=$PATH:$HOME/bin:/home/t/bin:/usr/lib64/qt-3.3/bin:/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin:/root/bin
export PATH

#eval $(ssh-agent -s)
#ssh-add ~/.ssh/id_rsa

cd /data/build_tfrs || exit
#ruby faa_get_tfrs -f geojson tfrs.geojson
#python3 faa_get_tfrs.py tfrs.geojson

# geojson files
#curl 'https://tfr.faa.gov/geoserver/TFR/ows?service=WFS&version=1.0.0&request=GetFeature&typeName=TFR%3AV_TFR_LOC&maxFeatures=50&outputFormat=application%2Fjson' > /data/MetarMap/scripts/tfrs.geojson

/usr/bin/python3 faa_get_tfrs.py tfrs.geojson
/bin/cp tfrs.geojson /data/MetarMap/scripts

cd /data/MetarMap/scripts

git add tfrs.geojson
git commit -m "auto-commit $DATE"
# Push to `data` branch — keeps daily TFR auto-commits out of `main`.
# The Android app reads from this branch via Endpoints.TFR_GEOJSON.
git push origin HEAD:data
