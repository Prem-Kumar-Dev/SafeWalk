"""Do routes from the new risk map pass fewer real 2024 crimes than routes from the old one?
And does penalising the worst blocks harder (cost = len x (1 + lam x r^p), p > 1) beat the app's linear rule?

Risk maps are forecasts for the first week of 2024 made only from 2015-2023 data (same as the original test).
Usage: python route_eval2.py name1=model_file:groups name2=...   (groups comma-separated)"""
import sys, json
import numpy as np, pandas as pd, lightgbm as lgb, networkx as nx
from scipy.spatial import cKDTree
import chi, routing as R, features as F_, experiments as X

EXTRA_TARGETS = (2, 4, 6)          # report crime cut interpolated at these % extra distances


def forecast(runner, model_file, groups, t):
    m = lgb.Booster(model_file=model_file)
    Xb, _ = runner.block(groups, t)
    p = m.predict(Xb.astype(np.float32)).reshape(2, chi.N_WIN, -1) / np.array([20.0, 8.0])[:, None, None]
    risk = np.zeros((2, chi.N_WIN, chi.NY, chi.NX), np.float32); risk[:, :, runner.world.mask] = p
    return risk


def main(specs):
    runner = X.Runner()
    W = runner.world; segs = W.segs
    t0 = X.SPLITS["final"]["test"][0]
    G = R.build_graph(segs)
    edges = list(G.edges(data=True)); lens = np.array([e["length"] for _, _, e in edges])
    d24 = W.crimes[W.crimes.street & (W.crimes.year == 2024)]
    nodes = np.array(list(G.nodes)); xy = np.array([[G.nodes[n]["x"], G.nodes[n]["y"]] for n in nodes])
    rng = np.random.default_rng(1); pairs = []
    while len(pairs) < 250:
        i, j = rng.integers(len(nodes), size=2)
        if 1000 <= np.hypot(*(xy[i] - xy[j])) <= 4000:
            pairs.append((nodes[i], nodes[j]))
    maps = {}
    for s in specs:
        name, rest = s.split("="); f, groups = rest.split(":")
        R.edge_risk(G, segs, forecast(runner, f, groups.split(","), t0))
        rk = np.stack([e["risk"] for _, _, e in edges])
        maps[name] = rk / ((lens[:, None] * rk).sum(0) / lens.sum())          # x city average per slot
    slots = {"Weekday 8 pm-12 am": 5, "Weekend 12-4 am": 6}
    rows = []
    for sname, slot in slots.items():
        d, w = divmod(slot, 6)
        tree = cKDTree(d24[(d24.weekend == d) & (d24.win == w)][["x", "y"]].to_numpy())
        for mname, rel in maps.items():
            r = rel[:, slot]
            for p in (1.0, 1.5, 2.0):
                for lam in (0, 0.1, 0.25, 0.5, 1, 2):
                    if lam == 0 and (p != 1.0):
                        continue
                    for (u, v, e), rr in zip(edges, r):
                        e["w"] = e["length"] * (1 + lam * rr ** p); e["rr"] = rr
                    for k, (a, b) in enumerate(pairs):
                        path = nx.shortest_path(G, a, b, weight="w")
                        pe = [G[x][y] for x, y in zip(path[:-1], path[1:])]
                        L = sum(e["length"] for e in pe)
                        px, py = R.path_xy(G, segs, path); sx, sy = R.sample_along(px, py, 20)
                        near = set().union(*tree.query_ball_point(np.c_[sx, sy], 50)) if len(sx) else set()
                        worst = max((e["rr"] for e in pe if e["length"] >= 25), default=0)
                        rows.append(dict(slot=sname, map=mname, p=p, lam=lam, pair=k, length=L,
                                         crimes=len(near), worst=worst))
            print(sname, mname, "done", flush=True)
    r = pd.DataFrame(rows); r.to_csv("out/route_eval2.csv", index=False)
    base = r[r.lam == 0].set_index(["slot", "map", "pair"])[["length", "crimes", "worst"]]
    r = r.join(base, on=["slot", "map", "pair"], rsuffix="_short")
    g = r.groupby(["slot", "map", "p", "lam"])
    s = pd.DataFrame(dict(extra_pct=g.apply(lambda d: (d.length / d.length_short - 1).mean() * 100),
                          crime_cut_pct=g.apply(lambda d: (1 - d.crimes.sum() / d.crimes_short.sum()) * 100),
                          worst_block_x=g.worst.median()))
    s.to_csv("out/route_summary2.csv"); print(s.round(1).to_string())
    # crime cut at matched extra distance, by linear interpolation along each (map, p) curve
    out = []
    for (slot, mp, p), d in s.reset_index().groupby(["slot", "map", "p"]):
        d = pd.concat([d, s.reset_index().query("slot == @slot and map == @mp and lam == 0")]).drop_duplicates("lam").sort_values("extra_pct")
        for x in EXTRA_TARGETS:
            out.append(dict(slot=slot, map=mp, p=p, extra=x, cut=float(np.interp(x, d.extra_pct, d.crime_cut_pct))))
    o = pd.DataFrame(out).pivot_table(index=["slot", "extra"], columns=["map", "p"], values="cut")
    o.to_csv("out/route_matched2.csv"); print(o.round(1).to_string())


if __name__ == "__main__":
    main(sys.argv[1:])
