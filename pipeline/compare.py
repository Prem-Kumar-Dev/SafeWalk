"""Paired comparison of experiments week by week: mean gain in points with a 95% bootstrap CI over test weeks."""
import sys, numpy as np, pandas as pd
split, ref = sys.argv[1], sys.argv[2]
cols = ("auc", "top1", "top5", "top20", "harm_auc", "harm_top5")
a = pd.read_csv(f"out/weekly_{split}_{ref}.csv").groupby("week")[list(cols)].mean()
rng = np.random.default_rng(0)
for name in sys.argv[3:]:
    b = pd.read_csv(f"out/weekly_{split}_{name}.csv").groupby("week")[list(cols)].mean()
    d = (b - a) * 100
    idx = rng.integers(len(d), size=(4000, len(d)))
    out = []
    for c in cols:
        bs = d[c].to_numpy()[idx].mean(1)
        out.append(f"{c} {d[c].mean():+.2f} [{np.percentile(bs, 2.5):+.2f},{np.percentile(bs, 97.5):+.2f}]")
    print(f"{name:30s} wins {int((d.auc > 0).sum())}/{len(d)} | " + " | ".join(out))
