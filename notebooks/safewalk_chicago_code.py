"""SafeWalk Chicago: street-crime hotspot prediction and safer walking routes (2015-2024 Chicago data).

All project code in one file, in the order the notebook runs it.
Each section was a separate file; the file name is in the section header."""


# ============================================================================
# FILE: download.py
# ============================================================================
"""Download 10 years of Chicago street crime + the city's street network from the official open-data portal."""
import os, json, time, urllib.parse, urllib.request
import pandas as pd

os.makedirs("data", exist_ok=True); os.makedirs("out", exist_ok=True)
CRIMES = "https://data.cityofchicago.org/resource/ijzp-q8t2.csv"
STREETS = "https://data.cityofchicago.org/resource/pr57-gg9e.json"

WHERE = (
    "year = {year} AND domestic = false AND latitude IS NOT NULL AND ("
    "primary_type in('ROBBERY','BATTERY','ASSAULT','HOMICIDE','CRIMINAL SEXUAL ASSAULT','CRIM SEXUAL ASSAULT','KIDNAPPING') "
    "OR (primary_type='THEFT' AND description in('POCKET-PICKING','PURSE-SNATCHING'))) AND ("
    "location_description in('STREET','SIDEWALK','ALLEY','PARKING LOT / GARAGE (NON RESIDENTIAL)','PARKING LOT/GARAGE(NON.RESID.)',"
    "'PARK PROPERTY','GAS STATION','VACANT LOT / LAND','VACANT LOT/LAND','BRIDGE','HIGHWAY / EXPRESSWAY','HIGHWAY/EXPRESSWAY',"
    "'LAKEFRONT / WATERFRONT / RIVERBANK','LAKEFRONT/WATERFRONT/RIVERBANK') OR location_description like 'CTA%')"
)


def get(url, params, path, tries=5):
    """Download with retries: the portal sometimes drops long-running requests."""
    req = urllib.request.Request(f"{url}?{urllib.parse.urlencode(params)}", headers={"User-Agent": "safewalk-notebook"})
    for k in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                data = r.read()
            open(path, "wb").write(data)
            return
        except Exception as e:
            if k == tries - 1:
                raise
            print(f"  retrying after {type(e).__name__} ({k + 1}/{tries - 1})")
            time.sleep(5 * (k + 1))


crime_file = "data/chicago_street_crime_2015_2024.csv.gz"
if not os.path.exists(crime_file):
    parts = []
    for year in range(2015, 2025):                      # one request per year (~27,000 rows each)
        p = f"data/part_{year}.csv"
        get(CRIMES, {"$select": "id,date,primary_type,description,location_description,arrest,latitude,longitude",
                     "$where": WHERE.format(year=year), "$order": "id", "$limit": 100000}, p)
        d = pd.read_csv(p); os.remove(p)
        parts.append(d); print(f"crimes {year}: {len(d)} rows")
    df = pd.concat(parts, ignore_index=True).drop_duplicates("id")
    df = df[df.latitude.between(41.6, 42.1) & df.longitude.between(-87.95, -87.5)]   # drop mis-geocoded points
    df["primary_type"] = df.primary_type.replace({"CRIM SEXUAL ASSAULT": "CRIMINAL SEXUAL ASSAULT"})
    df.to_csv(crime_file, index=False)
print("street crimes:", len(pd.read_csv(crime_file)))

for off in (0, 30000):
    path = f"data/streets_{off}.json"
    if not os.path.exists(path):
        get(STREETS, {"$select": "fnode_id,tnode_id,the_geom,class,street_nam,street_typ,pre_dir,length,status",
                      "$order": "objectid", "$limit": 30000, "$offset": off}, path)
    print(path, len(json.load(open(path))), "street segments")


# ============================================================================
# FILE: style.py
# ============================================================================
"""Shared plot style: one sequential blue ramp, fixed categorical order, quiet axes."""
import matplotlib as mpl
from matplotlib.colors import LinearSegmentedColormap

SURFACE = "#fcfcfb"
TEXT = "#0b0b0b"
TEXT2 = "#52514e"
GRIDLINE = "#e4e3df"
CAT = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]  # fixed order, never cycled
SEQ = ["#fcfcfb", "#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"]
SEQ_CMAP = LinearSegmentedColormap.from_list("seq_blue", SEQ)


def apply():
    mpl.rcParams.update({
        "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
        "axes.edgecolor": GRIDLINE, "axes.labelcolor": TEXT2, "xtick.color": TEXT2, "ytick.color": TEXT2,
        "text.color": TEXT, "axes.titlecolor": TEXT, "axes.titleweight": "bold", "axes.titlesize": 12,
        "axes.titlelocation": "left", "axes.grid": True, "grid.color": GRIDLINE, "grid.linewidth": 0.8,
        "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
        "lines.linewidth": 2, "legend.frameon": False,
    })


# ============================================================================
# FILE: chi.py
# ============================================================================
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


# ============================================================================
# FILE: hotspot.py
# ============================================================================
"""Weekly hotspot prediction on a 250 m grid for 12 time slots (weekday/weekend x six 4-hour windows).

Everything a prediction for week t uses comes from weeks before t.
"""
import numpy as np, pandas as pd
from scipy.ndimage import gaussian_filter
import chi

N_SLOT = 2 * chi.N_WIN
LOOKBACKS = (1, 4, 13, 52, 156, 260)          # weeks: up to 5 years of history


def count_cube(df, n_weeks):
    C = np.zeros((n_weeks, 2, chi.N_WIN, chi.NY, chi.NX), np.float32)
    ok = df.week.between(0, n_weeks - 1)
    np.add.at(C, (df.week[ok], df.weekend[ok], df.win[ok], df.gy[ok], df.gx[ok]), 1)
    return C


class History:
    """Fast 'incidents per week over the last L weeks' for any target week t, via cumulative sums."""
    def __init__(self, C):
        self.cs = np.concatenate([np.zeros_like(C[:1]), np.cumsum(C, 0, dtype=np.float32)])

    def rate(self, t, L):
        lo = max(t - L, 0)
        return (self.cs[t] - self.cs[lo]) / max(t - lo, 1)

    def window_rate(self, t0, t1):
        """Rate over weeks t0..t1-1 (clipped to available history)."""
        t0, t1 = max(t0, 0), max(t1, 0)
        return (self.cs[t1] - self.cs[t0]) / max(t1 - t0, 1)


def smooth(a, s_space, s_time=0.0):
    """a: (2, 6, NY, NX). Gaussian in space; circular across the six 4-hour windows of the same day type."""
    if s_space == 0 and s_time == 0:
        return a
    return gaussian_filter(a, sigma=(0, s_time, s_space, s_space),
                           mode=("constant", "wrap", "constant", "constant"))


def capture_curve(score, actual, seed=0):
    """Share of actual incidents captured as flagged area grows; ties broken randomly."""
    order = np.lexsort((np.random.default_rng(seed).random(score.size), -score))
    return np.cumsum(actual[order]) / max(actual.sum(), 1)


def evaluate(pred_fn, C, mask, weeks, levels=(0.01, 0.05, 0.2)):
    """pred_fn(t) -> (2, 6, NY, NX) scores. Returns one row per (week, slot) with capture at each level."""
    n = mask.sum()
    rows = []
    for t in weeks:
        P = pred_fn(t)
        for d in range(2):
            for w in range(chi.N_WIN):
                a = C[t, d, w][mask]
                if a.sum() == 0:
                    continue
                cur = capture_curve(P[d, w][mask], a)
                r = dict(week=t, weekend=d, win=w, n=a.sum(), auc=cur.mean())
                for lv in levels:
                    r[f"top{lv*100:g}"] = cur[int(np.ceil(lv * n)) - 1]
                rows.append(r)
    return pd.DataFrame(rows)


def kde_predictor(H, L, s_space, s_time):
    return lambda t: smooth(H.rate(t, L), s_space, s_time)


# ---------------- features for the machine-learning model ----------------
def static_features(street_len):
    xx, yy = np.meshgrid(np.arange(chi.NX), np.arange(chi.NY))
    return {"street_len_all": street_len["all"], "street_len_arterial": street_len["arterial"],
            "street_len_local": street_len["local"], "cell_x": xx.astype(np.float32), "cell_y": yy.astype(np.float32)}


def week_features(H, t, kde_best, static, mask):
    """All features for target week t as (n_slots * n_cells, n_features), plus names."""
    F = {}
    for L in LOOKBACKS:
        r = H.rate(t, L)
        for s in (0, 1, 2):
            F[f"same_slot_{L}w_s{s}"] = smooth(r, s)
        F[f"st_kde_{L}w"] = smooth(r, **kde_best)
    for L in (52, 260):                                     # all times of day and both day types pooled
        allt = H.rate(t, L).sum((0, 1), keepdims=True)
        F[f"any_time_{L}w_s1"] = np.broadcast_to(smooth(allt, 1), (2, chi.N_WIN, chi.NY, chi.NX))
    # same season last year and two years ago (weeks t-54..t-50 and t-106..t-102)
    for k in (1, 2):
        F[f"same_season_{k}y"] = smooth(H.window_rate(t - 52 * k - 2, t - 52 * k + 3), **kde_best)
    names = list(F) + list(static) + ["weekend", "window"]
    cols = []
    for v in F.values():
        cols.append(v[:, :, mask].reshape(-1))
    n_cells = mask.sum()
    for v in static.values():
        cols.append(np.tile(v[mask], N_SLOT))
    d_idx, w_idx = np.meshgrid(np.arange(2), np.arange(chi.N_WIN), indexing="ij")
    cols.append(np.repeat(d_idx.ravel(), n_cells)); cols.append(np.repeat(w_idx.ravel(), n_cells))
    return np.column_stack(cols).astype(np.float32), names


def week_target(C, t, mask):
    return C[t][:, :, mask].reshape(-1)


# ============================================================================
# FILE: routing.py
# ============================================================================
"""Walking graph from Chicago's street centerlines + risk-weighted routing."""
import numpy as np, networkx as nx
import chi

WALKABLE = {"2", "3", "4", "5", "7", "S"}   # drop expressways (1), ramps (9), runways (99), rivers, extensions


def build_graph(segs):
    G = nx.Graph()
    for i, s in enumerate(segs):
        if s["cls"] not in WALKABLE or s["u"] == s["v"] or s["length"] < 1:
            continue
        G.add_node(s["u"], x=s["x"][0], y=s["y"][0])
        G.add_node(s["v"], x=s["x"][-1], y=s["y"][-1])
        if G.has_edge(s["u"], s["v"]) and G[s["u"]][s["v"]]["length"] <= s["length"]:
            continue
        G.add_edge(s["u"], s["v"], length=s["length"], seg=i, name=s["name"])
    biggest = max(nx.connected_components(G), key=len)
    return G.subgraph(biggest).copy()


def sample_along(x, y, step=25.0):
    d = np.r_[0, np.cumsum(np.hypot(np.diff(x), np.diff(y)))]
    t = np.arange(step / 2, max(d[-1], step / 2 + 1e-6), step).clip(max=d[-1])
    return np.interp(t, d, x), np.interp(t, d, y)


def edge_risk(G, segs, risk):
    """risk: (2, 6, NY, NX) predicted incidents per hour per cell. Sets edge['risk'] = (12,) mean along the edge."""
    for u, v, e in G.edges(data=True):
        s = segs[e["seg"]]
        px, py = sample_along(s["x"], s["y"])
        gx = (px // chi.CELL_M).astype(int).clip(0, chi.NX - 1)
        gy = (py // chi.CELL_M).astype(int).clip(0, chi.NY - 1)
        e["risk"] = risk[:, :, gy, gx].mean(-1).reshape(-1).astype(np.float32)
        e["cells"] = np.unique(gy * chi.NX + gx)


def set_weights(G, slot, lam, ref):
    """cost = length x (1 + lam x risk / ref). lam = 0 gives the shortest route."""
    for u, v, e in G.edges(data=True):
        e["w"] = e["length"] * (1 + lam * e["risk"][slot] / ref)


def route(G, a, b):
    return nx.shortest_path(G, a, b, weight="w")


def path_xy(G, segs, path):
    xs, ys = [], []
    for u, v in zip(path[:-1], path[1:]):
        s = segs[G[u][v]["seg"]]
        x, y = s["x"], s["y"]
        if np.hypot(x[0] - G.nodes[u]["x"], y[0] - G.nodes[u]["y"]) > np.hypot(x[-1] - G.nodes[u]["x"], y[-1] - G.nodes[u]["y"]):
            x, y = x[::-1], y[::-1]
        xs.append(x); ys.append(y)
    return np.concatenate(xs), np.concatenate(ys)


def path_stats(G, path, slot):
    L = sum(G[u][v]["length"] for u, v in zip(path[:-1], path[1:]))
    expo = sum(G[u][v]["length"] * G[u][v]["risk"][slot] for u, v in zip(path[:-1], path[1:]))
    return L, expo


# ============================================================================
# FILE: eda.py
# ============================================================================
import numpy as np, pandas as pd, matplotlib.pyplot as plt
import chi, style
style.apply()

df = chi.load_crimes()
segs = chi.load_streets()

fig = plt.figure(figsize=(16, 9.5))
gs = fig.add_gridspec(2, 3, width_ratios=[1.35, 1.1, 1.1], height_ratios=[1, 1])

# where: density on the real map, street network as basemap
ax = fig.add_subplot(gs[:, 0])
for s in segs:
    if s["cls"] in ("1", "2", "3"):
        ax.plot(s["x"] / 1000, s["y"] / 1000, color="#c9c8c2", lw=0.5, zorder=1)
hb = ax.hexbin(df.x / 1000, df.y / 1000, gridsize=(60, 70), bins="log", cmap=style.SEQ_CMAP,
               mincnt=5, linewidths=0, alpha=0.9, zorder=2)
ax.set_aspect("equal"); ax.grid(False)
ax.set_title("Where: street crime, 2015-2024")
ax.set_xlabel("km east"); ax.set_ylabel("km north")
fig.colorbar(hb, ax=ax, orientation="horizontal", shrink=0.7, pad=0.07, label="incidents per ~600 m hexagon (log scale)")

# when: hour x day-of-week heatmap
ax = fig.add_subplot(gs[0, 1:])
hd = df.groupby([df.date.dt.dayofweek, df.date.dt.hour]).size().unstack(fill_value=0) / 10  # per year
im = ax.imshow(hd.values, aspect="auto", cmap=style.SEQ_CMAP, origin="upper")
ax.set_yticks(range(7), ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"])
ax.set_xticks(range(0, 24, 2), [f"{h}:00" for h in range(0, 24, 2)])
ax.grid(False); ax.set_title("When: average incidents per year by day and hour")
fig.colorbar(im, ax=ax, shrink=0.9, label="incidents per year")

# trend: monthly totals by type
ax = fig.add_subplot(gs[1, 1:])
m = df.set_index("date").groupby("primary_type").resample("MS").size().unstack(0).fillna(0)
top = ["BATTERY", "ROBBERY", "ASSAULT"]
m["OTHER"] = m.drop(columns=top).sum(axis=1)
for col, c in zip(top + ["OTHER"], style.CAT):
    ax.plot(m.index, m[col], color=c, label=col.title() if col != "OTHER" else "Other (theft from person, sexual assault, homicide, kidnapping)")
ax.set_title("Over time: monthly incidents by type")
ax.set_ylabel("incidents per month"); ax.legend(loc="upper left", bbox_to_anchor=(0, 1.0), fontsize=9, ncol=4)
ax.set_ylim(0, 1500)
ax.axvspan(pd.Timestamp("2024-01-01"), pd.Timestamp("2024-12-31"), color="#ece9df", zorder=0)
ax.text(pd.Timestamp("2024-01-20"), ax.get_ylim()[1] * 0.93, "test year", color=style.TEXT2, fontsize=9)

fig.tight_layout(); fig.savefig("out/01_eda.png", dpi=150)

# numbers quoted in the write-up
h = df.date.dt.hour
print("rows", len(df), "years", df.year.min(), df.year.max())
print("per year", df.groupby("year").size().to_dict())
print("share 20:00-04:00:", round(((h >= 20) | (h < 4)).mean() * 100, 1))
print("weekend share of incidents:", round(df.weekend.mean() * 100, 1), "(2/7 = 28.6 expected if flat)")
wk_night = df[(df.weekend == 1) & ((h < 4))].shape[0] / 2 / 522; wd_night = df[(df.weekend == 0) & (h < 4)].shape[0] / 5 / 522
print("12-4am incidents per day: weekend", round(wk_night, 1), "weekday", round(wd_night, 1))
print("busiest hour", h.value_counts().idxmax(), "quietest", h.value_counts().idxmin())
print(df.location_description.value_counts(normalize=True).head(4).round(3).to_dict())
cell = df.groupby(["gx", "gy"]).size().sort_values(ascending=False)
print("cells with any crime", len(cell), "; top 5% of those cells hold", round(cell.head(int(len(cell) * .05)).sum() / len(df) * 100, 1), "% of crime")


# ============================================================================
# FILE: run_kde.py
# ============================================================================
"""Step 1: KDE baselines. Tune bandwidths on validation weeks (late 2023) and test on 2024.
Also answers: does a longer history (1 vs 5 years) help?"""
import numpy as np, pandas as pd, json, pickle
import chi, hotspot as H_

df = chi.load_crimes()
segs = chi.load_streets()
mask, street_len = chi.street_cells(segs)
n_weeks = int(df.week.max())                     # drop the last partial week (30-31 Dec 2024)
C = H_.count_cube(df, n_weeks)
H = H_.History(C)

wk = lambda d: int((pd.Timestamp(d) - chi.WEEK0).days // 7)
test = list(range(wk("2024-01-01"), n_weeks))
valid = list(range(wk("2023-10-02"), wk("2024-01-01")))
train = list(range(wk("2021-01-04"), wk("2023-10-02")))
print(f"city cells {mask.sum()} (~{mask.sum() * 0.0625:.0f} km2); weeks: train {len(train)}, valid {len(valid)}, test {len(test)}")
print("share of 2024 crimes inside city cells:", round(C[test][..., mask].sum() / C[test].sum(), 4))

rows = []
for L in (52, 156, 260):
    for ss in (0.25, 0.5, 0.75, 1, 2):
        for st in (0, 1, 2, 3, 5):
            r = H_.evaluate(H_.kde_predictor(H, L, ss, st), C, mask, valid)
            rows.append(dict(L=L, s_space=ss, s_time=st, val_auc=r.auc.mean(), val_top5=r.top5.mean()))
tune = pd.DataFrame(rows).sort_values("val_auc", ascending=False)
print(tune.head(6).round(4).to_string())
best_per_L = tune.groupby("L").head(1).set_index("L")
print(best_per_L.round(4))

# test-set results for each history length with its own tuned bandwidth, plus naive baselines
res = {"Last week (as-is)": H_.evaluate(lambda t: H.rate(t, 1), C, mask, test)}
for L, r in best_per_L.iterrows():
    res[f"Tuned KDE, {L // 52} yr history"] = H_.evaluate(H_.kde_predictor(H, L, r.s_space, r.s_time), C, mask, test)
summ = pd.DataFrame({k: v[["top1", "top5", "top20", "auc"]].mean() for k, v in res.items()}).T
print(summ.round(3))

best = tune.iloc[0]
cfg = dict(n_weeks=n_weeks, test=test, valid=valid, train=train,
           kde_best=dict(s_space=float(best.s_space), s_time=float(best.s_time)), kde_best_L=int(best.L),
           city_cells=int(mask.sum()))
json.dump(cfg, open("out/config.json", "w"), indent=1)
tune.to_csv("out/kde_tuning.csv", index=False)
pickle.dump(res, open("out/kde_results.pkl", "wb"))
np.save("out/mask.npy", mask); pickle.dump(street_len, open("out/street_len.pkl", "wb"))


# ============================================================================
# FILE: run_model.py
# ============================================================================
"""Step 2: LightGBM hotspot model. Train on 2021-Sep 2023 targets, early-stop on Oct-Dec 2023, test on 2024."""
import numpy as np, pandas as pd, json, pickle, lightgbm as lgb
import chi, hotspot as H_

cfg = json.load(open("out/config.json"))
df = chi.load_crimes()
mask = np.load("out/mask.npy"); street_len = pickle.load(open("out/street_len.pkl", "rb"))
C = H_.count_cube(df, cfg["n_weeks"]); H = H_.History(C)
static = H_.static_features(street_len)
kde_best = cfg["kde_best"]
rng = np.random.default_rng(0)
KEEP_ZERO = 0.08


def build(weeks, subsample):
    Xs, ys, ws = [], [], []
    for t in weeks:
        X, names = H_.week_features(H, t, kde_best, static, mask)
        y = H_.week_target(C, t, mask)
        if subsample:
            keep = (y > 0) | (rng.random(y.size) < KEEP_ZERO)
            X, y = X[keep], y[keep]
            ws.append(np.where(y > 0, 1.0, 1 / KEEP_ZERO))
        Xs.append(X); ys.append(y)
    return np.vstack(Xs), np.concatenate(ys), (np.concatenate(ws) if subsample else None), names


Xtr, ytr, wtr, names = build(cfg["train"], True)
Xva, yva, _, _ = build(cfg["valid"], False)
print("train rows", len(ytr), "valid rows", len(yva), "features", len(names))
params = dict(objective="poisson", learning_rate=0.05, num_leaves=63, min_data_in_leaf=300,
              feature_fraction=0.7, bagging_fraction=0.8, bagging_freq=1, lambda_l2=2.0, verbose=-1, seed=0, num_threads=2)
m = lgb.train(params, lgb.Dataset(Xtr, ytr, weight=wtr, feature_name=names), 3000,
              valid_sets=[lgb.Dataset(Xva, yva, feature_name=names)],
              callbacks=[lgb.early_stopping(150, verbose=False)])
best_iter = m.best_iteration
print("best iteration", best_iter)

# refit on train + valid weeks with the chosen number of rounds
Xa, ya, wa, _ = build(cfg["train"] + cfg["valid"], True)
m = lgb.train(params, lgb.Dataset(Xa, ya, weight=wa, feature_name=names), best_iter)
m.save_model("out/lgbm_chicago.txt")
del Xtr, Xva, Xa

n_cells = mask.sum()
preds = {}
def lgb_pred(t):
    X, _ = H_.week_features(H, t, kde_best, static, mask)
    p = m.predict(X).reshape(2, chi.N_WIN, n_cells)
    out = np.zeros((2, chi.N_WIN, chi.NY, chi.NX), np.float32); out[:, :, mask] = p
    preds[t] = p.astype(np.float32)
    return out

res = pickle.load(open("out/kde_results.pkl", "rb"))
res["LightGBM (ours)"] = H_.evaluate(lgb_pred, C, mask, cfg["test"])
np.save("out/pred_2024.npy", np.stack([preds[t] for t in cfg["test"]]))   # (weeks, 2, 6, cells)
pickle.dump(res, open("out/all_results.pkl", "wb"))

summ = pd.DataFrame({k: v[["top1", "top5", "top20", "auc"]].mean() for k, v in res.items()}).T
print(summ.round(3)); summ.to_csv("out/summary.csv")
kde_name = "Tuned KDE, 5 yr history"
for col in ("top1", "top5", "top20", "auc"):
    a = res["LightGBM (ours)"].groupby("week")[col].mean(); b = res[kde_name].groupby("week")[col].mean()
    print(f"{col}: LightGBM better in {(a > b).sum()}/{len(a)} test weeks, mean gain {100 * (a - b).mean():+.2f} pts")
imp = pd.Series(m.feature_importance("gain"), index=names).sort_values(ascending=False)
imp.to_csv("out/feature_importance.csv"); print((imp / imp.sum() * 100).head(10).round(1))


# ============================================================================
# FILE: results_fig.py
# ============================================================================
"""Model comparison on 2024 + one predicted hotspot map with that week's real crimes."""
import numpy as np, pandas as pd, json, pickle, matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap
from matplotlib.patches import Patch
import chi, hotspot as H_, style
style.apply()

cfg = json.load(open("out/config.json")); mask = np.load("out/mask.npy")
df = chi.load_crimes(); C = H_.count_cube(df, cfg["n_weeks"]); H = H_.History(C)
pred = np.load("out/pred_2024.npy")                     # (weeks, 2, 6, cells)
test = cfg["test"]; n = mask.sum()
kb = cfg["kde_best"]

models = {"Last week (as-is)": lambda t: H.rate(t, 1)[:, :, mask],
          "Tuned KDE, 1 yr history": lambda t: H_.smooth(H.rate(t, 52), 0.5, 2)[:, :, mask],
          "Tuned KDE, 5 yr history": lambda t: H_.smooth(H.rate(t, 260), **kb)[:, :, mask],
          "LightGBM (ours)": lambda t: pred[test.index(t)]}
fig, ax = plt.subplots(figsize=(8, 5))
x = np.arange(1, n + 1) / n * 100
for (name, f), col in zip(models.items(), style.CAT):
    curves = []
    for t in test:
        P = f(t)
        for d in range(2):
            for w in range(chi.N_WIN):
                a = C[t, d, w][mask]
                if a.sum():
                    curves.append(H_.capture_curve(P[d, w], a))
    y = np.mean(curves, 0) * 100
    ax.plot(x, y, color=col, label=name)
    if name in ("LightGBM (ours)", "Last week (as-is)"):
        i = np.searchsorted(x, 20); ax.annotate(f"{y[i]:.0f}%", (20, y[i]), xytext=(6, -12), textcoords="offset points", color=style.TEXT2, fontsize=9)
ax.axvline(20, color=style.TEXT2, lw=1, ls=(0, (3, 3))); ax.text(20.6, 3, "riskiest 20% of city", color=style.TEXT2, fontsize=9)
ax.text(21.5, 96, "LightGBM and 5-year KDE lines overlap", color=style.TEXT2, fontsize=9)
ax.set_xlim(0, 60); ax.set_ylim(0, 100)
ax.set_xlabel("% of city area flagged (riskiest 250 m cells first)"); ax.set_ylabel("% of next week's street crimes captured")
ax.set_title("Hotspot models on all 52 weeks of 2024 (never seen in training)"); ax.legend(loc="lower right")
fig.tight_layout(); fig.savefig("out/02_capture_curves.png", dpi=150)

# hotspot map: a summer weekend night in 2024
wk = int((pd.Timestamp("2024-07-15") - chi.WEEK0).days // 7); d, w = 1, 0
p = np.full((chi.NY, chi.NX), np.nan); p[mask] = pred[test.index(wk)][d, w]
v = p[mask]; t5, t20 = np.quantile(v, 0.95), np.quantile(v, 0.80)
cls = np.full(p.shape, np.nan); cls[mask] = 0; cls[mask & (p >= t20)] = 1; cls[mask & (p >= t5)] = 2
ev = df[(df.week == wk) & (df.weekend == d) & (df.win == w)]
in5 = (cls[ev.gy, ev.gx] == 2).mean() * 100; in20 = (cls[ev.gy, ev.gx] >= 1).mean() * 100
fig, ax = plt.subplots(figsize=(7, 8))
ax.imshow(cls, origin="lower", extent=(0, chi.NX * chi.CELL_M / 1000, 0, chi.NY * chi.CELL_M / 1000),
          cmap=ListedColormap(["#e9eef5", "#86b6ef", "#184f95"]), vmin=0, vmax=2, interpolation="nearest")
ax.scatter(ev.x / 1000, ev.y / 1000, s=14, color=style.CAT[1], edgecolor=style.SURFACE, linewidth=0.6, zorder=3)
ax.legend(handles=[Patch(color="#184f95", label=f"riskiest 5% of city  ({in5:.0f}% of crimes)"),
                   Patch(color="#86b6ef", label=f"riskiest 5-20%  (cumulative {in20:.0f}%)"),
                   Patch(color="#e9eef5", label="rest of city"),
                   plt.Line2D([], [], marker="o", ls="", color=style.CAT[1], label=f"actual street crimes ({len(ev)})")],
          loc="lower left", fontsize=9)
ax.set_title(f"Forecast vs reality: weekend 12-4 am, week of {chi.week_start(wk):%-d %b %Y}")
ax.set_xlabel("km east"); ax.set_ylabel("km north"); ax.grid(False)
ax.set_xlim(5, 37); ax.set_ylim(0, 44)
fig.tight_layout(); fig.savefig("out/03_hotspot_map.png", dpi=150)
print("map week", chi.week_start(wk).date(), "crimes", len(ev), "in top5", round(in5, 1), "in top20", round(in20, 1))


# ============================================================================
# FILE: route_eval.py
# ============================================================================
"""Step 3: do 'safest' routes (risk predicted only from past data) actually pass fewer real 2024 crimes?"""
import numpy as np, pandas as pd, json, pickle, time
from scipy.spatial import cKDTree
import chi, hotspot as H_, routing as R

cfg = json.load(open("out/config.json"))
mask = np.load("out/mask.npy")
segs = chi.load_streets()
G = R.build_graph(segs)
print("walking graph:", G.number_of_nodes(), "junctions,", G.number_of_edges(), "street segments,",
      round(sum(e["length"] for _, _, e in G.edges(data=True)) / 1000), "km")

# risk map built ONLY from 2015-2023 data: the model's forecast for the first week of 2024, per hour per cell
pred = np.load("out/pred_2024.npy")[0]                             # (2, 6, cells), incidents per week
hours = np.array([20.0, 8.0])[:, None, None]                       # weekday slot = 5 days x 4 h; weekend = 2 x 4 h
risk = np.zeros((2, chi.N_WIN, chi.NY, chi.NX), np.float32); risk[:, :, mask] = pred / hours
R.edge_risk(G, segs, risk)

df = chi.load_crimes(); d24 = df[df.year == 2024]
nodes = np.array(list(G.nodes)); xy = np.array([[G.nodes[n]["x"], G.nodes[n]["y"]] for n in nodes])
rng = np.random.default_rng(1)
pairs = []
while len(pairs) < 250:
    i, j = rng.integers(len(nodes), size=2)
    if 1000 <= np.hypot(*(xy[i] - xy[j])) <= 4000:
        pairs.append((nodes[i], nodes[j]))

SLOTS = {"Weekday 8 pm-12 am": 5, "Weekend 12-4 am": 6 + 0}
LAMS = (0, 0.25, 0.5, 1, 2, 4, 8)
rows = []
t0 = time.time()
for sname, slot in SLOTS.items():
    d, w = divmod(slot, 6)
    pts = d24[(d24.weekend == d) & (d24.win == w)][["x", "y"]].to_numpy()
    tree = cKDTree(pts)
    lens = np.array([e["length"] for _, _, e in G.edges(data=True)])
    rk = np.array([e["risk"][slot] for _, _, e in G.edges(data=True)])
    ref = (lens * rk).sum() / lens.sum()                           # city-average risk per metre of street
    variants = [("Risk-aware (ours)", lam) for lam in LAMS] + \
               [("Avoid main roads", mu) for mu in (0.25, 0.5, 1, 2)] + [("Random detour", a) for a in (0.3, 1, 2, 4)]
    noise = np.random.default_rng(7).random(G.number_of_edges())
    for method, k_ in variants:
        if method == "Risk-aware (ours)":
            R.set_weights(G, slot, k_, ref)
        else:
            for (u, v, e), z in zip(G.edges(data=True), noise):
                bump = (segs[e["seg"]]["cls"] in ("2", "3")) if method == "Avoid main roads" else z
                e["w"] = e["length"] * (1 + k_ * bump)
        for k, (a, b) in enumerate(pairs):
            p = R.route(G, a, b)
            L, expo = R.path_stats(G, p, slot)
            px, py = R.path_xy(G, segs, p)
            sx, sy = R.sample_along(px, py, 20)
            near = set().union(*tree.query_ball_point(np.c_[sx, sy], 50)) if len(sx) else set()
            rows.append(dict(slot=sname, method=method, lam=k_, pair=k, length=L, exposure=expo, crimes_2024=len(near)))
    print(sname, "done", round(time.time() - t0), "s")

r = pd.DataFrame(rows)
r.to_csv("out/route_eval.csv", index=False)
base = r[(r.method == "Risk-aware (ours)") & (r.lam == 0)].set_index(["slot", "pair"])
r = r.join(base[["length", "exposure", "crimes_2024"]], on=["slot", "pair"], rsuffix="_short")
r["extra_dist_pct"] = (r.length / r.length_short - 1) * 100
r["changed"] = r.extra_dist_pct > 0.5
summ = r.groupby(["slot", "method", "lam"]).agg(
    trips_rerouted_pct=("changed", lambda s: s.mean() * 100),
    extra_distance_pct=("extra_dist_pct", "mean"),
    km=("length", lambda s: s.sum() / 1000),
    crimes_2024=("crimes_2024", "sum"),
    crimes_2024_short=("crimes_2024_short", "sum"),
    predicted_exposure=("exposure", "sum"), predicted_exposure_short=("exposure_short", "sum"))
summ["predicted_risk_cut_pct"] = (1 - summ.predicted_exposure / summ.predicted_exposure_short) * 100
summ["real_2024_crimes_cut_pct"] = (1 - summ.crimes_2024 / summ.crimes_2024_short) * 100
summ["real_crimes_per_km"] = summ.crimes_2024 / summ.km
cols = ["trips_rerouted_pct", "extra_distance_pct", "predicted_risk_cut_pct", "real_2024_crimes_cut_pct", "real_crimes_per_km"]
print(summ[cols].round(1).to_string())
summ.to_csv("out/route_summary.csv")
pickle.dump(dict(pairs=pairs), open("out/route_pairs.pkl", "wb"))


# ============================================================================
# FILE: route_fig.py
# ============================================================================
"""Route trade-off chart + bootstrap confidence intervals for the headline numbers."""
import numpy as np, pandas as pd, matplotlib.pyplot as plt
import style
style.apply()

r = pd.read_csv("out/route_eval.csv")
base = r[(r.method == "Risk-aware (ours)") & (r.lam == 0)].set_index(["slot", "pair"])
r = r.join(base[["length", "crimes_2024"]], on=["slot", "pair"], rsuffix="_short")
rng = np.random.default_rng(0)


def stats(g, boot=2000):
    """Extra distance % (mean over trips) and real-crime cut % (ratio of totals), with 95% bootstrap CIs over trips."""
    L, Ls, c, cs = (g[k].to_numpy() for k in ("length", "length_short", "crimes_2024", "crimes_2024_short"))
    ext = (L / Ls - 1) * 100
    cut = (1 - c.sum() / cs.sum()) * 100
    idx = rng.integers(len(g), size=(boot, len(g)))
    cut_b = (1 - c[idx].sum(1) / cs[idx].sum(1)) * 100
    return ext.mean(), cut, *np.percentile(cut_b, [2.5, 97.5])


rows = []
for (slot, method, lam), g in r.groupby(["slot", "method", "lam"]):
    e, c, lo, hi = stats(g)
    rows.append(dict(slot=slot, method=method, lam=lam, extra_dist=e, crime_cut=c, ci_lo=lo, ci_hi=hi))
s = pd.DataFrame(rows)
s.to_csv("out/route_tradeoff.csv", index=False)
print(s.round(1).to_string())

order = ["Risk-aware (ours)", "Avoid main roads", "Random detour"]
colors = dict(zip(order, style.CAT[:3]))
fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=True)
for ax, slot in zip(axes, ["Weekday 8 pm-12 am", "Weekend 12-4 am"]):
    for m in order:
        d = s[(s.slot == slot) & ((s.method == m) | ((s.method == "Risk-aware (ours)") & (s.lam == 0)))].sort_values("extra_dist")
        ax.plot(d.extra_dist, d.crime_cut, "-o", color=colors[m], ms=6, label=m,
                markeredgecolor=style.SURFACE, markeredgewidth=1.5)
        ax.fill_between(d.extra_dist, d.ci_lo, d.ci_hi, color=colors[m], alpha=0.12, linewidth=0)
    pick = s[(s.slot == slot) & (s.method == "Risk-aware (ours)") & (s.lam == 0.5)].iloc[0]
    ax.annotate(f"+{pick.extra_dist:.0f}% distance\n{pick.crime_cut:.0f}% fewer crimes", (pick.extra_dist, pick.crime_cut),
                xytext=(30, -50), textcoords="offset points", color=style.TEXT2, fontsize=9,
                arrowprops=dict(arrowstyle="-", color=style.TEXT2))
    ax.axhline(0, color=style.TEXT2, lw=0.8)
    ax.set_title(slot); ax.set_xlabel("extra walking distance vs shortest route (%)")
    ax.set_xlim(-0.5, 20.5); ax.set_ylim(-10, 70)
axes[0].set_ylabel("fewer real 2024 crimes within 50 m of route (%)")
axes[0].legend(loc="upper left")
fig.suptitle("Safer routes on 250 random 1-4 km walks: risk map from 2015-2023 data, checked against 2024 crimes",
             x=0.01, ha="left", fontsize=11, color=style.TEXT2)
fig.tight_layout(); fig.savefig("out/04_route_tradeoff.png", dpi=150)


# ============================================================================
# FILE: export_app.py
# ============================================================================
"""Step 4: package the walking graph + next-week risk forecast for the in-browser demo."""
import numpy as np, json, base64, lightgbm as lgb, pickle, networkx as nx
import chi, hotspot as H_, routing as R

cfg = json.load(open("out/config.json"))
mask = np.load("out/mask.npy"); street_len = pickle.load(open("out/street_len.pkl", "rb"))
df = chi.load_crimes()
C = H_.count_cube(df, cfg["n_weeks"]); H = H_.History(C)
m = lgb.Booster(model_file="out/lgbm_chicago.txt")

# forecast for the week after the data ends, using all 2015-2024 history
t = cfg["n_weeks"]
X, _ = H_.week_features(H, t, cfg["kde_best"], H_.static_features(street_len), mask)
p = m.predict(X).reshape(2, chi.N_WIN, -1) / np.array([20.0, 8.0])[:, None, None]    # incidents per hour
risk = np.zeros((2, chi.N_WIN, chi.NY, chi.NX), np.float32); risk[:, :, mask] = p
week_label = chi.week_start(t).strftime("%-d %b %Y")
print("forecast week starting", week_label)

segs = chi.load_streets()
G = R.build_graph(segs)
R.edge_risk(G, segs, risk)
nodes = list(G.nodes); idx = {n: i for i, n in enumerate(nodes)}
edges = list(G.edges(data=True))
lens = np.array([e["length"] for _, _, e in edges])
rk = np.stack([e["risk"] for _, _, e in edges])                    # (E, 12)
ref = (lens[:, None] * rk).sum(0) / lens.sum()                      # city average per slot
rel = rk / ref                                                       # 1.0 = city-average risk for that time slot
q = np.clip(np.round(rel * 10), 0, 65535).astype(np.uint16)        # stored in tenths of the city average

# length-weighted thresholds per slot: top 20% / 5% / 1% of street length
thr = {}
for k, frac in (("elevated", 0.20), ("high", 0.05), ("very_high", 0.01)):
    vals = []
    for s in range(12):
        o = np.argsort(-q[:, s].astype(np.int64)); cl = np.cumsum(lens[o]) / lens.sum()
        vals.append(int(q[o[np.searchsorted(cl, frac)], s]))
    thr[k] = vals
print("thresholds (x city avg):", {k: [v / 10 for v in vs] for k, vs in thr.items()})


def rdp(x, y, eps=4.0):
    """Douglas-Peucker simplification of a polyline (metres)."""
    if len(x) <= 2:
        return [0, len(x) - 1]
    keep = np.zeros(len(x), bool); keep[[0, -1]] = True
    stack = [(0, len(x) - 1)]
    while stack:
        a, b = stack.pop()
        dx, dy = x[b] - x[a], y[b] - y[a]; n = np.hypot(dx, dy) or 1
        d = np.abs(dy * (x[a + 1:b] - x[a]) - dx * (y[a + 1:b] - y[a])) / n
        if len(d) and d.max() > eps:
            i = a + 1 + int(d.argmax()); keep[i] = True; stack += [(a, i), (i, b)]
    return list(np.nonzero(keep)[0])


names = sorted({e["name"] for _, _, e in edges}); name_idx = {n: i for i, n in enumerate(names)}
nxy = np.array([[G.nodes[n]["x"], G.nodes[n]["y"]] for n in nodes]).round().astype(np.int32)
eu = np.array([idx[u] for u, _, _ in edges], np.uint32); ev = np.array([idx[v] for _, v, _ in edges], np.uint32)
mid_off, mid_pts = [0], []
for u, v, e in edges:
    s = segs[e["seg"]]; x, y = s["x"], s["y"]
    if np.hypot(x[0] - G.nodes[u]["x"], y[0] - G.nodes[u]["y"]) > np.hypot(x[-1] - G.nodes[u]["x"], y[-1] - G.nodes[u]["y"]):
        x, y = x[::-1], y[::-1]
    k = rdp(x, y)[1:-1]
    mid_pts += [[x[i], y[i]] for i in k]; mid_off.append(len(mid_pts))
mid_pts = np.array(mid_pts or [[0, 0]]).round().astype(np.int32)

b64 = lambda a: base64.b64encode(np.ascontiguousarray(a).tobytes()).decode()

# a default trip where the safer route clearly differs: weekday 8 pm-12 am, balanced setting
slot = 5
for u, v, e in G.edges(data=True):
    e["w0"] = e["length"]; e["w1"] = e["length"] * (1 + 0.5 * e["risk"][slot] / ref[slot])
rng = np.random.default_rng(3); best = None
cand = [n for n in nodes if 12000 < G.nodes[n]["y"] < 30000 and 14000 < G.nodes[n]["x"] < 30000]
for _ in range(400):
    a, b = rng.choice(len(cand), 2, replace=False); a, b = cand[a], cand[b]
    dd = np.hypot(G.nodes[a]["x"] - G.nodes[b]["x"], G.nodes[a]["y"] - G.nodes[b]["y"])
    if not 2200 < dd < 3200:
        continue
    p0 = nx.shortest_path(G, a, b, "w0"); p1 = nx.shortest_path(G, a, b, "w1")
    L0, E0 = R.path_stats(G, p0, slot); L1, E1 = R.path_stats(G, p1, slot)
    ext, cut = L1 / L0 - 1, 1 - E1 / E0
    score = cut - 2 * max(ext - 0.06, 0)
    if ext < 0.09 and (best is None or score > best[0]):
        best = (score, a, b, ext, cut)
print("default trip: extra %.1f%%, predicted risk cut %.0f%%" % (best[3] * 100, best[4] * 100))

data = dict(
    week=week_label, cell=chi.CELL_M, lon0=chi.LON0, lat0=chi.LAT0, mlat=chi.M_PER_DEG_LAT, mlon=chi.M_PER_DEG_LON,
    nodes=b64(nxy), eu=b64(eu), ev=b64(ev), len=b64(np.round(lens).astype(np.uint16).clip(1)),
    risk=b64(q), mid_off=b64(np.array(mid_off, np.uint32)), mid_pts=b64(mid_pts),
    name_idx=b64(np.array([name_idx[e["name"]] for _, _, e in edges], np.uint16)), names=names,
    thr=thr, n_nodes=len(nodes), n_edges=len(edges), start=idx[best[1]], end=idx[best[2]],
)
s = json.dumps(data, separators=(",", ":"))
open("out/app_data.json", "w").write(s)
print("nodes", len(nodes), "edges", len(edges), "mid points", len(mid_pts), "payload MB", round(len(s) / 1e6, 2))
