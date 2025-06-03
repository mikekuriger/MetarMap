
The actual chart data is generated in chartmaker...
become root, run "go" to enter the docker container
cd chartmaker
node make

# you want to build all sectionals, and all terminals.  i will script it someday

then go here and make zips:
/data/metarmap/make_zips.py
* Wall - 5 6 7
* Sectional - 8 9 10 11 
* Terminal - 10 11 12 13

These json files are used by the app to provide zipfile downloads of terminal / sectional chats to the user

./generate_sectionals_json.py 
✅ Generated /data/metarmap/zips/sectionals.json with 38 entries.

./generate_terminals_json.py 
⚠️ Skipping missing or invalid file: Albuquerque.zip
⚠️ Skipping missing or invalid file: Billings.zip
⚠️ Skipping missing or invalid file: Brownsville.zip
⚠️ Skipping missing or invalid file: El_Paso.zip
⚠️ Skipping missing or invalid file: Great_Falls.zip
⚠️ Skipping missing or invalid file: Halifax.zip
⚠️ Skipping missing or invalid file: Honolulu_Inset.zip
⚠️ Skipping missing or invalid file: Klamath_Falls.zip
⚠️ Skipping missing or invalid file: Lake_Huron.zip
⚠️ Skipping missing or invalid file: Montreal.zip
✅ Generated /data/metarmap/zips2/terminals.json with 29 entries.
