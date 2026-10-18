"""Feature groups for the weekly hotspot model. Every feature for target week t uses only data from
before week t, except things published ahead of time (event permits) and the optional weather group
(actual weather of week t: an upper bound on what a forecast could add)."""
import os, time
import numpy as np
import chi, hotspot as H_, sources as S

GROUPS = ("base", "harm", "ctx", "s311", "lic", "cta", "events", "weather")
CACHE = os.environ.get("SW_CACHE", "cache")
sm = H_.smooth


def _b(a):
    """(NY, NX) or (2, NY, NX) -> (2, 6, NY, NX)."""
    a = np.asarray(a, np.float32)
    if a.ndim == 2:
        return np.broadcast_to(a, (2, chi.N_WIN) + a.shape)
    return np.broadcast_to(a[:, None], (2, chi.N_WIN) + a.shape[1:])


def _s2(a, s):
    from scipy.ndimage import gaussian_filter
    return a if s == 0 else gaussian_filter(a, s, mode="constant")


class CellHistory:
    """Like hotspot.History for (weeks, ...) arrays of any trailing shape."""
    def __init__(self, C):
        self.cs = np.concatenate([np.zeros_like(C[:1]), np.cumsum(C, 0, dtype=np.float32)])

    def rate(self, t, L):
        lo = max(t - L, 0)
        return (self.cs[t] - self.cs[lo]) / max(t - lo, 1)


class World:
    def __init__(self, n_weeks=None, groups=GROUPS, verbose=True):
        t0 = time.time()
        log = (lambda *a: print(f"[{time.time() - t0:5.0f}s]", *a, flush=True)) if verbose else (lambda *a: None)
        df = S.load_all_crimes()
        self.crimes = df
        st = df[df.street]
        self.n_weeks = n_weeks or int(st.week.max())          # last full week of data
        n = self.n_weeks + 1                                  # +1 so features for "next week" exist
        self.C = S.slot_cube(st, n); self.Hc = H_.History(self.C)
        self.CH = S.slot_cube(st, n, st.harm); self.Hh = H_.History(self.CH)
        log("crimes", len(df), "street", len(st), "weeks", self.n_weeks)
        segs = chi.load_streets()
        self.segs = segs
        self.mask, street_len = chi.street_cells(segs)
        self.static = H_.static_features(street_len)
        self.groups = set(groups)
        if "ctx" in groups:
            ctx = S.context_cubes(df, n)
            self.ctx = {k: CellHistory(v) for k, v in ctx.items()}
            other = df[~df.street]
            self.Hall = H_.History(S.slot_cube(other, n))
            log("context crimes")
        if "s311" in groups:
            self.s311 = {k: CellHistory(v) for k, v in S.load_311(n).items()}
            log("311")
        if "lic" in groups:
            self.lic = S.license_cubes(n)
            log("licenses")
        if "cta" in groups:
            self.cta, stations = S.cta_static()
            self.rides = CellHistory(S.cta_rides(n, stations))
            log("cta")
        if "events" in groups:
            E, k = S.event_cubes(n)
            self.ev = E; self.ev_hist = CellHistory(E)
            log("events", k)
        if "weather" in groups:
            self.wx, self.wx_names = S.weather_table(n)
            log("weather")

    # ------------------------------------------------------------------ per-group features for week t
    def group(self, g, t, kde_best):
        F = {}
        if g == "base":
            for L in H_.LOOKBACKS:
                r = self.Hc.rate(t, L)
                for s in (0, 1, 2):
                    F[f"same_slot_{L}w_s{s}"] = sm(r, s)
                F[f"st_kde_{L}w"] = sm(r, **kde_best)
            for L in (52, 260):
                allt = self.Hc.rate(t, L).sum((0, 1), keepdims=True)
                F[f"any_time_{L}w_s1"] = np.broadcast_to(sm(allt, 1), (2, chi.N_WIN, chi.NY, chi.NX))
            for k in (1, 2):
                F[f"same_season_{k}y"] = sm((self.Hc.cs[t - 52 * k + 3] - self.Hc.cs[t - 52 * k - 2]) / 5, **kde_best)
            for name, v in self.static.items():
                F[name] = _b(v)
        elif g == "harm":
            for L in (52, 260):
                r = self.Hh.rate(t, L)
                F[f"harm_slot_{L}w_s1"] = sm(r, 1)
                F[f"harm_kde_{L}w"] = sm(r, **kde_best)
            F["harm_slot_13w_s2"] = sm(self.Hh.rate(t, 13), 2)
        elif g == "ctx":
            for k, h in self.ctx.items():
                F[f"ctx_{k}_260w_s1"] = _b(_s2(h.rate(t, 260), 1))
                F[f"ctx_{k}_52w_s1"] = _b(_s2(h.rate(t, 52), 1))
                F[f"ctx_{k}_4w_s2"] = _b(_s2(h.rate(t, 4), 2))
            F["ctx_allother_slot_260w_s1"] = sm(self.Hall.rate(t, 260), 1)
            F["ctx_allother_slot_52w_s1"] = sm(self.Hall.rate(t, 52), 1)
        elif g == "s311":
            h = self.s311["lights"]
            for L, s in ((4, 1), (13, 1), (52, 1), (52, 0)):
                F[f"lights_out_{L}w_s{s}"] = _b(_s2(h.rate(t, L), s))
            for k in ("graffiti", "vehicles", "vacant"):
                F[f"{k}_52w_s1"] = _b(_s2(self.s311[k].rate(t, 52), 1))
            F["vacant_260w_s1"] = _b(_s2(self.s311["vacant"].rate(t, 260), 1))
        elif g == "lic":
            for k, cube in self.lic.items():
                a = cube[t - 1]
                F[f"lic_{k}_s0"] = _b(a)
                F[f"lic_{k}_s1"] = _b(_s2(a, 1))
            F["lic_bars_s3"] = _b(_s2(self.lic["bars"][t - 1], 3))
        elif g == "cta":
            for k, v in self.cta.items():
                F[f"cta_{k}"] = _b(v)
            F["cta_l_stations_s2"] = _b(_s2(self.cta["l_stations"], 2))
            F["cta_bus_boardings_s1"] = _b(_s2(self.cta["bus_boardings"], 1))
            r = self.rides.rate(t, 52)                                 # (2, NY, NX) per day, by day type
            F["cta_rides_52w_s1"] = _b(np.stack([_s2(r[0], 1), _s2(r[1], 1)]))
            F["cta_rides_52w_s3"] = _b(np.stack([_s2(r[0], 3), _s2(r[1], 3)]))
        elif g == "events":
            e = self.ev[t]
            F["events_this_week_s1"] = _b(np.stack([_s2(e[0], 1), _s2(e[1], 1)]))
            F["events_this_week_s3"] = _b(np.stack([_s2(e[0], 3), _s2(e[1], 3)]))
            F["events_260w_s1"] = _b(_s2(self.ev_hist.rate(t, 260).sum(0), 1))
        elif g == "weather":
            w = self.wx[t]                                             # (2, 6, n_vars)
            for j, nm in enumerate(self.wx_names):
                F[f"wx_{nm}"] = np.broadcast_to(w[:, :, j][:, :, None, None], (2, chi.N_WIN, chi.NY, chi.NX))
        return F

    def matrix(self, g, t, kde_best):
        F = self.group(g, t, kde_best)
        X = np.column_stack([np.ascontiguousarray(v)[:, :, self.mask].reshape(-1) for v in F.values()])
        if g == "base":
            n_cells = self.mask.sum()
            d_idx, w_idx = np.meshgrid(np.arange(2), np.arange(chi.N_WIN), indexing="ij")
            X = np.column_stack([X, np.repeat(d_idx.ravel(), n_cells), np.repeat(w_idx.ravel(), n_cells)])
            return X.astype(np.float32), list(F) + ["weekend", "window"]
        return X.astype(np.float32), list(F)

    def target(self, t, harm=False):
        return (self.CH if harm else self.C)[t][:, :, self.mask].reshape(-1)


def cached(world, g, t, kde_best, tag="v1"):
    """Feature block for group g, week t, as float16 on disk (identical rounding at train and predict time)."""
    os.makedirs(f"{CACHE}/{g}", exist_ok=True)
    p = f"{CACHE}/{g}/{tag}_{t}.npy"; pn = f"{CACHE}/{g}/{tag}_names.txt"
    if os.path.exists(p) and os.path.exists(pn):
        return np.load(p), open(pn).read().split("\n")
    X, names = world.matrix(g, t, kde_best)
    X = X.astype(np.float16)
    np.save(p, X); open(pn, "w").write("\n".join(names))
    return X, names
