"""SafeWalk data files, format version 1: reference writer, reader, validator and router.

This file is the executable version of app-spec/02-data-files.md and app-spec/03-routing.md.
Where the docs and this code disagree, this code wins and the docs get fixed.

  python3 safewalk_format.py validate <dir-with-manifest.json>   check a published data folder
  python3 safewalk_format.py fixture  <out-dir>                  rebuild the test fixture

Only needs numpy.
"""
import hashlib, heapq, json, math, struct, sys, zlib, datetime as dt
import numpy as np

FORMAT_VERSION = 1
N_SLOTS = 12
RISK_SCALE = 100                      # stored value / 100 = multiple of the city average for that slot
NO_NAME = 0xFFFF
LEVELS = {"direct": 0.0, "balanced": 0.5, "lowest_risk": 1.0}
SNAP_MAX_M = 150.0
M_PER_DEG_LAT = 110_950.0             # same constants as chi.py, so lengths agree with the model pipeline

GRAPH_HDR = struct.Struct("<4sHHIIIII4i")   # magic, version, flags, n_nodes, n_edges, n_shape, n_names, names_bytes, bbox e6
RISK_HDR = struct.Struct("<4sHHI16sIH2x")   # magic, version, n_slots, n_edges, graph_id, week_start_days, scale


# ---------------------------------------------------------------- compression
def deflate(raw: bytes) -> bytes:
    """Raw DEFLATE (RFC 1951, no zlib/gzip header). iOS: NSData.decompressed(using: .zlib). Android: Inflater(true)."""
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(raw) + c.flush()


def inflate(blob: bytes) -> bytes:
    return zlib.decompress(blob, -15)


def graph_id_of(raw_graph: bytes) -> str:
    return hashlib.sha256(raw_graph).hexdigest()[:16]


# ---------------------------------------------------------------- graph file
def encode_graph(lat, lon, eu, ev, length_m, road_class, name_idx, shape_off, shape_lat, shape_lon, names):
    """All arrays are numpy-like. shape_* hold intermediate points only, each edge's run oriented u -> v."""
    lat_e6 = np.round(np.asarray(lat) * 1e6).astype("<i4")
    lon_e6 = np.round(np.asarray(lon) * 1e6).astype("<i4")
    s_lat = np.round(np.asarray(shape_lat, float) * 1e6).astype("<i4")
    s_lon = np.round(np.asarray(shape_lon, float) * 1e6).astype("<i4")
    names_blob = "\n".join(names).encode("utf-8")
    all_lat = np.r_[lat_e6, s_lat]; all_lon = np.r_[lon_e6, s_lon]
    bbox = (int(all_lat.min()), int(all_lon.min()), int(all_lat.max()), int(all_lon.max()))
    n, e = len(lat_e6), len(eu)
    parts = [
        GRAPH_HDR.pack(b"SWG1", FORMAT_VERSION, 0, n, e, len(s_lat), len(names), len(names_blob), *bbox),
        np.stack([lat_e6, lon_e6], 1).astype("<i4").tobytes(),           # nodes: (lat, lon) pairs
        np.asarray(eu, "<u4").tobytes(),
        np.asarray(ev, "<u4").tobytes(),
        np.asarray(length_m, "<f4").tobytes(),
        np.asarray(shape_off, "<u4").tobytes(),                            # n_edges + 1 offsets
        np.stack([s_lat, s_lon], 1).astype("<i4").tobytes() if len(s_lat) else b"",
        np.asarray(name_idx, "<u2").tobytes(),
        np.asarray(road_class, "u1").tobytes(),
    ]
    raw = b"".join(parts)
    raw += b"\0" * (-len(raw) % 4)                                         # names start 4-byte aligned
    raw += names_blob
    return raw


def decode_graph(raw: bytes):
    h = GRAPH_HDR.unpack_from(raw, 0)
    magic, ver, flags, n, e, ns, nn, nb = h[:8]
    assert magic == b"SWG1", "not a SafeWalk graph file"
    assert ver == FORMAT_VERSION, f"graph format {ver} not supported"
    o = GRAPH_HDR.size
    def take(dtype, count):
        nonlocal o
        a = np.frombuffer(raw, dtype, count, o); o += a.nbytes; return a
    nodes = take("<i4", 2 * n).reshape(n, 2)
    g = dict(lat=nodes[:, 0] / 1e6, lon=nodes[:, 1] / 1e6, eu=take("<u4", e), ev=take("<u4", e),
             length=take("<f4", e), shape_off=take("<u4", e + 1))
    sp = take("<i4", 2 * ns).reshape(ns, 2)
    g["shape_lat"], g["shape_lon"] = sp[:, 0] / 1e6, sp[:, 1] / 1e6
    g["name_idx"] = take("<u2", e); g["road_class"] = take("u1", e)
    o += -o % 4
    g["names"] = raw[o:o + nb].decode("utf-8").split("\n") if nn else []
    assert o + nb == len(raw), "graph file has trailing bytes"
    g["bbox"] = [v / 1e6 for v in h[8:]]
    g["n_nodes"], g["n_edges"] = n, e
    return g


# ---------------------------------------------------------------- risk file
def encode_risk(graph_id, week_start: dt.date, rel_risk, thresholds):
    """rel_risk: (n_edges, 12) multiples of the city average. thresholds: dict elevated/high/very_high -> 12 values."""
    q = np.clip(np.round(np.asarray(rel_risk) * RISK_SCALE), 0, 65535).astype("<u2")
    days = (week_start - dt.date(1970, 1, 1)).days
    hdr = RISK_HDR.pack(b"SWR1", FORMAT_VERSION, N_SLOTS, q.shape[0], graph_id.encode("ascii"), days, RISK_SCALE)
    thr = np.array([thresholds[k] for k in ("elevated", "high", "very_high")], "<u2")
    return hdr + thr.tobytes() + q.tobytes()


def decode_risk(raw: bytes):
    magic, ver, ns, e, gid, days, scale = RISK_HDR.unpack_from(raw, 0)
    assert magic == b"SWR1", "not a SafeWalk risk file"
    assert ver == FORMAT_VERSION and ns == N_SLOTS
    o = RISK_HDR.size
    thr = np.frombuffer(raw, "<u2", 3 * N_SLOTS, o).reshape(3, N_SLOTS); o += thr.nbytes
    risk = np.frombuffer(raw, "<u2", e * N_SLOTS, o).reshape(e, N_SLOTS); o += risk.nbytes
    assert o == len(raw), "risk file size mismatch"
    return dict(graph_id=gid.decode("ascii"), week_start=dt.date(1970, 1, 1) + dt.timedelta(days=days),
                scale=scale, n_edges=e, risk=risk,
                thresholds=dict(elevated=thr[0], high=thr[1], very_high=thr[2]))


def thresholds_from(rel_q, length):
    """Length-weighted cut points: riskiest 20% / 5% / 1% of street length, per slot, in stored units."""
    out = {}
    for k, frac in (("elevated", 0.20), ("high", 0.05), ("very_high", 0.01)):
        vals = []
        for s in range(N_SLOTS):
            o = np.argsort(-rel_q[:, s].astype(np.int64)); cl = np.cumsum(length[o]) / length.sum()
            vals.append(int(rel_q[o[min(np.searchsorted(cl, frac), len(o) - 1)], s]))
        out[k] = vals
    return out


# ---------------------------------------------------------------- time slot
def slot_for(local: dt.datetime) -> int:
    """local = wall-clock time in America/Chicago. Weekend = Saturday or Sunday. Slot = weekend * 6 + hour // 4."""
    return (6 if local.weekday() >= 5 else 0) + local.hour // 4


# ---------------------------------------------------------------- geometry
def _m_per_deg_lon(lat):
    return 111_320.0 * math.cos(math.radians(lat))


def edge_points(g, e):
    """(lat, lon) list for edge e, from node u to node v."""
    a, b = g["shape_off"][e], g["shape_off"][e + 1]
    u, v = g["eu"][e], g["ev"][e]
    return ([(g["lat"][u], g["lon"][u])] + list(zip(g["shape_lat"][a:b], g["shape_lon"][a:b]))
            + [(g["lat"][v], g["lon"][v])])


def point_at(g, e, frac):
    """(lat, lon) of the point a fraction of the way along edge e's polyline, measured from u."""
    pts = edge_points(g, e)
    kx = _m_per_deg_lon(pts[0][0])
    seg = [math.hypot((b[1] - a[1]) * kx, (b[0] - a[0]) * M_PER_DEG_LAT) for a, b in zip(pts, pts[1:])]
    goal = frac * sum(seg)
    for (a, b), L in zip(zip(pts, pts[1:]), seg):
        if goal <= L or L == seg[-1] and (a, b) == (pts[-2], pts[-1]):
            t = 0.0 if L == 0 else min(goal / L, 1.0)
            return a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])
        goal -= L
    return pts[-1]


def snap(g, lat, lon):
    """Nearest point on any edge. Returns (edge, fraction of the edge's length from u, distance m) or None.
    Apps use a bucket index; this brute force is the reference answer."""
    best = None
    kx, ky = _m_per_deg_lon(lat), M_PER_DEG_LAT
    for e in range(g["n_edges"]):
        pts = [((p[1] - lon) * kx, (p[0] - lat) * ky) for p in edge_points(g, e)]
        seg = [math.hypot(x1 - x0, y1 - y0) for (x0, y0), (x1, y1) in zip(pts, pts[1:])]
        tot = sum(seg) or 1.0; run = 0.0
        for (x0, y0), (x1, y1), L in zip(pts, pts[1:], seg):
            t = 0.0 if L == 0 else max(0.0, min(1.0, -(x0 * (x1 - x0) + y0 * (y1 - y0)) / (L * L)))
            d = math.hypot(x0 + t * (x1 - x0), y0 + t * (y1 - y0))
            if best is None or d < best[2] - 0.01:          # ties within 1 cm go to the lower edge index
                best = (e, (run + t * L) / tot, d)
            run += L
    return best if best and best[2] <= SNAP_MAX_M else None


# ---------------------------------------------------------------- routing
def route(g, r, slot, lam, a_snap, b_snap):
    """A* on cost = length * (1 + lam * risk / scale). a_snap/b_snap from snap().
    Returns dict(edges=[(edge, from_frac, to_frac)], length_m, cost, exposure) or None."""
    risk = r["risk"][:, slot].astype(np.float64) / r["scale"]
    L = g["length"].astype(np.float64)
    w = L * (1 + lam * risk)
    n = g["n_nodes"]
    S, T = n, n + 1                                       # virtual start / target nodes
    ea, fa, _ = a_snap; eb, fb, _ = b_snap

    adj = {}
    def add(x, y, cost, piece):
        adj.setdefault(x, []).append((y, cost, piece))
    # virtual links from the snapped points to the ends of their edges, at the edge's own per-metre cost
    add(S, int(g["eu"][ea]), w[ea] * fa, (ea, fa, 0.0))
    add(S, int(g["ev"][ea]), w[ea] * (1 - fa), (ea, fa, 1.0))
    add(int(g["eu"][eb]), T, w[eb] * fb, (eb, 0.0, fb))
    add(int(g["ev"][eb]), T, w[eb] * (1 - fb), (eb, 1.0, fb))
    if ea == eb:
        add(S, T, w[ea] * abs(fb - fa), (ea, fa, fb))
    for e in range(g["n_edges"]):
        u, v = int(g["eu"][e]), int(g["ev"][e])
        add(u, v, w[e], (e, 0.0, 1.0)); add(v, u, w[e], (e, 1.0, 0.0))

    # heuristic: straight-line metres to the target point (cost >= length >= straight line, so admissible)
    tl, tn = point_at(g, eb, fb)
    kx = _m_per_deg_lon(tl)
    def h(x):
        if x >= n:
            return 0.0
        return 0.999 * math.hypot((g["lon"][x] - tn) * kx, (g["lat"][x] - tl) * M_PER_DEG_LAT)

    dist = {S: 0.0}; prev = {}; pq = [(h(S), 0.0, S)]; done = set()
    while pq:
        _, d, x = heapq.heappop(pq)
        if x in done:
            continue
        done.add(x)
        if x == T:
            break
        for y, c, piece in adj.get(x, ()):
            nd = d + c
            if nd < dist.get(y, math.inf) - 1e-9:
                dist[y] = nd; prev[y] = (x, piece); heapq.heappush(pq, (nd + h(y), nd, y))
    if T not in dist:
        return None
    pieces = []; x = T
    while x != S:
        x, piece = prev[x]; pieces.append(piece)
    pieces.reverse()
    length = sum(L[e] * abs(t - f) for e, f, t in pieces)
    expo = sum(L[e] * abs(t - f) * risk[e] for e, f, t in pieces)
    return dict(edges=[(int(e), round(float(f), 6), round(float(t), 6)) for e, f, t in pieces],
                length_m=round(float(length), 1), cost=round(float(dist[T]), 1),
                exposure=round(float(expo), 1))


# ---------------------------------------------------------------- validator
def sha256_file(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def validate(folder):
    """Checks everything an app relies on. Prints problems; returns True when the folder is publishable."""
    import os
    ok = True
    def bad(msg):
        nonlocal ok; ok = False; print("FAIL:", msg)
    m = json.load(open(os.path.join(folder, "manifest.json")))
    if m.get("format_version") != FORMAT_VERSION:
        bad("format_version must be 1")
    files = {}
    for key in ("graph", "risk"):
        f = m[key]; p = os.path.join(folder, f["path"])
        blob = open(p, "rb").read()
        if len(blob) != f["bytes"]: bad(f"{key}: bytes {len(blob)} != manifest {f['bytes']}")
        if hashlib.sha256(blob).hexdigest() != f["sha256"]: bad(f"{key}: sha256 mismatch")
        raw = inflate(blob)
        if len(raw) != f["raw_bytes"]: bad(f"{key}: raw_bytes mismatch")
        files[key] = raw
    g = decode_graph(files["graph"]); r = decode_risk(files["risk"])
    gid = graph_id_of(files["graph"])
    if gid != m["graph"]["graph_id"]: bad("graph_id in manifest is not the graph's hash")
    if r["graph_id"] != gid: bad("risk file was made for a different graph")
    if r["n_edges"] != g["n_edges"]: bad("risk edge count != graph edge count")
    if str(r["week_start"]) != m["risk"]["week_start"]: bad("week_start differs between manifest and risk file")
    if r["week_start"].weekday() != 0: bad("week_start must be a Monday")
    if int(g["eu"].max()) >= g["n_nodes"] or int(g["ev"].max()) >= g["n_nodes"]: bad("edge points at missing node")
    if np.any(g["eu"] == g["ev"]): bad("self-loop edge")
    if not np.all(g["length"] >= 1): bad("edge shorter than 1 m")
    if g["shape_off"][0] != 0 or g["shape_off"][-1] != len(g["shape_lat"]) or np.any(np.diff(g["shape_off"].astype(np.int64)) < 0):
        bad("shape_off not monotonic / not covering shape points")
    named = g["name_idx"][g["name_idx"] != NO_NAME]
    if len(named) and int(named.max()) >= len(g["names"]): bad("name index out of range")
    if not set(np.unique(g["road_class"]).tolist()) <= {0, 1, 2, 3, 4}: bad("unknown road_class")
    # straight line between the ends must not exceed stored length (A* heuristic relies on it)
    kx = _m_per_deg_lon(float(np.mean(g["lat"])))
    sl = np.hypot((g["lon"][g["eu"]] - g["lon"][g["ev"]]) * kx, (g["lat"][g["eu"]] - g["lat"][g["ev"]]) * M_PER_DEG_LAT)
    if np.any(sl > g["length"] * 1.01 + 1): bad("edge length shorter than straight line between its ends")
    # connected (one component), so every snapped pair has a route
    parent = list(range(g["n_nodes"]))
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    for u, v in zip(g["eu"], g["ev"]):
        parent[find(int(u))] = find(int(v))
    if len({find(i) for i in range(g["n_nodes"])}) != 1: bad("graph is not one connected component")
    # city average is 1.0 per slot (length-weighted), within quantisation
    rel = r["risk"].astype(np.float64) / r["scale"]
    avg = (g["length"][:, None] * rel).sum(0) / g["length"].sum()
    if np.any(np.abs(avg - 1) > 0.02): bad(f"length-weighted mean risk per slot should be 1.0, got {np.round(avg, 3)}")
    t = r["thresholds"]
    if not (np.all(t["elevated"] <= t["high"]) and np.all(t["high"] <= t["very_high"])): bad("thresholds not ordered")
    print(("OK" if ok else "NOT PUBLISHABLE"), f"graph {gid}: {g['n_nodes']} nodes, {g['n_edges']} edges;",
          f"risk week {r['week_start']}")
    return ok


def write_manifest(folder, graph_raw, risk_raw, week_start, generated_at, model, city="chicago"):
    """Writes the two compressed files with content-addressed names, then manifest.json."""
    import os
    gid = graph_id_of(graph_raw)
    gz, rz = deflate(graph_raw), deflate(risk_raw)
    rid = hashlib.sha256(rz).hexdigest()[:12]
    gpath, rpath = f"graph-{gid}.bin", f"risk-{week_start}-{rid}.bin"
    open(os.path.join(folder, gpath), "wb").write(gz)
    open(os.path.join(folder, rpath), "wb").write(rz)
    g = decode_graph(graph_raw)
    m = dict(
        format_version=FORMAT_VERSION, min_app_format=FORMAT_VERSION, city=city,
        display_name="Chicago", timezone="America/Chicago", bbox=g["bbox"],
        generated_at=generated_at,
        graph=dict(path=gpath, graph_id=gid, bytes=len(gz), raw_bytes=len(graph_raw), sha256=hashlib.sha256(gz).hexdigest()),
        risk=dict(path=rpath, week_start=str(week_start), valid_until=str(week_start + dt.timedelta(days=7)),
                  bytes=len(rz), raw_bytes=len(risk_raw), sha256=hashlib.sha256(rz).hexdigest()),
        model=model,
    )
    json.dump(m, open(os.path.join(folder, "manifest.json"), "w"), indent=2)
    style = os.path.join(folder, "style.json")
    if not os.path.exists(style):
        json.dump({"map_style_url": "https://tiles.openfreemap.org/styles/liberty"}, open(style, "w"), indent=2)
    return m


# ---------------------------------------------------------------- fixture
def build_fixture(out):
    """A 7 x 7 street grid (100 m blocks) near the Loop with a risky band, plus golden routes."""
    import os
    os.makedirs(out, exist_ok=True)
    lat0, lon0, step = 41.8800, -87.6300, 100.0
    kx = _m_per_deg_lon(lat0)
    N = 7
    rng = np.random.default_rng(1)
    jit = rng.uniform(-15, 15, (N * N, 2))                 # jitter corners so no two paths tie on length
    lat = [lat0 + (j * step + jit[j * N + i, 1]) / M_PER_DEG_LAT for j in range(N) for i in range(N)]
    lon = [lon0 + (i * step + jit[j * N + i, 0]) / kx for j in range(N) for i in range(N)]
    def metres(p, q):
        return math.hypot((q[1] - p[1]) * kx, (q[0] - p[0]) * M_PER_DEG_LAT)
    eu, ev, length, cls, nidx, risk = [], [], [], [], [], []
    shape_off, s_lat, s_lon = [0], [], []
    names = ["W Madison St", "W Monroe St", "W Adams St", "W Jackson Blvd", "W Van Buren St", "W Congress Pkwy",
             "W Harrison St", "N Wells St", "N La Salle St", "N Clark St", "N Dearborn St", "N State St",
             "N Wabash Ave", "N Michigan Ave"]
    for j in range(N):
        for i in range(N):
            a = j * N + i
            for b, horiz in ((a + 1, True), (a + N, False)):
                if (horiz and i == N - 1) or (not horiz and j == N - 1):
                    continue
                eu.append(a); ev.append(b)
                if not horiz and i == 3 and j == 2:          # one bent street, to exercise shape points
                    mid = (lat[a] + 50 / M_PER_DEG_LAT, lon[a] + 20 / kx)
                    s_lat.append(mid[0]); s_lon.append(mid[1])
                    length.append(metres((lat[a], lon[a]), mid) + metres(mid, (lat[b], lon[b])))
                else:
                    length.append(metres((lat[a], lon[a]), (lat[b], lon[b])))
                shape_off.append(len(s_lat))
                cls.append(1 if (horiz and j % 3 == 0) or (not horiz and i % 3 == 0) else 3)
                nidx.append(j if horiz else N + i)
                # risky middle column (i = 2..4) at night, calm in the day; slot-dependent on purpose
                hot = 2 <= i <= 4 and 1 <= j <= 5
                risk.append([(6.0 if hot else 0.4) * (1 + 0.3 * rng.random()) if s in (5, 6, 11) else
                             (2.0 if hot else 0.8) for s in range(N_SLOTS)])
    length = np.array(length); rel = np.array(risk)
    rel = rel / ((length[:, None] * rel).sum(0) / length.sum())         # normalise: city average = 1.0
    nidx[5] = NO_NAME                                                    # one unnamed edge
    graph_raw = encode_graph(lat, lon, eu, ev, length, cls, nidx, shape_off, s_lat, s_lon, names)
    gid = graph_id_of(graph_raw)
    q = np.clip(np.round(rel * RISK_SCALE), 0, 65535).astype(np.uint16)
    risk_raw = encode_risk(gid, dt.date(2026, 10, 19), rel, thresholds_from(q, length))
    write_manifest(out, graph_raw, risk_raw, dt.date(2026, 10, 19), "2026-10-19T09:00:00Z",
                   dict(name="fixture", version="0", trained_through="2026-10-11"), city="fixture")

    g = decode_graph(graph_raw); r = decode_risk(risk_raw)
    cases = []
    off = lambda k, dn, de: (lat[k] + dn / M_PER_DEG_LAT, lon[k] + de / kx)    # a point dn m north, de m east of corner k
    trips = [("near corner to near corner, night", off(0, 4, 30), off(48, -35, -3), dt.datetime(2026, 10, 20, 21, 30)),
             ("mid-block start and end, night", off(7, 3, 40), off(13, 60, -2), dt.datetime(2026, 10, 21, 22, 0)),
             ("same edge, weekend night", off(8, 1, 15), off(8, -2, 70), dt.datetime(2026, 10, 24, 1, 15)),
             ("daytime weekday", off(3, 5, 40), off(45, -5, -45), dt.datetime(2026, 10, 22, 13, 0))]
    for label, A, B, when in trips:
        s = slot_for(when); sa, sb = snap(g, *A), snap(g, *B)
        c = dict(label=label, start=dict(lat=A[0], lon=A[1]), end=dict(lat=B[0], lon=B[1]),
                 local_time=when.isoformat(), slot=s,
                 snap_start=dict(edge=sa[0], frac=round(sa[1], 6), dist_m=round(sa[2], 2)),
                 snap_end=dict(edge=sb[0], frac=round(sb[1], 6), dist_m=round(sb[2], 2)), routes={})
        for name, lam in LEVELS.items():
            c["routes"][name] = route(g, r, s, lam, sa, sb)
        cases.append(c)
    far = (lat0 - 0.01, lon0)                                             # ~1.1 km south: outside, must not snap
    cases.append(dict(label="outside coverage", start=dict(lat=far[0], lon=far[1]), snap_start=None))
    assert snap(g, *far) is None
    json.dump(dict(note="Golden answers from reference/safewalk_format.py. Lengths in metres; compare with 0.5 m "
                        "tolerance, fractions with 1e-4. edges = [edge index, from fraction, to fraction] in walking order.",
                   cases=cases), open(os.path.join(out, "expected_routes.json"), "w"), indent=1)
    return validate(out)


if __name__ == "__main__":
    cmd, folder = sys.argv[1], sys.argv[2]
    ok = validate(folder) if cmd == "validate" else build_fixture(folder)
    sys.exit(0 if ok else 1)
