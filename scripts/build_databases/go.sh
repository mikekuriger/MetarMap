#!/bin/bash

I=`whoami`
if [[ "$I" != "mk7193" ]]; then
  echo "must be mk7193"
  exit 1
fi


date=$(date +%Y-%m-%d-%H:%M:%S)
#BASE="$BASE"
BASE="/data/build_databases"

cd $BASE
#echo $date >> deploy.log

# download new files
python3 get_data.py > /tmp/deploy.log
if [ $? -eq 1 ]; then
    cat /tmp/deploy.log | sed "s/^/$date - /" >> deploy.log
    exit 0
fi
cat /tmp/deploy.log | sed "s/^/$date - /" >> deploy.log

# unzip files
zips="$BASE/zip"
zipfile=$(ls $zips|tail -1)
rm -rf $BASE/alldata/*
cd $BASE/alldata
unzip $zips/$zipfile
rm -rf $BASE/data/*
cd $BASE/data
unzip $BASE/alldata/CSV_Data/*zip
# process files into sqlite3 database
cd $BASE
python3 import_faa_csvs.py
python3 import_fix_csvs.py  
python3 import_frq_csv.py
python3 import_all.py
/bin/mv -f data/*db .
# generate manifest for app
python3 generate_manifest.py
# move files to site
/bin/cp *db db_manifest.json /data/metarmap/sqlite
# deploy live
cd /data
/data/deploy.sh
