"""The spatio-temporal CNN from ai_options/run_neural.py, as a function the weekly job can call.

It predicts a correction on top of the tuned 5-year KDE for every cell and slot, from history rate maps
for all 12 slots. Tested on 2023 and 2024: alone it is about level with LightGBM, but a 70/30 blend
(LightGBM / net) beats LightGBM alone in 48 of 52 weeks of both years. The weekly job trains a fixed
number of epochs (the median that early stopping picked in both test years) to save time."""
import os
import numpy as np, torch, torch.nn as nn
import chi, hotspot as H_

S = H_.N_SLOT
EPS = 1e-4
torch.set_num_threads(os.cpu_count())


class Block(nn.Module):
    def __init__(self, c, d):
        super().__init__()
        self.f = nn.Sequential(nn.Conv2d(c, c, 3, padding=d, dilation=d), nn.GroupNorm(8, c), nn.GELU(),
                               nn.Conv2d(c, c, 3, padding=1), nn.GroupNorm(8, c))

    def forward(self, x):
        return torch.nn.functional.gelu(x + self.f(x))


class STNet(nn.Module):
    """Dilated residual CNN; receptive field about 10 km. Output = log KDE + learned correction per slot."""
    def __init__(self, c_in, c=32, dil=(1, 2, 4, 8, 1, 2)):
        super().__init__()
        self.inp = nn.Sequential(nn.Conv2d(c_in, c, 1), nn.GELU())
        self.body = nn.Sequential(*[Block(c, d) for d in dil])
        self.out = nn.Conv2d(c, S, 1)
        nn.init.zeros_(self.out.weight); nn.init.zeros_(self.out.bias)

    def forward(self, x, log_kde):
        return log_kde + self.out(self.body(self.inp(x)))


def forecast(C, H, mask, static, kde_best, train_weeks, target, epochs=19, seeds=(0, 1, 2), log=print):
    """C: (weeks, 2, 6, NY, NX) street-crime counts; H: hotspot.History(C). Returns (2, 6, NY, NX) incidents per week,
    or (len(target), 2, 6, NY, NX) when target is a list of weeks."""
    targets = list(target) if np.ndim(target) else [target]
    ry, rx = np.nonzero(mask.any(1))[0], np.nonzero(mask.any(0))[0]
    box = np.s_[max(ry[0] - 8, 0):ry[-1] + 9, max(rx[0] - 8, 0):rx[-1] + 9]
    mask_b = mask[box]
    street = [np.log1p(static[k] / 100) for k in ("street_len_all", "street_len_arterial", "street_len_local")]

    def week_input(t):
        ch = [np.log1p(52 * H.rate(t, L)).reshape(S, chi.NY, chi.NX) for L in H_.LOOKBACKS]
        kde = H_.smooth(H.rate(t, 260), **kde_best).reshape(S, chi.NY, chi.NX)
        ch.append(np.log(kde + EPS))
        for k in (1, 2):
            ch.append(np.log1p(52 * H_.smooth(H.window_rate(t - 52 * k - 2, t - 52 * k + 3), **kde_best)).reshape(S, chi.NY, chi.NX))
        ch.append(np.stack(street + [mask.astype(np.float32)]))
        return np.concatenate(ch)[(slice(None),) + box].astype(np.float16), np.log(kde + EPS)[(slice(None),) + box].astype(np.float32)

    X = {t: week_input(t) for t in list(train_weeks) + targets}
    tr = np.stack([X[t][0] for t in train_weeks]).astype(np.float32)
    mu = tr[..., mask_b].mean((0, 2)); sd = tr[..., mask_b].std((0, 2)) + 1e-3
    del tr
    mask_t = torch.tensor(mask_b)

    def batch(ts):
        x = torch.tensor(np.stack([(X[t][0].astype(np.float32) - mu[:, None, None]) / sd[:, None, None] for t in ts]))
        o = torch.tensor(np.stack([X[t][1] for t in ts]))
        y = torch.tensor(C[ts].reshape(len(ts), S, chi.NY, chi.NX)[(slice(None), slice(None)) + box])
        return x, o, y

    preds = []
    for seed in seeds:
        torch.manual_seed(seed); rng = np.random.default_rng(seed)
        m = STNet(len(mu)); opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4)
        for ep in range(epochs):
            m.train()
            for ts in np.array_split(rng.permutation(train_weeks), max(len(train_weeks) // 4, 1)):
                x, o, y = batch(list(ts))
                x = x + 0.05 * torch.randn_like(x)
                lm = m(x, o)
                loss = (torch.exp(lm) - y * lm)[..., mask_t].mean()
                opt.zero_grad(); loss.backward(); opt.step()
        m.eval()
        x, o, _ = batch(targets)
        with torch.no_grad():
            preds.append(torch.exp(m(x, o)).numpy())
        log(f"net seed {seed} trained")
    out = np.zeros((len(targets), S, chi.NY, chi.NX), np.float32); out[(slice(None), slice(None)) + box] = np.mean(preds, 0)
    out = out.reshape(len(targets), 2, chi.N_WIN, chi.NY, chi.NX)
    return out if np.ndim(target) else out[0]
