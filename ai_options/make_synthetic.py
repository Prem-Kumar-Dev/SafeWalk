"""Test harness only: fake Chicago-shaped crime + street files so the pipeline can run without the portal.
Stable hotspots + slowly drifting local changes + near-repeat effect + season + time-of-day profile."""
import numpy as np, pandas as pd, json, os
from scipy.ndimage import gaussian_filter
import chi

rng = np.random.default_rng(42)
os.makedirs("data", exist_ok=True); os.makedirs("out", exist_ok=True)
NY, NX = chi.NY, chi.NX
yy, xx = np.mgrid[0:NY, 0:NX]
city = ((xx - 80) / 55) ** 2 + ((yy - 95) / 80) ** 2 < 1

# streets: grid of lines every 2 cells, arterials every 8
segs, nid = [], 0
def node(x, y):
    return f"{int(x)}_{int(y)}"
for gy in range(0, NY, 2):
    for gx in range(0, NX - 2, 2):
        if city[gy, gx] and city[gy, gx + 2]:
            segs.append((gx, gy, gx + 2, gy, "2" if gy % 8 == 0 else "4"))
for gx in range(0, NX, 2):
    for gy in range(0, NY - 2, 2):
        if city[gy, gx] and city[gy + 2, gx]:
            segs.append((gx, gy, gx, gy + 2, "2" if gx % 8 == 0 else "4"))
rows = []
for x0, y0, x1, y1, cls in segs:
    lat, lon = chi.to_latlon(np.array([x0, x1]) * 250 + 125, np.array([y0, y1]) * 250 + 125)
    rows.append(dict(fnode_id=node(x0, y0), tnode_id=node(x1, y1), class_=cls, street_nam=f"S{y0}", street_typ="ST",
                     the_geom=dict(type="LineString", coordinates=[[lon[0], lat[0]], [lon[1], lat[1]]])))
for r in rows:
    r["class"] = r.pop("class_")
json.dump(rows[:len(rows) // 2], open("data/streets_0.json", "w"))
json.dump(rows[len(rows) // 2:], open("data/streets_30000.json", "w"))

art = np.zeros((NY, NX)); art[::8, :] = 1; art[:, ::8] = 1
f = gaussian_filter(rng.normal(size=(NY, NX)), 2); base = np.exp(1.3 * f / f.std() + 0.5 * art) * city
base *= 520 / base.sum() / 12                                   # ~520 incidents per week overall
slot = np.array([1.4, 0.4, 0.7, 1.1, 1.3, 1.3, 2.2, 0.5, 0.6, 0.9, 1.2, 1.6]); slot *= 12 / slot.sum()
slot_shift = gaussian_filter(rng.normal(size=(12, NY, NX)), (0, 3, 3)) * 4   # places with their own time-of-day mix
n_weeks = 522
drift = np.zeros((NY, NX)); prev = np.zeros((12, NY, NX))
ev = []
for w in range(n_weeks):
    if w % 4 == 0:
        drift = 0.97 * drift + 0.25 * gaussian_filter(rng.normal(size=(NY, NX)), 4) * 10
    season = 1 + 0.3 * np.sin(2 * np.pi * (w - 13) / 52) - (0.15 if 270 <= w <= 340 else 0)
    lam = base * np.exp(drift + slot_shift) * slot[:, None, None] * season
    lam = lam + 0.6 * gaussian_filter(prev, (0, 1, 1)) * lam / (lam + 0.05)   # near-repeat
    n = rng.poisson(lam)
    prev = n.astype(float)
    s, gy, gx = np.nonzero(n)
    k = n[s, gy, gx]
    s, gy, gx = np.repeat(s, k), np.repeat(gy, k), np.repeat(gx, k)
    weekend, win = s // 6, s % 6
    day = np.where(weekend == 1, rng.integers(5, 7, s.size), rng.integers(0, 5, s.size))
    hours = win * 4 + rng.random(s.size) * 4
    ts = chi.WEEK0 + pd.to_timedelta(w * 7 + day, "D") + pd.to_timedelta(hours, "h")
    lat, lon = chi.to_latlon((gx + rng.random(s.size)) * 250, (gy + rng.random(s.size)) * 250)
    ev.append(pd.DataFrame(dict(date=ts, latitude=lat, longitude=lon)))
df = pd.concat(ev, ignore_index=True)
df = df[df.date < "2024-12-31"]
df.to_csv(chi.CRIME_FILE, index=False)
print("events", len(df), "per year", df.date.dt.year.value_counts().sort_index().to_dict())
