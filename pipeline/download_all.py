"""Download every free data source SafeWalk uses. Re-running only fetches what is missing,
except files for the current year, which are refreshed (so a weekly job picks up new rows).

Sources (all free, no API key):
  City of Chicago open data portal (data.cityofchicago.org, Socrata SoQL):
    crimes (all types, 2015-now), street centerlines, 311 requests (street/alley lights out,
    graffiti, abandoned vehicles, vacant buildings), business licenses (bars, liquor stores,
    late-hour, restaurants...), CTA 'L' stops + daily station entries, CTA bus stops,
    Park District outdoor event permits + park boundaries.
  Open-Meteo archive (archive-api.open-meteo.com): hourly weather for central Chicago.
"""
import os, sys, json, time, datetime as dt, urllib.parse, urllib.request
import pandas as pd

D = os.environ.get("SW_DATA", "data")
os.makedirs(D, exist_ok=True)
PORTAL = "https://data.cityofchicago.org/resource/{}.csv"
THIS_YEAR = dt.date.today().year
FIRST_YEAR = 2015
UA = {"User-Agent": "safewalk-retrain (indie, non-commercial)"}
APP_TOKEN = os.environ.get("SOCRATA_APP_TOKEN")      # optional; raises rate limits
if APP_TOKEN:
    UA["X-App-Token"] = APP_TOKEN


def fetch(url, params=None, tries=6, timeout=600):
    q = f"{url}?{urllib.parse.urlencode(params)}" if params else url
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(q, headers=UA), timeout=timeout) as r:
                return r.read()
        except Exception as e:
            if k == tries - 1:
                raise
            print(f"  retry {k + 1} after {type(e).__name__}: {e}", flush=True)
            time.sleep(5 * 2 ** k)


def soql_csv(ds, select, where, path, order=":id", page=500_000, refresh=False):
    """Paged CSV download of a Socrata query into a gzip CSV."""
    if os.path.exists(path) and not refresh:
        return
    parts, off = [], 0
    while True:
        blob = fetch(PORTAL.format(ds), {"$select": select, "$where": where, "$order": order,
                                         "$limit": page, "$offset": off})
        tmp = path + ".part"
        open(tmp, "wb").write(blob)
        d = pd.read_csv(tmp, low_memory=False); os.remove(tmp)
        parts.append(d); off += len(d)
        if len(d) < page:
            break
    df = pd.concat(parts, ignore_index=True)
    df.to_csv(path, index=False)
    print(f"{os.path.basename(path)}: {len(df):,} rows", flush=True)


def yearly(name, ds, select, where_tpl, datecol, first=FIRST_YEAR - 1):
    for y in range(first, THIS_YEAR + 1):
        w = f"{datecol} >= '{y}-01-01T00:00:00' AND {datecol} < '{y + 1}-01-01T00:00:00' AND ({where_tpl})"
        soql_csv(ds, select, w, f"{D}/{name}_{y}.csv.gz", refresh=(y >= THIS_YEAR - (1 if dt.date.today().month == 1 else 0)))


def main(which):
    if "crimes" in which:     # all crime types: street crimes are the target, the rest are context features
        yearly("crimes", "ijzp-q8t2",
               "id,date,primary_type,description,location_description,domestic,latitude,longitude",
               "latitude IS NOT NULL", "date", first=FIRST_YEAR)

    if "streets" in which:
        for off in (0, 30000):
            p = f"{D}/streets_{off}.json"
            if not os.path.exists(p):
                open(p, "wb").write(fetch("https://data.cityofchicago.org/resource/pr57-gg9e.json",
                                          {"$select": "fnode_id,tnode_id,the_geom,class,street_nam,street_typ,pre_dir,length,status",
                                           "$order": "objectid", "$limit": 30000, "$offset": off}))

    if "311" in which:
        types = ("'Street Light Out Complaint','Alley Light Out Complaint','Viaduct Light Out Complaint',"
                 "'Street Light Pole Damage Complaint','Graffiti Removal Request','Abandoned Vehicle Complaint',"
                 "'Vacant/Abandoned Building Complaint'")
        yearly("sr311", "v6vf-nfxy", "created_date,sr_type,latitude,longitude",
               f"sr_type in({types}) AND duplicate = false AND latitude IS NOT NULL", "created_date", first=2018)
        # pre-2019 history lives in per-type legacy datasets
        legacy = {"lights_all_out": ("zuxi-7xem", "creation_date"), "lights_one_out": ("3aav-uy2v", "creation_date"),
                  "graffiti": ("hec5-y4x5", "creation_date"), "abandoned_vehicles": ("3c9v-pnva", "creation_date"),
                  "vacant_buildings": ("7nii-7srd", "date_service_request_was_received")}
        for name, (ds, dc) in legacy.items():
            soql_csv(ds, f"{dc} AS created_date,latitude,longitude",
                     f"{dc} >= '{FIRST_YEAR - 1}-01-01T00:00:00' AND {dc} < '2018-12-31T00:00:00' AND latitude IS NOT NULL",
                     f"{D}/legacy311_{name}.csv.gz")

    if "licenses" in which:
        kinds = ("'Tavern','Consumption on Premises - Incidental Activity','Package Goods','Late Hour',"
                 "'Retail Food Establishment','Public Place of Amusement','Music and Dance','Filling Station',"
                 "'Pawnbroker','Hotel','Outdoor Patio','Tobacco'")
        soql_csv("r5kz-chrr", "account_number,site_number,license_description,license_start_date,expiration_date,"
                 "license_status,latitude,longitude",
                 f"license_description in({kinds}) AND latitude IS NOT NULL", f"{D}/licenses.csv.gz",
                 refresh=True)

    if "cta" in which:
        soql_csv("8pix-ypme", "stop_id,station_name,map_id,location", "1=1", f"{D}/cta_l_stops.csv.gz", order="stop_id")
        soql_csv("5neh-572f", "station_id,date,daytype,rides", f"date >= '{FIRST_YEAR - 1}-01-01T00:00:00'",
                 f"{D}/cta_l_rides.csv.gz", refresh=True)
        soql_csv("mq3i-nnqe", "*", "1=1", f"{D}/cta_bus_stops.csv.gz")

    if "events" in which:
        soql_csv("pk66-w54g", "park_number,park_facility_name,reservation_start_date,reservation_end_date,event_type,permit_status",
                 f"reservation_start_date >= '{FIRST_YEAR - 1}-01-01T00:00:00'", f"{D}/park_events.csv.gz", refresh=True)
        p = f"{D}/parks.json"
        if not os.path.exists(p):         # park points (2016 list, the last one with coordinates)
            open(p, "wb").write(fetch("https://data.cityofchicago.org/resource/wwy2-k7b3.json",
                                      {"$select": "park_number,park_name,acres,location", "$limit": 5000}))

    if "weather" in which:
        p = f"{D}/weather.csv.gz"
        end = (dt.date.today() - dt.timedelta(days=2)).isoformat()
        blob = fetch("https://archive-api.open-meteo.com/v1/archive",
                     dict(latitude=41.88, longitude=-87.68, start_date=f"{FIRST_YEAR - 1}-12-01", end_date=end,
                          hourly="temperature_2m,precipitation,snowfall,apparent_temperature,cloud_cover,wind_speed_10m",
                          timezone="America/Chicago"))
        h = json.loads(blob)["hourly"]
        pd.DataFrame(h).to_csv(p, index=False)
        print("weather:", len(h["time"]), "hours", flush=True)


if __name__ == "__main__":
    main(set(sys.argv[1:]) or {"crimes", "streets", "311", "licenses", "cta", "events", "weather"})
