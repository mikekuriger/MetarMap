#!/bin/bash

cd /data/chartmaker

# Grand Canyon
echo "Grand Canyon Start"
cp settings.json.GC settings.json

sudo docker run --rm \
  -v /data/chartmaker:/chartmaker:z \
  -w /chartmaker \
  chartmaker:6 \
  bash -lc '/root/.nvm/versions/node/v20.17.0/bin/node make -full-single=1'

echo "Grand Canyon Complete"

echo "FIXING permissions"
sudo chown -R mk7193:mk7193 /data/chartmaker/workarea
echo "Done"
