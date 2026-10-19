import sys, json, os, numpy as np
sys.path.insert(0, sys.argv[2]); import safewalk_format as F
folder = sys.argv[1]; m = json.load(open(os.path.join(folder, "manifest.json")))
g = F.decode_graph(F.inflate(open(os.path.join(folder, m["graph"]["path"]), "rb").read()))
r = F.decode_risk(F.inflate(open(os.path.join(folder, m["risk"]["path"]), "rb").read()))
rng = np.random.default_rng(7); cases = []
def near_snap(lat, lon):
    # brute force over edges whose first node is within 400 m, same scoring as F.snap
    kx = F._m_per_deg_lon(lat)
    d = np.hypot((g["lon"] - lon) * kx, (g["lat"] - lat) * F.M_PER_DEG_LAT)
    close = set(np.nonzero(d < 600)[0].tolist())
    sub = [e for e in range(g["n_edges"]) if int(g["eu"][e]) in close or int(g["ev"][e]) in close]
    best = None
    for e in sub:
        pts = [((p[1] - lon) * kx, (p[0] - lat) * F.M_PER_DEG_LAT) for p in F.edge_points(g, e)]
        seg = [np.hypot(x1 - x0, y1 - y0) for (x0, y0), (x1, y1) in zip(pts, pts[1:])]
        tot = sum(seg) or 1.0; run = 0.0
        for (x0, y0), (x1, y1), L in zip(pts, pts[1:], seg):
            t = 0.0 if L == 0 else max(0.0, min(1.0, -(x0 * (x1 - x0) + y0 * (y1 - y0)) / (L * L)))
            dd = np.hypot(x0 + t * (x1 - x0), y0 + t * (y1 - y0))
            if best is None or (dd < best[2] - 1e-9) or (abs(dd - best[2]) <= 1e-9 and e < best[0]):
                best = (e, (run + t * L) / tot, dd)
            run += L
    return best if best and best[2] <= F.SNAP_MAX_M else None
while len(cases) < 6:
    i, j = rng.integers(0, g["n_nodes"], 2)
    A = (g["lat"][i] + rng.normal(0, 3e-4), g["lon"][i] + rng.normal(0, 3e-4))
    B = (g["lat"][j] + rng.normal(0, 3e-4), g["lon"][j] + rng.normal(0, 3e-4))
    km = np.hypot((A[1]-B[1]) * F._m_per_deg_lon(A[0]), (A[0]-B[0]) * F.M_PER_DEG_LAT) / 1000
    if not 1 < km < 6: continue
    sa, sb = near_snap(*A), near_snap(*B)
    if not sa or not sb: continue
    slot = int(rng.integers(0, 12)); c = dict(start=A, end=B, slot=slot, snap_start=sa, snap_end=sb, routes={})
    for name, lam in F.LEVELS.items():
        c["routes"][name] = F.route(g, r, slot, lam, sa, sb)
    cases.append(c); print(len(cases), round(km, 1), {k: v["length_m"] for k, v in c["routes"].items()}, flush=True)
json.dump(dict(cases=cases), open(os.path.join(folder, "city_golden.json"), "w"), default=float)
