#!/bin/bash

[ -d "/data/workarea/Sectional/6_quantized" ] || { echo "Missing directory, make sure to generate tiles first"; exit 1; }

# remove previous backup
/bin/rm -rf /data/metarmap/Sectional.pre
/bin/rm -rf /data/metarmap/Terminal.pre
/bin/rm -rf /data/metarmap/Enroute_Low.pre

# cleanup
find /data/chartmaker/workarea/Sectional/6_quantized -name "*.xml" -exec /bin/rm {} \;
find /data/chartmaker/workarea/Terminal/6_quantized -name "*.xml" -exec /bin/rm {} \;
find /data/chartmaker/workarea/Enroute_Low/6_quantized -name "*.xml" -exec /bin/rm {} \;

# backup current charts
/bin/rm -rf /data/Sectional.old /data/Terminal.old /data/Enroute_Low.old
/bin/mv -f /data/metarmap/Sectional /data/Sectional.old
/bin/mv -f /data/metarmap/Terminal /data/Terminal.old
/bin/mv -f /data/metarmap/Enroute_Low /data/Enroute_Low.old

# move new charts into place
/bin/mv /data/workarea/Sectional/6_quantized /data/metarmap/Sectional
/bin/mv /data/workarea/Terminal/6_quantized /data/metarmap/Terminal
/bin/mv /data/workarea/Enroute_Low/6_quantized /data/metarmap/Enroute_Low
