#!/bin/bash

export HOME="/Users/mk7193"
export PATH="/usr/local/bin:/usr/bin:/bin"
export DATE=$(date "+%y%m%d_%H%M%S")

eval $(ssh-agent -s)
ssh-add ~/.ssh/id_rsa

cd /Users/mk7193/AndroidStudioProjects/MetarMap/scripts || exit
#ruby faa_get_tfrs -f geojson tfrs.geojson
python3 faa_get_tfrs.py tfrs.geojson
git add tfrs.geojson
git commit -m "auto-commit $DATE"
git push origin main
