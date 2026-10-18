"""Weekly job: refresh data, retrain on the most recent history, forecast next week, write the app's data files.

  python train_weekly.py <publish-folder>      e.g. site/v1/chicago

Training mirrors the evaluated setup: 300 weeks of targets for fitting, the 13 weeks after them for
early stopping, then a refit on all of them with that many rounds; three seeds averaged,
then blended 70/30 with the neural net (stnet.py). The forecast is for the coming
Monday; its features use every complete week of data available (the portal lags about a week)."""
import os, sys, json, datetime as dt
import numpy as np, pandas as pd, lightgbm as lgb
import chi, hotspot as H_, features as F_, experiments as X, export_data as E, download_all

CFG = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "model_config.json")))


def main(folder):
    if os.environ.get("SW_SKIP_DOWNLOAD") != "1":
        download_all.main({"crimes", "streets", "311", "licenses", "cta", "events"})   # weather was tested and dropped
    groups = CFG["groups"]
    W = F_.World(groups=[g for g in groups if g != "base"] + ["base"])
    last = W.n_weeks                         # first week with no (complete) data: the one we forecast from
    runner = X.Runner.__new__(X.Runner); runner.world = W
    runner.kde_best = CFG["kde_best"]
    F_.CACHE = os.environ.get("SW_CACHE", "cache_weekly")
    nv, nt = CFG["valid_weeks"], CFG["train_weeks"]
    va = list(range(last - nv, last)); tr = list(range(last - nv - nt, last - nv))
    harm = CFG.get("harm_target", False)
    Xtr, ytr, wtr, names = runner.build(groups, tr, harm, True)
    Xva, yva, _, _ = runner.build(groups, va, harm, False)
    Xa, ya, wa, _ = runner.build(groups, tr + va, harm, True)
    Xf, _ = runner.block(groups, last); Xf = Xf.astype(np.float32)
    preds, its = [], []
    for seed in CFG.get("seeds", [0]):            # average a few seeds so routes don't reshuffle week to week by chance
        P = dict(X.PARAMS, **CFG.get("params", {}), seed=seed)
        m = lgb.train(P, lgb.Dataset(Xtr, ytr, weight=wtr, feature_name=names), 3000,
                      valid_sets=[lgb.Dataset(Xva, yva, feature_name=names)], callbacks=[lgb.early_stopping(150, verbose=False)])
        it = m.best_iteration; its.append(it)
        m = lgb.train(P, lgb.Dataset(Xa, ya, weight=wa, feature_name=names), it)
        preds.append(m.predict(Xf))
    del Xtr, Xva, Xa
    it = int(np.mean(its))
    p = np.mean(preds, 0).reshape(2, chi.N_WIN, -1)                          # incidents per week
    nn_cfg = CFG.get("net")
    if nn_cfg:                                                                 # blend with the neural net
        import stnet
        nw = list(range(last - nn_cfg["train_weeks"], last))
        p_nn = stnet.forecast(W.C, W.Hc, W.mask, W.static, CFG["kde_best"], nw, last,
                              epochs=nn_cfg["epochs"], seeds=nn_cfg["seeds"])[:, :, W.mask]
        p = (1 - nn_cfg["weight"]) * p + nn_cfg["weight"] * p_nn
    p = p / np.array([20.0, 8.0])[:, None, None]                              # incidents per hour
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
