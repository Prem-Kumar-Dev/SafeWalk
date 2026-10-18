"""Turn a weekly risk forecast (incidents per hour per 250 m cell and slot) into the app's data files:
graph-<id>.bin, risk-<week>-<hash>.bin and manifest.json, in the format of app-spec/reference/safewalk_format.py."""
import os, sys, datetime as dt
import numpy as np
import chi, routing as R

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.environ.get("SW_FORMAT_DIR", os.path.join(HERE, "reference")))
import safewalk_format as SF

CLASS = {"2": 1, "3": 2, "4": 3, "5": 4, "7": 4, "S": 4}


def rdp(x, y, eps=2.0):
    """Douglas-Peucker: indices of points kept (metres)."""
    if len(x) <= 2:
        return list(range(len(x)))
    keep = np.zeros(len(x), bool); keep[[0, -1]] = True
    stack = [(0, len(x) - 1)]
    while stack:
        a, b = stack.pop()
        dx, dy = x[b] - x[a], y[b] - y[a]; n = np.hypot(dx, dy) or 1
        d = np.abs(dy * (x[a + 1:b] - x[a]) - dx * (y[a + 1:b] - y[a])) / n
        if len(d) and d.max() > eps:
            i = a + 1 + int(d.argmax()); keep[i] = True; stack += [(a, i), (i, b)]
    return list(np.nonzero(keep)[0])


def build_graph(segs):
    """Walking graph in file order. Returns (raw graph bytes, networkx graph with edge attr 'idx')."""
    G = R.build_graph(segs)
    nodes = sorted(G.nodes); idx = {n: i for i, n in enumerate(nodes)}
    edges = sorted(G.edges(data=True), key=lambda e: (min(idx[e[0]], idx[e[1]]), max(idx[e[0]], idx[e[1]])))
    nx_ = np.array([G.nodes[n]["x"] for n in nodes]); ny_ = np.array([G.nodes[n]["y"] for n in nodes])
    lat, lon = chi.to_latlon(nx_, ny_)
    names = sorted({e["name"] for _, _, e in edges if e["name"]}); nidx = {n: i for i, n in enumerate(names)}
    eu, ev, L, cls, nm, off, slat, slon = [], [], [], [], [], [0], [], []
    for k, (u, v, e) in enumerate(edges):
        a, b = sorted((idx[u], idx[v])); ua = nodes[a]
        s = segs[e["seg"]]; x, y = s["x"], s["y"]
        if np.hypot(x[0] - G.nodes[ua]["x"], y[0] - G.nodes[ua]["y"]) > np.hypot(x[-1] - G.nodes[ua]["x"], y[-1] - G.nodes[ua]["y"]):
            x, y = x[::-1], y[::-1]
        keep = rdp(x, y)[1:-1]
        la, lo = chi.to_latlon(x[keep], y[keep])
        slat += list(la); slon += list(lo); off.append(len(slat))
        eu.append(a); ev.append(b)
        straight = np.hypot(nx_[a] - nx_[b], ny_[a] - ny_[b])
        L.append(max(e["length"], straight, 1.0))
        cls.append(CLASS.get(s["cls"], 0)); nm.append(nidx.get(e["name"], SF.NO_NAME))
        e["idx"] = k
    raw = SF.encode_graph(lat, lon, eu, ev, L, cls, nm, off, slat, slon, names)
    order = [(u, v) for u, v, _ in edges]
    return raw, G, order, np.array(L)


def edge_rel_risk(G, order, segs, risk_cells, lengths):
    """risk_cells: (2, 6, NY, NX) incidents per hour. Returns (n_edges, 12) multiples of the city average."""
    R.edge_risk(G, segs, risk_cells)
    rk = np.stack([G[u][v]["risk"] for u, v in order]).astype(np.float64)
    ref = (lengths[:, None] * rk).sum(0) / lengths.sum()
    return rk / ref


def publish(folder, graph_raw, rel, lengths, week_start, model_meta, generated_at=None):
    os.makedirs(folder, exist_ok=True)
    gid = SF.graph_id_of(graph_raw)
    q = np.clip(np.round(rel * SF.RISK_SCALE), 0, 65535).astype(np.uint16)
    risk_raw = SF.encode_risk(gid, week_start, rel, SF.thresholds_from(q, lengths))
    generated_at = generated_at or dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    m = SF.write_manifest(folder, graph_raw, risk_raw, week_start, generated_at, model_meta)
    style = os.path.join(folder, "style.json")
    if not os.path.exists(style):
        open(style, "w").write('{"map_style_url": "https://tiles.openfreemap.org/styles/liberty"}\n')
    if not SF.validate(folder):
        raise SystemExit("validation failed: not publishing")
    return m


def prune(folder, keep_weeks=8):
    """Keep risk files for 8 weeks (spec: apps holding an older manifest can still fetch them) and only the current graph."""
    import json, glob
    m = json.load(open(os.path.join(folder, "manifest.json")))
    cutoff = dt.date.fromisoformat(m["risk"]["week_start"]) - dt.timedelta(weeks=keep_weeks)
    used = {m["graph"]["graph_id"]}
    for p in glob.glob(os.path.join(folder, "risk-*.bin")):
        if dt.date.fromisoformat(os.path.basename(p)[5:15]) < cutoff:
            os.remove(p)
        else:
            used.add(SF.decode_risk(SF.inflate(open(p, "rb").read()))["graph_id"])
    for p in glob.glob(os.path.join(folder, "graph-*.bin")):       # a graph stays while a kept risk file needs it
        if os.path.basename(p)[6:22] not in used:
            os.remove(p)
