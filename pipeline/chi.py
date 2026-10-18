"""Chicago street-crime hotspots: data loading, real-world grid, time slots, street network."""
import json, glob
import numpy as np, pandas as pd

CRIME_FILE = "data/chicago_street_crime_2015_2024.csv.gz"
STREET_FILES = "data/streets_*.json"

# ---- real-world grid: local metric projection around Chicago
LON0, LAT0 = -87.95, 41.64
M_PER_DEG_LAT = 110_950.0
M_PER_DEG_LON = 111_320.0 * np.cos(np.radians(41.84))
CELL_M = 250                        # 250 m x 250 m cells
NX, NY = 144, 176                   # 36 km x 44 km covers the whole city

# ---- time: weekly blocks, weekday/weekend x six 4-hour windows = 12 slots
WEEK0 = pd.Timestamp("2014-12-29")  # a Monday
N_WIN = 6
DAYTYPES = ("Weekday", "Weekend")
WIN_LABELS = ["12-4 am", "4-8 am", "8 am-12 pm", "12-4 pm", "4-8 pm", "8 pm-12 am"]


def to_xy(lat, lon):
    return (np.asarray(lon) - LON0) * M_PER_DEG_LON, (np.asarray(lat) - LAT0) * M_PER_DEG_LAT


def to_latlon(x, y):
    return LAT0 + np.asarray(y) / M_PER_DEG_LAT, LON0 + np.asarray(x) / M_PER_DEG_LON


def load_crimes(path=CRIME_FILE):
    df = pd.read_csv(path, parse_dates=["date"])
    df["x"], df["y"] = to_xy(df.latitude, df.longitude)
    df["gx"] = (df.x // CELL_M).astype(int).clip(0, NX - 1)
    df["gy"] = (df.y // CELL_M).astype(int).clip(0, NY - 1)
    df["week"] = (df.date - WEEK0).dt.days // 7
    df["weekend"] = (df.date.dt.dayofweek >= 5).astype(int)
    df["win"] = df.date.dt.hour // 4
    df["year"] = df.date.dt.year
    return df


def week_start(w):
    return WEEK0 + pd.Timedelta(weeks=int(w))


def load_streets(pattern=STREET_FILES):
    """City street centerlines -> list of dicts with node ids, class, name, length (m) and xy polyline."""
    rows = []
    for f in sorted(glob.glob(pattern)):
        rows += json.load(open(f))
    segs = []
    for r in rows:
        g = r.get("the_geom")
        if not g or not r.get("fnode_id"):
            continue
        lines = g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]]
        pts = [p[:2] for line in lines for p in line]
        if len(pts) < 2:
            continue
        lon, lat = np.array(pts, float).T
        x, y = to_xy(lat, lon)
        name = " ".join(s for s in (r.get("pre_dir"), r.get("street_nam"), r.get("street_typ")) if s)
        segs.append(dict(u=r["fnode_id"], v=r["tnode_id"], cls=r.get("class", ""), name=name,
                         length=float(np.hypot(np.diff(x), np.diff(y)).sum()), x=x, y=y))
    return segs


def street_cells(segs, step=25.0):
    """Grid cells crossed by streets (our 'city area' mask) and street length per cell by road class."""
    mask = np.zeros((NY, NX), bool)
    length = {k: np.zeros((NY, NX), np.float32) for k in ("arterial", "local", "all")}
    for s in segs:
        x, y = s["x"], s["y"]
        d = np.r_[0, np.cumsum(np.hypot(np.diff(x), np.diff(y)))]
        if d[-1] == 0:
            continue
        t = np.arange(0, d[-1] + step, step).clip(max=d[-1])
        px, py = np.interp(t, d, x), np.interp(t, d, y)
        gx = (px // CELL_M).astype(int).clip(0, NX - 1)
        gy = (py // CELL_M).astype(int).clip(0, NY - 1)
        mask[gy, gx] = True
        kind = "arterial" if s["cls"] in ("2", "3") else "local" if s["cls"] == "4" else None
        np.add.at(length["all"], (gy, gx), step)
        if kind:
            np.add.at(length[kind], (gy, gx), step)
    return mask, length


