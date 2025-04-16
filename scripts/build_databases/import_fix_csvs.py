import os
import sqlite3
import csv
import pandas as pd

SCHEMA_FILE = "FIX_CSV_DATA_STRUCTURE.csv"
DATA_DIR = "data"
OUTPUT_DB = "faa_fixes.db"

def map_data_type(dtype):
    dtype = dtype.upper()
    if dtype == "VARCHAR":
        return "TEXT"
    elif dtype == "NUMBER":
        return "REAL"
    return "TEXT"

def create_table(cursor, table_name, table_schema):
    columns = []
    for _, row in table_schema.iterrows():
        colname = row["Column Name"]
        coltype = map_data_type(row["Data Type"])
        nullable = "" if row["Nullable"] == "No" else "NULL"
        columns.append(f'"{colname}" {coltype} {nullable}')
    create_sql = f"CREATE TABLE IF NOT EXISTS {table_name} (\n  " + ",\n  ".join(columns) + "\n);"
    cursor.execute(f"DROP TABLE IF EXISTS {table_name};")
    cursor.execute(create_sql)

def import_csv_to_table(cursor, table_name, csv_path):
    with open(csv_path, newline='', encoding='ISO-8859-1') as f:
        reader = csv.reader(f)
        headers = next(reader)
        placeholders = ", ".join(["?"] * len(headers))
        insert_sql = f'INSERT INTO {table_name} VALUES ({placeholders})'
        for row in reader:
            cursor.execute(insert_sql, row)

def main():
    schema = pd.read_csv(os.path.join(DATA_DIR, SCHEMA_FILE))
    conn = sqlite3.connect(os.path.join(DATA_DIR, OUTPUT_DB))
    cursor = conn.cursor()

    for table_name in schema["CSV File"].unique():
        csv_filename = f"{table_name}.csv"
        csv_path = os.path.join(DATA_DIR, csv_filename)
        table_schema = schema[schema["CSV File"] == table_name]

        print(f"Processing {csv_filename}...")
        create_table(cursor, table_name, table_schema)

        if os.path.exists(csv_path):
            import_csv_to_table(cursor, table_name, csv_path)
            print(f"  → Loaded data into {table_name}")
        else:
            print(f"  → CSV file not found: {csv_filename}")

    conn.commit()
    conn.close()
    print(f"Done. Database saved as {OUTPUT_DB}")

if __name__ == "__main__":
    main()
