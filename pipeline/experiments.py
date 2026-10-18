"""Measure each data source on held-out years. Loads all data once, then runs the experiments listed on
the command line (or all of them), appending one row per experiment to out/experiments.csv.

Two splits, so the choice of what to keep is not made on the year we report:
  sel   : train targets 2020-01 .. 2022-09, early stop on Oct-Dec 2022, test on 2023   (used to choose)
  final : train targets 2021-01 .. 2023-09, early stop on Oct-Dec 2023, test on 2024   (reported, same as the original)
"""
import os, sys, json, time
import numpy as np, pandas as pd, lightgbm as lgb
import chi, hotspot as H_, features as F_

OUT = "out"; os.makedirs(OUT, exist_ok=True)
wk = lambda d: int((pd.Timestamp(d) - chi.WEEK0).days // 7)
SPLITS = {
    "sel": dict(train=(wk("2020-01-06"), wk("2022-10-03")), valid=(wk("2022-10-03"), wk("2023-01-02")),
                test=(wk("2023-01-02"), wk("2024-01-01"))),
    "final": dict(train=(wk("2021-01-04"), wk("2023-10-02")), valid=(wk("2023-10-02"), wk("2024-01-01")),
                  test=(wk("2024-01-01"), wk("2024-12-30"))),
}
KEEP_ZERO = 0.08
PARAMS = dict(objective="poisson", learning_rate=0.05, num_leaves=63, min_data_in_leaf=300,
              feature_fraction=0.7, bagging_fraction=0.8, bagging_freq=1, lambda_l2=2.0, verbose=-1, seed=0,
              num_threads=os.cpu_count())


def capture(score, actual, levels=(0.01, 0.05, 0.2), seed=0):
    order = np.lexsort((np.random.default_rng(seed).random(score.size), -score))
    cur = np.cumsum(actual[order]) / max(actual.sum(), 1e-9)
    n = score.size
    return dict(auc=cur.mean(), **{f"top{lv * 100:g}": cur[int(np.ceil(lv * n)) - 1] for lv in levels})


def evaluate_week(world, t, pred):
    """pred: (12 * cells,). One row per slot with captures of crime counts and of harm."""
    n_cells = world.mask.sum()
    yc, yh = world.target(t), world.target(t, harm=True)
    rows = []
    for s in range(12):
        sl = slice(s * n_cells, (s + 1) * n_cells)
        if yc[sl].sum() == 0:
            continue
        r = dict(week=t, slot=s, n=yc[sl].sum(), **capture(pred[sl], yc[sl]))
        r.update({f"harm_{k}": v for k, v in capture(pred[sl], yh[sl]).items()})
        rows.append(r)
    return rows


class Runner:
    def __init__(self):
        self.world = F_.World(groups=F_.GROUPS)
        self.kde_best = self.tune_kde()

    def tune_kde(self):
        p = f"{OUT}/kde_best.json"
        if os.path.exists(p):
            return json.load(open(p))
        H, C, mask = self.world.Hc, self.world.C, self.world.mask
        v0, v1 = SPLITS["final"]["valid"]
        best = None
        for L in (260,):
            for ss in (0.25, 0.5, 0.75, 1):
                for st in (0, 1, 2, 3, 5):
                    r = H_.evaluate(H_.kde_predictor(H, L, ss, st), C, mask, range(v0, v1))
                    if best is None or r.auc.mean() > best[0]:
                        best = (r.auc.mean(), ss, st)
        kb = dict(s_space=best[1], s_time=best[2])
        json.dump(kb, open(p, "w")); print("kde best", kb, flush=True)
        return kb

    def block(self, groups, t):
        Xs, names = [], []
        for g in groups:
            X, n = F_.cached(self.world, g, t, self.kde_best)
            Xs.append(X); names += n
        return np.hstack(Xs), names

    def build(self, groups, weeks, harm, subsample):
        Xs, ys, ws = [], [], []
        for t in weeks:
            X, names = self.block(groups, t)
            y = self.world.target(t, harm)
            if subsample:
                keep = (self.world.target(t) > 0) | (np.random.default_rng(t).random(y.size) < KEEP_ZERO)
                X, y = X[keep], y[keep]
                ws.append(np.where(self.world.target(t)[keep] > 0, 1.0, 1 / KEEP_ZERO))
            Xs.append(X.astype(np.float32)); ys.append(y)
        return np.vstack(Xs), np.concatenate(ys), (np.concatenate(ws) if subsample else None), names

    def run(self, name, groups, split="final", harm=False, params=None, train_from=None, save=False):
        t0 = time.time()
        sp = SPLITS[split]; P = dict(PARAMS, **(params or {}))
        tr = list(range(train_from or sp["train"][0], sp["train"][1])); va = list(range(*sp["valid"]))
        Xtr, ytr, wtr, names = self.build(groups, tr, harm, True)
        Xva, yva, _, _ = self.build(groups, va, harm, False)
        m = lgb.train(P, lgb.Dataset(Xtr, ytr, weight=wtr, feature_name=names, free_raw_data=True), 3000,
                      valid_sets=[lgb.Dataset(Xva, yva, feature_name=names)],
                      callbacks=[lgb.early_stopping(150, verbose=False)])
        it = m.best_iteration
        del Xtr, Xva
        Xa, ya, wa, _ = self.build(groups, tr + va, harm, True)
        m = lgb.train(P, lgb.Dataset(Xa, ya, weight=wa, feature_name=names), it)
        del Xa
        rows = []
        for t in range(*sp["test"]):
            X, _ = self.block(groups, t)
            rows += evaluate_week(self.world, t, m.predict(X.astype(np.float32)))
        r = pd.DataFrame(rows)
        r.to_csv(f"{OUT}/weekly_{split}_{name}.csv", index=False)
        res = dict(name=name, split=split, groups="+".join(groups), harm_target=harm, iters=it,
                   n_features=len(names), minutes=round((time.time() - t0) / 60, 1),
                   **{k: round(float(r[k].mean()), 5) for k in r.columns if k not in ("week", "slot", "n")})
        pd.DataFrame([res]).to_csv(f"{OUT}/experiments.csv", mode="a", index=False,
                                   header=not os.path.exists(f"{OUT}/experiments.csv"))
        imp = pd.Series(m.feature_importance("gain"), index=names).sort_values(ascending=False)
        imp.to_csv(f"{OUT}/importance_{split}_{name}.csv")
        if save:
            m.save_model(f"{OUT}/model_{split}_{name}.txt")
        print(json.dumps(res), flush=True)
        return res, m

    def kde_row(self, split):
        sp = SPLITS[split]; rows = []
        for t in range(*sp["test"]):
            P = H_.smooth(self.world.Hc.rate(t, 260), **self.kde_best)[:, :, self.world.mask].reshape(-1)
            rows += evaluate_week(self.world, t, P)
        r = pd.DataFrame(rows); r.to_csv(f"{OUT}/weekly_{split}_kde5y.csv", index=False)
        res = dict(name="kde5y", split=split, groups="kde", **{k: round(float(r[k].mean()), 5) for k in r.columns if k not in ("week", "slot", "n")})
        pd.DataFrame([res]).to_csv(f"{OUT}/experiments.csv", mode="a", index=False,
                                   header=not os.path.exists(f"{OUT}/experiments.csv"))
        print(json.dumps(res), flush=True)



def batch(R, items):
    """items: list of dicts with keys name, groups, split and optional harm, params, train_from (a date)."""
    for it in items:
        tf = wk(it["train_from"]) if it.get("train_from") else None
        R.run(it["name"], it["groups"], it.get("split", "sel"), harm=it.get("harm", False),
              params=it.get("params"), train_from=tf, save=it.get("save", False))


if __name__ == "__main__":
    R = Runner()
    plan = sys.argv[1] if len(sys.argv) > 1 else "single"
    split = sys.argv[2] if len(sys.argv) > 2 else "sel"
    if plan == "single":            # baseline, then each source added on its own
        R.kde_row(split)
        R.run("base", ["base"], split)
        for g in ("harm", "ctx", "s311", "lic", "cta", "events", "weather"):
            R.run(f"base+{g}", ["base", g], split)
    elif plan == "batch":           # python experiments.py batch <json file>
        batch(R, json.load(open(split)))
    elif plan == "custom":          # python experiments.py custom <split> name g1,g2 [harm]
        R.run(sys.argv[3], sys.argv[4].split(","), split, harm=len(sys.argv) > 5 and sys.argv[5] == "harm")

