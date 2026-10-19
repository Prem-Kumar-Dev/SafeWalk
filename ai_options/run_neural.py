"""Step 2b: spatio-temporal neural network vs LightGBM. Same weeks, same metric, same test year (2024).

A small fully-convolutional network looks at the whole city map at once: history rate maps for all 12
time slots go in as image channels, and it predicts a correction on top of the tuned 5-year KDE for
every cell and slot. It starts out exactly equal to the KDE (last layer is zero) and only moves away
from it where the training weeks say it helps. Several seeds are averaged for stability.
Run after run_kde.py and run_model.py (it reads their outputs from out/)."""
import numpy as np, pandas as pd, json, pickle, time, os, torch, torch.nn as nn
import chi, hotspot as H_

N_SEEDS = int(os.environ.get("N_SEEDS", 3))
MAX_EPOCHS = int(os.environ.get("MAX_EPOCHS", 40))
PATIENCE = 6
torch.set_num_threads(os.cpu_count())
dev = "mps" if torch.backends.mps.is_available() else "cuda" if torch.cuda.is_available() else "cpu"

cfg = json.load(open("out/config.json"))
df = chi.load_crimes()
mask = np.load("out/mask.npy"); street_len = pickle.load(open("out/street_len.pkl", "rb"))
C = H_.count_cube(df, cfg["n_weeks"]); H = H_.History(C)
kde_best, L_best = cfg["kde_best"], cfg["kde_best_L"]
S = H_.N_SLOT
EPS = 1e-4
# work on the city's bounding box (plus a margin) only: fewer pixels, faster training
ry, rx = np.nonzero(mask.any(1))[0], np.nonzero(mask.any(0))[0]
BOX = np.s_[max(ry[0] - 8, 0):ry[-1] + 9, max(rx[0] - 8, 0):rx[-1] + 9]
mask_b = mask[BOX]


def week_input(t):
    """(channels, NY, NX) input for target week t, built only from weeks before t; plus the KDE offset (S, NY, NX)."""
    ch = []
    for L in H_.LOOKBACKS:                                   # recent to 5-year rates, all 12 slots
        ch.append(np.log1p(52 * H.rate(t, L)).reshape(S, chi.NY, chi.NX))
    kde = H_.smooth(H.rate(t, L_best), **kde_best).reshape(S, chi.NY, chi.NX)
    ch.append(np.log(kde + EPS))
    for k in (1, 2):                                         # same season one and two years earlier
        ch.append(np.log1p(52 * H_.smooth(H.window_rate(t - 52 * k - 2, t - 52 * k + 3), **kde_best)).reshape(S, chi.NY, chi.NX))
    ch.append(np.stack([np.log1p(street_len[k] / 100) for k in ("all", "arterial", "local")] + [mask.astype(np.float32)]))
    return np.concatenate(ch)[(slice(None),) + BOX].astype(np.float16), np.log(kde + EPS)[(slice(None),) + BOX].astype(np.float32)


weeks = cfg["train"] + cfg["valid"] + cfg["test"]
t0 = time.time()
X = {t: week_input(t) for t in weeks}
print(f"inputs for {len(weeks)} weeks built in {time.time() - t0:.0f}s, {X[weeks[0]][0].shape[0]} channels, device {dev}")
tr = np.stack([X[t][0] for t in cfg["train"]]).astype(np.float32)
mu = tr[..., mask_b].mean((0, 2)); sd = tr[..., mask_b].std((0, 2)) + 1e-3
del tr
mask_t = torch.tensor(mask_b, device=dev)


class Block(nn.Module):
    def __init__(self, c, d):
        super().__init__()
        self.f = nn.Sequential(nn.Conv2d(c, c, 3, padding=d, dilation=d), nn.GroupNorm(8, c), nn.GELU(),
                               nn.Conv2d(c, c, 3, padding=1), nn.GroupNorm(8, c))
    def forward(self, x):
        return torch.nn.functional.gelu(x + self.f(x))


class STNet(nn.Module):
    """Dilated residual CNN; receptive field about 10 km. Output = log KDE + learned correction per slot."""
    def __init__(self, c_in, c=int(os.environ.get("WIDTH", 32)), dil=(1, 2, 4, 8, 1, 2)):
        super().__init__()
        self.inp = nn.Sequential(nn.Conv2d(c_in, c, 1), nn.GELU())
        self.body = nn.Sequential(*[Block(c, d) for d in dil])
        self.out = nn.Conv2d(c, S, 1)
        nn.init.zeros_(self.out.weight); nn.init.zeros_(self.out.bias)
    def forward(self, x, log_kde):
        return log_kde + self.out(self.body(self.inp(x)))


def batch(ts):
    x = torch.tensor(np.stack([(X[t][0].astype(np.float32) - mu[:, None, None]) / sd[:, None, None] for t in ts]), device=dev)
    o = torch.tensor(np.stack([X[t][1] for t in ts]), device=dev)
    y = torch.tensor(C[ts].reshape(len(ts), S, chi.NY, chi.NX)[(slice(None), slice(None)) + BOX], device=dev)
    return x, o, y


def poisson_loss(log_mu, y):
    l = torch.exp(log_mu) - y * log_mu
    return l[..., mask_t].mean()


def predict(models, t):
    x, o, _ = batch([t])
    with torch.no_grad():
        p = np.mean([torch.exp(m(x, o))[0].cpu().numpy() for m in models], 0)
    out = np.zeros((S, chi.NY, chi.NX), np.float32); out[(slice(None),) + BOX] = p
    return out.reshape(2, chi.N_WIN, chi.NY, chi.NX)


def fit(seed, train_weeks, valid_weeks=None, n_epochs=MAX_EPOCHS):
    """Train one network. With valid_weeks: early-stop on validation AUC and return the best epoch count."""
    torch.manual_seed(seed); rng = np.random.default_rng(seed)
    m = STNet(len(mu)).to(dev)
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4)
    best, best_ep, best_state, bad = -1, 0, None, 0
    for ep in range(n_epochs):
        m.train()
        for ts in np.array_split(rng.permutation(train_weeks), max(len(train_weeks) // 4, 1)):
            x, o, y = batch(list(ts))
            x = x + 0.05 * torch.randn_like(x)                   # light input noise against overfitting
            loss = poisson_loss(m(x, o), y)
            opt.zero_grad(); loss.backward(); opt.step()
        if valid_weeks is None:
            continue
        m.eval()
        auc = H_.evaluate(lambda t: predict([m], t), C, mask, valid_weeks).auc.mean()
        if auc > best:
            best, best_ep, bad = auc, ep + 1, 0
            best_state = {k: v.clone() for k, v in m.state_dict().items()}
        else:
            bad += 1
            if bad >= PATIENCE:
                break
    if valid_weeks is not None:
        m.load_state_dict(best_state)
    return m.eval(), best_ep, best


# 1) pick the number of epochs on validation weeks (Oct-Dec 2023), like LightGBM's early stopping
eps = []
for s in range(N_SEEDS):
    t0 = time.time()
    _, ep, auc = fit(s, cfg["train"], cfg["valid"])
    eps.append(ep); print(f"seed {s}: best epoch {ep}, valid AUC {auc:.4f} ({time.time() - t0:.0f}s)")
n_ep = int(np.median(eps))

# 2) refit on train + valid with that many epochs, average the seeds, test on 2024
models = [fit(s, cfg["train"] + cfg["valid"], None, n_ep)[0] for s in range(N_SEEDS)]
torch.save([m.state_dict() for m in models], "out/stnet_chicago.pt")
n_cells = mask.sum()
preds = {}
def nn_pred(t):
    P = predict(models, t); preds[t] = P[:, :, mask]; return P

res = pickle.load(open("out/all_results.pkl", "rb"))
res["Neural net (STNet)"] = H_.evaluate(nn_pred, C, mask, cfg["test"])
np.save("out/pred_2024_nn.npy", np.stack([preds[t] for t in cfg["test"]]))

# 3) blend: average of LightGBM and the network (both predict incidents per week)
lgb_pred = np.load("out/pred_2024.npy")
ti = {t: i for i, t in enumerate(cfg["test"])}
def blend(t):
    out = np.zeros((2, chi.N_WIN, chi.NY, chi.NX), np.float32)
    out[:, :, mask] = 0.5 * lgb_pred[ti[t]] + 0.5 * preds[t]
    return out
res["Blend (LightGBM + net)"] = H_.evaluate(blend, C, mask, cfg["test"])
pickle.dump(res, open("out/all_results_nn.pkl", "wb"))

summ = pd.DataFrame({k: v[["top1", "top5", "top20", "auc"]].mean() for k, v in res.items()}).T
print(summ.round(3)); summ.to_csv("out/summary_nn.csv")
for name in ("Neural net (STNet)", "Blend (LightGBM + net)"):
    for col in ("top5", "auc"):
        a = res[name].groupby("week")[col].mean(); b = res["LightGBM (ours)"].groupby("week")[col].mean()
        d = a - b
        lo, hi = np.percentile([d.sample(len(d), replace=True, random_state=i).mean() for i in range(2000)], [2.5, 97.5])
        print(f"{name} vs LightGBM, {col}: better in {(d > 0).sum()}/{len(d)} weeks, "
              f"mean {100 * d.mean():+.2f} pts (95% CI {100 * lo:+.2f} to {100 * hi:+.2f})")
