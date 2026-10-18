"""2024: chosen LightGBM (3 seeds) alone, the neural net alone, and their average."""
import numpy as np, pandas as pd, lightgbm as lgb
import experiments as X
R = X.Runner(); W = R.world
groups = ["base", "harm", "ctx", "s311", "lic", "cta", "events"]
ms = [lgb.Booster(model_file=f"out/model_final_chosen{s}.txt") for s in ("", "_seed1", "_seed2")]
nn = np.load("orig/out/pred_2024_nn.npy")
assert np.array_equal(np.load("orig/out/mask.npy"), W.mask)
weeks = list(range(*X.SPLITS["final"]["test"]))
rows = {k: [] for k in ("chosen3", "nn", "blend50", "blend70")}
gb_all = []
for i, t in enumerate(weeks):
    Xb, _ = R.block(groups, t)
    gb = np.mean([m.predict(Xb.astype(np.float32)) for m in ms], 0); gb_all.append(gb)
    n = nn[i].reshape(-1)
    for k, p in (("chosen3", gb), ("nn", n), ("blend50", 0.5 * gb + 0.5 * n), ("blend70", 0.7 * gb + 0.3 * n)):
        rows[k] += X.evaluate_week(W, t, p)
np.save("out/pred_2024_chosen3.npy", np.array(gb_all, np.float32))
for k, r in rows.items():
    pd.DataFrame(r).to_csv(f"out/weekly_final_{k}.csv", index=False)
    print(k, pd.DataFrame(r)[["top1", "top5", "top20", "auc", "harm_top5", "harm_auc"]].mean().round(4).to_dict())
