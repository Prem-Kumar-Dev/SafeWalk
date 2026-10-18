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


