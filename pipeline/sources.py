"""Load every downloaded source and turn it into grid arrays on the model's 250 m grid and weekly clock.

Everything here returns plain numpy arrays indexed like chi.py: week w (Mondays from chi.WEEK0),
day type d (0 weekday, 1 weekend), 4-hour window k, cell (gy, gx)."""
import glob, json, os
import numpy as np, pandas as pd
import chi

D = os.environ.get("SW_DATA", "data")

STREET_TYPES = {"ROBBERY", "BATTERY", "ASSAULT", "HOMICIDE", "CRIMINAL SEXUAL ASSAULT", "CRIM SEXUAL ASSAULT", "KIDNAPPING"}
STREET_PLACES = {"STREET", "SIDEWALK", "ALLEY", "PARKING LOT / GARAGE (NON RESIDENTIAL)", "PARKING LOT/GARAGE(NON.RESID.)",
                 "PARK PROPERTY", "GAS STATION", "VACANT LOT / LAND", "VACANT LOT/LAND", "BRIDGE", "HIGHWAY / EXPRESSWAY",
                 "HIGHWAY/EXPRESSWAY", "LAKEFRONT / WATERFRONT / RIVERBANK", "LAKEFRONT/WATERFRONT/RIVERBANK"}

# Harm weights for the severity-weighted target, loosely after the Cambridge Crime Harm Index ordering,
# compressed so one homicide does not drown a year of robberies.
def harm_weight(df):
    w = np.ones(len(df), np.float32)
    pt, ds = df.primary_type.to_numpy(), df.description.fillna("").to_numpy()
    agg = np.char.find(ds.astype(str), "AGG") >= 0
    armed = np.char.find(ds.astype(str), "ARMED") >= 0
    w[pt == "HOMICIDE"] = 10
    w[pt == "CRIMINAL SEXUAL ASSAULT"] = 8
    w[pt == "KIDNAPPING"] = 6
    w[pt == "ROBBERY"] = np.where(armed[pt == "ROBBERY"] | agg[pt == "ROBBERY"], 5, 3)
    w[pt == "BATTERY"] = np.where(agg[pt == "BATTERY"], 4, 2)
    w[pt == "ASSAULT"] = np.where(agg[pt == "ASSAULT"], 2, 1)
    return w


def _grid(df, lat="latitude", lon="longitude"):
    df = df[df[lat].between(41.6, 42.1) & df[lon].between(-87.95, -87.5)].copy()
    x, y = chi.to_xy(df[lat].to_numpy(float), df[lon].to_numpy(float))
    df["x"], df["y"] = x, y
    df["gx"] = (x // chi.CELL_M).astype(int).clip(0, chi.NX - 1)
    df["gy"] = (y // chi.CELL_M).astype(int).clip(0, chi.NY - 1)
    return df


def _clock(df, col):
    t = pd.to_datetime(df[col])
    df["date"] = t
    df["week"] = ((t - chi.WEEK0).dt.days // 7).astype(int)
    df["weekend"] = (t.dt.dayofweek >= 5).astype(int)
    df["win"] = t.dt.hour // 4
    return df


def load_all_crimes():
    df = pd.concat([pd.read_csv(f, low_memory=False) for f in sorted(glob.glob(f"{D}/crimes_*.csv.gz"))], ignore_index=True)
    df = df.drop_duplicates("id")
    df["primary_type"] = df.primary_type.replace({"CRIM SEXUAL ASSAULT": "CRIMINAL SEXUAL ASSAULT"})
    df = _clock(_grid(df), "date")
    loc = df.location_description.fillna("")
    dom = df.domestic.astype(str).str.lower().eq("true")
    typ = df.primary_type.isin(STREET_TYPES) | ((df.primary_type == "THEFT") & df.description.isin(["POCKET-PICKING", "PURSE-SNATCHING"]))
    place = loc.isin(STREET_PLACES) | loc.str.startswith("CTA")
    df["street"] = (~dom & typ & place).to_numpy()
    df["harm"] = harm_weight(df)
    df["year"] = df.date.dt.year
    return df


def cell_cube(df, n_weeks, weight=None):
    """(n_weeks, NY, NX) counts, all times pooled."""
    C = np.zeros((n_weeks, chi.NY, chi.NX), np.float32)
    ok = df.week.between(0, n_weeks - 1).to_numpy()
    w = 1.0 if weight is None else np.asarray(weight)[ok]
    np.add.at(C, (df.week.to_numpy()[ok], df.gy.to_numpy()[ok], df.gx.to_numpy()[ok]), w)
    return C


def slot_cube(df, n_weeks, weight=None):
    C = np.zeros((n_weeks, 2, chi.N_WIN, chi.NY, chi.NX), np.float32)
    ok = df.week.between(0, n_weeks - 1).to_numpy()
    w = 1.0 if weight is None else np.asarray(weight)[ok]
    np.add.at(C, (df.week.to_numpy()[ok], df.weekend.to_numpy()[ok], df.win.to_numpy()[ok],
                  df.gy.to_numpy()[ok], df.gx.to_numpy()[ok]), w)
    return C


# ---------------------------------------------------------------- context crimes (not the target)
CONTEXT = {
    "narcotics": lambda d: d.primary_type.eq("NARCOTICS"),
    "weapons": lambda d: d.primary_type.isin(["WEAPONS VIOLATION", "CONCEALED CARRY LICENSE VIOLATION"]),
    "damage": lambda d: d.primary_type.isin(["CRIMINAL DAMAGE", "CRIMINAL TRESPASS"]),
    "property": lambda d: d.primary_type.isin(["BURGLARY", "MOTOR VEHICLE THEFT", "THEFT"]) & ~d.street,
    "disorder": lambda d: d.primary_type.isin(["PUBLIC PEACE VIOLATION", "LIQUOR LAW VIOLATION", "PROSTITUTION",
                                                "GAMBLING", "INTERFERENCE WITH PUBLIC OFFICER"]),
    "violent_indoor": lambda d: d.primary_type.isin(list(STREET_TYPES)) & ~d.street,
}


def context_cubes(df, n_weeks):
    return {k: cell_cube(df[f(df).to_numpy()], n_weeks) for k, f in CONTEXT.items()}


# ---------------------------------------------------------------- 311 requests
def load_311(n_weeks):
    parts = []
    for f in sorted(glob.glob(f"{D}/sr311_*.csv.gz")):
        parts.append(pd.read_csv(f, usecols=["created_date", "sr_type", "latitude", "longitude"]))
    new = pd.concat(parts, ignore_index=True)
    kind = {"Street Light Out Complaint": "lights", "Alley Light Out Complaint": "lights",
            "Viaduct Light Out Complaint": "lights", "Street Light Pole Damage Complaint": "lights",
            "Graffiti Removal Request": "graffiti", "Abandoned Vehicle Complaint": "vehicles",
            "Vacant/Abandoned Building Complaint": "vacant"}
    new["kind"] = new.sr_type.map(kind)
    # the new system starts at different dates per type; older requests come from the legacy datasets
    switch = {"lights": "2018-07-01", "graffiti": "2018-12-31", "vehicles": "2018-12-18", "vacant": "2018-07-03"}
    legacy_files = {"lights": ["lights_all_out", "lights_one_out"], "graffiti": ["graffiti"],
                    "vehicles": ["abandoned_vehicles"], "vacant": ["vacant_buildings"]}
    out = {}
    for k, cut in switch.items():
        n = new[(new.kind == k) & (pd.to_datetime(new.created_date) >= cut)][["created_date", "latitude", "longitude"]]
        old = [pd.read_csv(f"{D}/legacy311_{f}.csv.gz") for f in legacy_files[k] if os.path.exists(f"{D}/legacy311_{f}.csv.gz")]
        old = pd.concat(old) if old else n.iloc[:0]
        old = old[pd.to_datetime(old.created_date) < cut]
        d = _clock(_grid(pd.concat([old, n], ignore_index=True).dropna()), "created_date")
        out[k] = cell_cube(d, n_weeks)
    return out


# ---------------------------------------------------------------- business licenses: active count per week
LICENSE_GROUPS = {
    "bars": ["Tavern", "Consumption on Premises - Incidental Activity", "Music and Dance", "Public Place of Amusement"],
    "liquor_stores": ["Package Goods"],
    "late_hour": ["Late Hour"],
    "food": ["Retail Food Establishment"],
    "gas": ["Filling Station"],
    "pawn_tobacco": ["Pawnbroker", "Tobacco"],
    "hotels": ["Hotel"],
}


def license_cubes(n_weeks):
    df = pd.read_csv(f"{D}/licenses.csv.gz", low_memory=False)
    df = _grid(df.dropna(subset=["latitude", "longitude", "license_start_date"]))
    s = ((pd.to_datetime(df.license_start_date, errors="coerce") - chi.WEEK0).dt.days // 7)
    e = ((pd.to_datetime(df.expiration_date, errors="coerce") - chi.WEEK0).dt.days // 7)
    revoked = df.license_status.isin(["REV", "R"]) if "license_status" in df else False
    df["s"], df["e"] = s, e.fillna(s + 104)
    df = df[df.s.notna() & ~revoked]
    out = {}
    for g, kinds in LICENSE_GROUPS.items():
        d = df[df.license_description.isin(kinds)]
        # one site counted once even when several licence records overlap: dedupe on site + week span
        d = d.drop_duplicates(["account_number", "site_number", "license_description", "s"])
        if g == "bars":
            d = d.drop_duplicates(["account_number", "site_number", "s"])
        delta = np.zeros((n_weeks + 1, chi.NY, chi.NX), np.float32)
        a = d.s.clip(0, n_weeks).astype(int).to_numpy(); b = (d.e + 1).clip(0, n_weeks).astype(int).to_numpy()
        keep = b > a
        np.add.at(delta, (a[keep], d.gy.to_numpy()[keep], d.gx.to_numpy()[keep]), 1)
        np.add.at(delta, (b[keep], d.gy.to_numpy()[keep], d.gx.to_numpy()[keep]), -1)
        out[g] = np.cumsum(delta, 0)[:n_weeks].clip(0)
    return out


# ---------------------------------------------------------------- CTA
def cta_static():
    st = pd.read_csv(f"{D}/cta_l_stops.csv.gz")
    ll = st.location.str.extract(r"\(?\s*(-?\d+\.\d+)[ ,]+(-?\d+\.\d+)")
    a, b = ll[0].astype(float), ll[1].astype(float)
    st["latitude"], st["longitude"] = np.where(a > 0, a, b), np.where(a > 0, b, a)
    st = st.drop_duplicates("map_id")
    st = _grid(st)
    L = np.zeros((chi.NY, chi.NX), np.float32)
    np.add.at(L, (st.gy, st.gx), 1)
    # distance from each cell centre to the nearest station
    from scipy.spatial import cKDTree
    yy, xx = np.mgrid[0:chi.NY, 0:chi.NX]
    cx, cy = (xx + 0.5) * chi.CELL_M, (yy + 0.5) * chi.CELL_M
    dist = cKDTree(np.c_[st.x, st.y]).query(np.c_[cx.ravel(), cy.ravel()])[0].reshape(chi.NY, chi.NX)
    bus = pd.read_csv(f"{D}/cta_bus_stops.csv.gz")
    ll = bus.location.astype(str).str.extract(r"(-?\d+\.\d+)[ ,]+(-?\d+\.\d+)")
    a, b = ll[0].astype(float), ll[1].astype(float)
    bus["latitude"], bus["longitude"] = np.where(a > 0, a, b), np.where(a > 0, b, a)
    bus = _grid(bus.dropna(subset=["latitude"]))
    B = np.zeros((chi.NY, chi.NX), np.float32); BB = np.zeros_like(B)
    np.add.at(B, (bus.gy, bus.gx), 1)
    np.add.at(BB, (bus.gy, bus.gx), pd.to_numeric(bus.boardings, errors="coerce").fillna(0).to_numpy(np.float32))
    return dict(l_stations=L, l_dist_m=dist.astype(np.float32), bus_stops=B, bus_boardings=BB), st


def cta_rides(n_weeks, stations):
    """(n_weeks, 2, NY, NX) L station entries per day, weekday vs weekend, at each station's cell."""
    r = pd.read_csv(f"{D}/cta_l_rides.csv.gz")
    r["date"] = pd.to_datetime(r.date)
    r["week"] = (r.date - chi.WEEK0).dt.days // 7
    r["weekend"] = (r.date.dt.dayofweek >= 5).astype(int)
    loc = stations.set_index(stations.map_id.astype(int))[["gy", "gx"]]
    r = r.join(loc, on="station_id", how="inner")
    r = r[r.week.between(0, n_weeks - 1)]
    R = np.zeros((n_weeks, 2, chi.NY, chi.NX), np.float32)
    np.add.at(R, (r.week, r.weekend, r.gy, r.gx), r.rides.astype(np.float32))
    R[:, 0] /= 5; R[:, 1] /= 2
    return R


# ---------------------------------------------------------------- big events (park permits, known in advance)
def event_cubes(n_weeks):
    ev = pd.read_csv(f"{D}/park_events.csv.gz")
    ev = ev[~ev.permit_status.fillna("").str.contains("Cancel|Denied|Withdrawn", case=False)]
    big = ev.event_type.fillna("").str.contains(r"12,001\+|10,000\+|Festival|Event [3-6]|Level 3|Level 4|Level 5", regex=True)
    ev = ev[big & ~ev.event_type.str.contains("Administrative|Media|Athletic Training", na=False)]
    parks = pd.DataFrame(json.load(open(f"{D}/parks.json")))
    parks["latitude"] = parks.location.map(lambda l: float(l["latitude"]) if isinstance(l, dict) and "latitude" in l else np.nan)
    parks["longitude"] = parks.location.map(lambda l: float(l["longitude"]) if isinstance(l, dict) and "longitude" in l else np.nan)
    parks = _grid(parks.dropna(subset=["latitude"]))
    parks["park_number"] = parks.park_number.astype(str)
    ev = ev.dropna(subset=["park_number"])
    ev["park_number"] = ev.park_number.astype(int).astype(str)
    ev = ev.merge(parks[["park_number", "gy", "gx"]], on="park_number", how="inner")
    E = np.zeros((n_weeks, 2, chi.NY, chi.NX), np.float32)
    for s, e, gy, gx in zip(pd.to_datetime(ev.reservation_start_date), pd.to_datetime(ev.reservation_end_date), ev.gy, ev.gx):
        if pd.isna(s):
            continue
        e = s if pd.isna(e) or e < s or (e - s).days > 14 else e
        for day in pd.date_range(s.normalize(), e.normalize()):
            w = (day - chi.WEEK0).days // 7
            if 0 <= w < n_weeks:
                E[w, int(day.dayofweek >= 5), gy, gx] += 1
    return E, len(ev)


# ---------------------------------------------------------------- weather (city-wide, per week x slot)
def weather_table(n_weeks):
    w = pd.read_csv(f"{D}/weather.csv.gz")
    t = pd.to_datetime(w.time)
    w["week"] = (t - chi.WEEK0).dt.days // 7
    w["weekend"] = (t.dt.dayofweek >= 5).astype(int); w["win"] = t.dt.hour // 4
    w["wet"] = (w.precipitation > 0.2).astype(float)
    g = w[w.week.between(0, n_weeks - 1)].groupby(["week", "weekend", "win"]).agg(
        temp=("temperature_2m", "mean"), feels=("apparent_temperature", "mean"), wet_hours=("wet", "mean"),
        precip=("precipitation", "sum"), snow=("snowfall", "sum"), cloud=("cloud_cover", "mean"), wind=("wind_speed_10m", "mean"))
    T = np.full((n_weeks, 2, chi.N_WIN, len(g.columns)), np.nan, np.float32)
    idx = g.index.to_frame().to_numpy()
    T[idx[:, 0], idx[:, 1], idx[:, 2]] = g.to_numpy(np.float32)
    return T, list(g.columns)
