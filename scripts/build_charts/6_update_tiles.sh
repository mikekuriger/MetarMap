#!/bin/bash

[ -d "/data/workarea/Sectional/6_quantized" ] || { echo "Missing directory, make sure to generate tiles first"; exit 1; }

# remove previous backup
/bin/rm -rf /data/metarmap/Sectional.pre
/bin/rm -rf /data/metarmap/Terminal.pre

# backup current charts
/bin/mv -f /data/metarmap/Sectional /data/metarmap/Sectional.pre
/bin/mv -f /data/metarmap/Terminal /data/metarmap/Terminal.pre

# move new charts into place
/bin/mv /data/workarea/Sectional/6_quantized /data/metarmap/Sectional
/bin/mv /data/workarea/Terminal/6_quantized /data/metarmap/Terminal
cd /data/metarmap/Terminal
/bin/rm -rf 5  6  7  8  9
