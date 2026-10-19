"""Weekly job: refresh data, retrain on the most recent history, forecast next week, write the app's data files.

  python train_weekly.py <publish-folder>      e.g. site/v1/chicago

Training mirrors the evaluated setup: 300 weeks of targets for fitting, the 13 weeks after them for
early stopping, then a refit on all of them with that many rounds; three seeds averaged,
then blended 70/30 with the neural net (stnet.py). The forecast is for the coming
Monday; its features use every complete week of data available (the portal lags about a week).

Before publishing, a quality check (gate) replays the same recipe on the last 8 complete weeks and compares it
with the plain 5-year KDE baseline. If this week's model is worse, the job fails and nothing is published,
so the app keeps last week's forecast. SW_SKIP_GATE=1 skips the check."""
import os, sys, json, datetime as dt
import numpy as np, pandas as pd, lightgbm as lgb
import chi, hotspot as H_, features as F_, experiments as X, export_data as E, download_all

CFG = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "model_config.json")))


def fit_predict(runner, W, groups, cutoff, targets):
    """Train the full recipe on weeks before `cutoff` and forecast each week in `targets`.
    Returns (len(targets), 2, N_WIN, n_cells) incidents per week, and the mean number of boosting rounds."""
    nv, nt = CFG["valid_weeks"], CFG["train_weeks"]
    va = list(range(cutoff - nv, cutoff)); tr = list(range(cutoff - nv - nt, cutoff - nv))
    harm = CFG.get("harm_target", False)
    Xtr, ytr, wtr, names = runner.build(groups, tr, harm, True)
    Xva, yva, _, _ = runner.build(groups, va, harm, False)
    Xa, ya, wa, _ = runner.build(groups, tr + va, harm, True)
    Xf = [runner.block(groups, t)[0].astype(np.float32) for t in targets]
    preds, its = [], []
    for seed in CFG.get("seeds", [0]):            # average a few seeds so routes don't reshuffle week to week by chance
        P = dict(X.PARAMS, **CFG.get("params", {}), seed=seed)
        m = lgb.train(P, lgb.Dataset(Xtr, ytr, weight=wtr, feature_name=names), 3000,
                      valid_sets=[lgb.Dataset(Xva, yva, feature_name=names)], callbacks=[lgb.early_stopping(150, verbose=False)])
        it = m.best_iteration; its.append(it)
        m = lgb.train(P, lgb.Dataset(Xa, ya, weight=wa, feature_name=names), it)
        preds.append([m.predict(x) for x in Xf])
    del Xtr, Xva, Xa
    p = np.mean(preds, 0).reshape(len(targets), 2, chi.N_WIN, -1)
    nn_cfg = CFG.get("net")
    if nn_cfg:                                                                 # blend with the neural net
        import stnet
        nw = list(range(cutoff - nn_cfg["train_weeks"], cutoff))
        p_nn = stnet.forecast(W.C, W.Hc, W.mask, W.static, CFG["kde_best"], nw, list(targets),
                              epochs=nn_cfg["epochs"], seeds=nn_cfg["seeds"])[..., W.mask]
        p = (1 - nn_cfg["weight"]) * p + nn_cfg["weight"] * p_nn
    return p, int(np.mean(its))


def gate(runner, W, groups, last):
    """Before publishing, replay the same recipe on the most recent weeks we already know the answer for:
    train as if it were `weeks` weeks ago, forecast those weeks, and score both the model and the plain
    5-year KDE baseline on the crimes that actually happened. Refuse to publish if the model is worse."""
    g = CFG.get("gate", {})
    k, margin = g.get("weeks", 8), g.get("min_auc_margin", 0.0)
    weeks = list(range(last - k, last))
    p, _ = fit_predict(runner, W, groups, last - k, weeks)
    rm, rk = [], []
    for i, t in enumerate(weeks):
        rm += X.evaluate_week(W, t, p[i].reshape(-1))
        kde = H_.smooth(W.Hc.rate(t, 260), **CFG["kde_best"])[:, :, W.mask].reshape(-1)
        rk += X.evaluate_week(W, t, kde)
    rm, rk = pd.DataFrame(rm), pd.DataFrame(rk)
    cols = ["auc", "top1", "top5", "top20", "harm_auc", "harm_top5"]
    wins = int((rm.groupby("week").auc.mean() > rk.groupby("week").auc.mean()).sum())
    report = dict(weeks=[str(chi.week_start(t).date()) for t in weeks], min_auc_margin=margin,
                  model={c: round(float(rm[c].mean()), 5) for c in cols},
                  kde={c: round(float(rk[c].mean()), 5) for c in cols}, weeks_model_better=wins)
    report["passed"] = bool(report["model"]["auc"] - report["kde"]["auc"] >= margin)
    os.makedirs(X.OUT, exist_ok=True)
    json.dump(report, open(f"{X.OUT}/gate.json", "w"), indent=1)
    verdict = "passed: publishing" if report["passed"] else "FAILED: not publishing, the app keeps last week's forecast"
    lines = [f"### Quality check {verdict}", "",
             f"Backtest on the {k} weeks from {report['weeks'][0]} to {report['weeks'][-1]}, "
             f"model trained only on data before them. Share of street crimes inside the riskiest part of the city:", "",
             "| | AUC | Top 1% | Top 5% | Top 20% | Harm in top 5% |", "|---|---|---|---|---|---|"]
    for name, key in (("This week's model", "model"), ("KDE baseline", "kde")):
        r = report[key]
        lines.append(f"| {name} | {r['auc']:.4f} | {r['top1']:.1%} | {r['top5']:.1%} | {r['top20']:.1%} | {r['harm_top5']:.1%} |")
    lines += ["", f"The model beat the baseline in {wins} of {k} weeks. "
              f"It must match or beat the baseline's AUC (margin {margin:+.4f}) to publish.", ""]
    md = "\n".join(lines)
    open(f"{X.OUT}/gate.md", "w").write(md)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(md)
    print(json.dumps(report), flush=True)
    return report["passed"]


def main(folder):
    if os.environ.get("SW_SKIP_DOWNLOAD") != "1":
        download_all.main({"crimes", "streets", "311", "licenses", "cta", "events"})   # weather was tested and dropped
    groups = CFG["groups"]
    W = F_.World(groups=[g for g in groups if g != "base"] + ["base"])
    last = W.n_weeks                         # first week with no (complete) data: the one we forecast from
    runner = X.Runner.__new__(X.Runner); runner.world = W
    runner.kde_best = CFG["kde_best"]
    F_.CACHE = os.environ.get("SW_CACHE", "cache_weekly")
    if os.environ.get("SW_SKIP_GATE") == "1":
        print("quality check skipped (SW_SKIP_GATE=1)", flush=True)
    elif not gate(runner, W, groups, last):
        raise SystemExit("quality check failed: this week's model is worse than the KDE baseline, not publishing")
    p, it = fit_predict(runner, W, groups, last, [last])
    nn_cfg = CFG.get("net")
    p = p[0] / np.array([20.0, 8.0])[:, None, None]                           # incidents per hour
    risk = np.zeros((2, chi.N_WIN, chi.NY, chi.NX), np.float32); risk[:, :, W.mask] = p

    graph_raw, G, order, lengths = E.build_graph(W.segs)
    rel = E.edge_rel_risk(G, order, W.segs, risk, lengths)
    today = dt.date.today()
    week_start = today + dt.timedelta(days=(7 - today.weekday()) % 7)          # next Monday (today if Monday)
    trained_through = (chi.week_start(last) - pd.Timedelta(days=1)).date()
    meta = dict(name="lightgbm-poisson+stnet" if nn_cfg else "lightgbm-poisson", version=f"{week_start.isocalendar()[0]}.{week_start.isocalendar()[1]:02d}",
                trained_through=str(trained_through), features="+".join(groups), rounds=int(it))
    E.publish(folder, graph_raw, rel, lengths, week_start, meta)
    E.prune(folder)
    print("published", folder, "week", week_start, "trained through", trained_through, "rounds", it)


if __name__ == "__main__":
    main(sys.argv[1])
