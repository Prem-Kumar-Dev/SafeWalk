"""Weekly hotspot prediction on a 250 m grid for 12 time slots (weekday/weekend x six 4-hour windows).

Everything a prediction for week t uses comes from weeks before t.
"""
import numpy as np, pandas as pd
from scipy.ndimage import gaussian_filter
import chi

N_SLOT = 2 * chi.N_WIN
LOOKBACKS = (1, 4, 13, 52, 156, 260)          # weeks: up to 5 years of history


def count_cube(df, n_weeks):
    C = np.zeros((n_weeks, 2, chi.N_WIN, chi.NY, chi.NX), np.float32)
    ok = df.week.between(0, n_weeks - 1)
    np.add.at(C, (df.week[ok], df.weekend[ok], df.win[ok], df.gy[ok], df.gx[ok]), 1)
    return C


class History:
    """Fast 'incidents per week over the last L weeks' for any target week t, via cumulative sums."""
    def __init__(self, C):
        self.cs = np.concatenate([np.zeros_like(C[:1]), np.cumsum(C, 0, dtype=np.float32)])

    def rate(self, t, L):
        lo = max(t - L, 0)
        return (self.cs[t] - self.cs[lo]) / max(t - lo, 1)

    def window_rate(self, t0, t1):
        """Rate over weeks t0..t1-1 (clipped to available history)."""
        t0, t1 = max(t0, 0), max(t1, 0)
        return (self.cs[t1] - self.cs[t0]) / max(t1 - t0, 1)


def smooth(a, s_space, s_time=0.0):
    """a: (2, 6, NY, NX). Gaussian in space; circular across the six 4-hour windows of the same day type."""
    if s_space == 0 and s_time == 0:
        return a
    return gaussian_filter(a, sigma=(0, s_time, s_space, s_space),
                           mode=("constant", "wrap", "constant", "constant"))


def capture_curve(score, actual, seed=0):
    """Share of actual incidents captured as flagged area grows; ties broken randomly."""
    order = np.lexsort((np.random.default_rng(seed).random(score.size), -score))
    return np.cumsum(actual[order]) / max(actual.sum(), 1)


def evaluate(pred_fn, C, mask, weeks, levels=(0.01, 0.05, 0.2)):
    """pred_fn(t) -> (2, 6, NY, NX) scores. Returns one row per (week, slot) with capture at each level."""
    n = mask.sum()
    rows = []
    for t in weeks:
        P = pred_fn(t)
        for d in range(2):
            for w in range(chi.N_WIN):
                a = C[t, d, w][mask]
                if a.sum() == 0:
                    continue
                cur = capture_curve(P[d, w][mask], a)
                r = dict(week=t, weekend=d, win=w, n=a.sum(), auc=cur.mean())
                for lv in levels:
                    r[f"top{lv*100:g}"] = cur[int(np.ceil(lv * n)) - 1]
                rows.append(r)
    return pd.DataFrame(rows)


def kde_predictor(H, L, s_space, s_time):
    return lambda t: smooth(H.rate(t, L), s_space, s_time)


# ---------------- features for the machine-learning model ----------------
def static_features(street_len):
    xx, yy = np.meshgrid(np.arange(chi.NX), np.arange(chi.NY))
    return {"street_len_all": street_len["all"], "street_len_arterial": street_len["arterial"],
            "street_len_local": street_len["local"], "cell_x": xx.astype(np.float32), "cell_y": yy.astype(np.float32)}


def week_features(H, t, kde_best, static, mask):
    """All features for target week t as (n_slots * n_cells, n_features), plus names."""
    F = {}
    for L in LOOKBACKS:
        r = H.rate(t, L)
        for s in (0, 1, 2):
            F[f"same_slot_{L}w_s{s}"] = smooth(r, s)
        F[f"st_kde_{L}w"] = smooth(r, **kde_best)
    for L in (52, 260):                                     # all times of day and both day types pooled
        allt = H.rate(t, L).sum((0, 1), keepdims=True)
        F[f"any_time_{L}w_s1"] = np.broadcast_to(smooth(allt, 1), (2, chi.N_WIN, chi.NY, chi.NX))
    # same season last year and two years ago (weeks t-54..t-50 and t-106..t-102)
    for k in (1, 2):
        F[f"same_season_{k}y"] = smooth(H.window_rate(t - 52 * k - 2, t - 52 * k + 3), **kde_best)
    names = list(F) + list(static) + ["weekend", "window"]
    cols = []
    for v in F.values():
        cols.append(v[:, :, mask].reshape(-1))
    n_cells = mask.sum()
    for v in static.values():
        cols.append(np.tile(v[mask], N_SLOT))
    d_idx, w_idx = np.meshgrid(np.arange(2), np.arange(chi.N_WIN), indexing="ij")
    cols.append(np.repeat(d_idx.ravel(), n_cells)); cols.append(np.repeat(w_idx.ravel(), n_cells))
    return np.column_stack(cols).astype(np.float32), names


def week_target(C, t, mask):
    return C[t][:, :, mask].reshape(-1)


