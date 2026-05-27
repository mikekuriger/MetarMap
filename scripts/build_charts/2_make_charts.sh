#!/bin/bash

# Sectional
echo "Sectional Start"
cd /data/chartmaker
cp settings.json.Sectional settings.json

sudo docker run --rm \
  -v /data/chartmaker:/chartmaker:z \
  -w /chartmaker \
  chartmaker:6 \
  bash -lc '/root/.nvm/versions/node/v20.17.0/bin/node make -full-single=0'

echo "Sectional Complete"

# Terminal
echo "Terminal Start"
cp settings.json.Terminal settings.json

sudo docker run --rm \
  -v /data/chartmaker:/chartmaker:z \
  -w /chartmaker \
  chartmaker:6 \
  bash -lc '/root/.nvm/versions/node/v20.17.0/bin/node make -full-single=2'

echo "Terminal Complete"

# Grand Canyon
echo "Grand Canyon Start"
cp settings.json.GC settings.json

sudo docker run --rm \
  -v /data/chartmaker:/chartmaker:z \
  -w /chartmaker \
  chartmaker:6 \
  bash -lc '/root/.nvm/versions/node/v20.17.0/bin/node make -full-single=1'

echo "Grand Canyon Complete"

# Enroute Low
echo "IFR Start"
cp settings.json.Sectional settings.json

sudo docker run --rm \
  -v /data/chartmaker:/chartmaker:z \
  -w /chartmaker \
  chartmaker:6 \
  bash -lc '/root/.nvm/versions/node/v20.17.0/bin/node make -full-single=4'

echo "IFR Complete"

echo "FIXING permissions"
sudo chown -R mk7193:mk7193 /data/chartmaker/workarea
echo "Done"
