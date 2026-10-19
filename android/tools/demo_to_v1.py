"""Convert the web demo's embedded data (app_data.json) into format-v1 files, for development until the
pipeline publishes real ones."""
import sys, json, base64, datetime as dt
import numpy as np
sys.path.insert(0, sys.argv[3])
import safewalk_format as F

d = json.load(open(sys.argv[1])); out = sys.argv[2]
b = lambda k, t: np.frombuffer(base64.b64decode(d[k]), t)
nxy = b("nodes", "<i4").reshape(-1, 2).astype(float)
eu, ev = b("eu", "<u4"), b("ev", "<u4")
L = b("len", "<u2").astype(float)
q = b("risk", "<u2").reshape(-1, 12).astype(float) / 10
moff = b("mid_off", "<u4"); mpts = b("mid_pts", "<i4").reshape(-1, 2).astype(float)
mpts = mpts[:moff[-1]]
lat = d["lat0"] + nxy[:, 1] / d["mlat"]; lon = d["lon0"] + nxy[:, 0] / d["mlon"]
slat = d["lat0"] + mpts[:, 1] / d["mlat"]; slon = d["lon0"] + mpts[:, 0] / d["mlon"]
# lengths were rounded to whole metres; make sure each is at least the straight line between its ends
kx = F._m_per_deg_lon(float(lat.mean()))
sl = np.hypot((lon[eu] - lon[ev]) * kx, (lat[eu] - lat[ev]) * F.M_PER_DEG_LAT)
L = np.maximum(L, np.ceil(sl)).clip(1)
rel = q / ((L[:, None] * q).sum(0) / L.sum())
graph_raw = F.encode_graph(lat, lon, eu, ev, L, np.zeros(len(eu), np.uint8), b("name_idx", "<u2"),
                           moff, slat, slon, d["names"])
gid = F.graph_id_of(graph_raw)
qq = np.clip(np.round(rel * F.RISK_SCALE), 0, 65535).astype(np.uint16)
week = dt.date(2024, 12, 30)
risk_raw = F.encode_risk(gid, week, rel, F.thresholds_from(qq, L))
F.write_manifest(out, graph_raw, risk_raw, week, "2026-10-18T00:00:00Z",
                 dict(name="demo-lightgbm-poisson", version="demo-2024-12-30", trained_through="2024-12-29"))
print(F.validate(out))
