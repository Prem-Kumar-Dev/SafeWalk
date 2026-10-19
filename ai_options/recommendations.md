# SafeWalk: free AI options and what's worth doing

Checked 18 Oct 2026. Everything here is free for a one-person app with no server budget.

## Recommendation in one line each

| Idea | Verdict | Why |
|---|---|---|
| Plain-language route explanations, template first | **Do now** | Zero cost, works on every phone, can't invent facts |
| Let Apple's on-device model / Gemini Nano reword the template | **Do later, optional** | Free and offline, but only on recent flagship phones; the template stays the source of truth |
| Neural network (spatio-temporal CNN) instead of LightGBM | **Test, don't switch yet** | Code is ready (`run_neural.py`); needs the real data to decide. Published work and our own results suggest a small gain at most |
| Blend LightGBM + neural net | **Test with the above** | Cheapest way to get a gain if the net adds anything |
| New free data: streetlight outages, places open late, CTA stations, weather | **Do, highest value** | Long-history crime rates already dominate the model; new information is what can move accuracy, not a fancier model |
| Bundle an open LLM (Gemma 3 1B, Llama 3.2 1B) | **Skip** | 0.5–0.8 GB download and ~1 GB RAM for one sentence |
| Gemini API free tier for explanations | **Skip** | Free-tier prompts may be used by Google and read by reviewers; route start/end is personal location data; not allowed for EEA/UK users |

## 1. Neural network vs LightGBM

**What I built.** `run_neural.py` (in this folder) adds a step after `run_model.py` in the existing pipeline. It is a small dilated residual CNN that sees the whole city map at once: the 1-week to 5-year rate maps for all 12 time slots, the tuned KDE, the same season one and two years earlier, and street length, as image channels. It predicts a correction on top of the tuned KDE, so it starts out exactly equal to the KDE and only moves where training weeks justify it. Same train / validation / 2024 test weeks and the same capture metric as LightGBM, Poisson loss, early stopping on validation, refit on train+valid, several seeds averaged so the result doesn't swing from run to run. It also scores a 50/50 blend with LightGBM and prints a bootstrap confidence interval for the difference against LightGBM per test week.

**Status.** The cloud environment can't reach data.cityofchicago.org (and the linked Mac's sandbox can't either), so I could not run it on the real data. I ran the whole pipeline (KDE → LightGBM → neural net) on a simulated Chicago-shaped dataset to prove the code runs end to end; results on simulated data say nothing about real Chicago and are only a smoke test. See "Simulated-data smoke test" below.

**What to expect on real data.** On real Chicago, LightGBM beats the tuned 5-year KDE by only 0.3 AUC points, and 70% of its feature importance is long-history rates. That says crime locations are very stable and the ceiling from crime history alone is close. I found no published head-to-head study where a deep model beats a well-tuned KDE or gradient-boosting model on weekly fine-grid forecasts; deep-learning papers usually compare against weak baselines (e.g. ConvLSTM on Chicago reported against logistic regression and random forest, [arXiv 2509.20913](https://arxiv.org/html/2509.20913)), and the NIJ forecasting challenge was won by a kernel/Poisson model ([arXiv 1801.02858](https://ar5iv.arxiv.org/html/1801.02858)). So switch to the net only if it beats LightGBM on 2024 with a confidence interval above zero; otherwise keep LightGBM (smaller, faster, easier to retrain weekly).

**How to run it on real data** (once the data is downloaded, e.g. by the retraining thread or on your own machine):
```
python run_kde.py && python run_model.py && python run_neural.py
```
It reads `out/config.json`, `out/mask.npy`, `out/street_len.pkl`, `out/all_results.pkl`, `out/pred_2024.npy` and writes `out/summary_nn.csv`, `out/pred_2024_nn.npy`, `out/stnet_chicago.pt`. Needs `pip install torch` (free). It uses Apple-silicon GPU (MPS) automatically on a Mac; free Google Colab is another option. Env vars: `N_SEEDS` (default 3), `MAX_EPOCHS` (40), `WIDTH` (32).

## 2. On-device route explanations

**Design: facts from code, wording optional from AI.** The router already knows everything needed: blocks avoided, their streets, the time slot, extra distance, risk vs city average. Build the sentence from a template, e.g.

> "This route avoids 3 higher-risk blocks on S State St for weekday 8 pm–12 am. It's 4% longer (130 m) and has about 70% less predicted risk than the shortest route."

Then, where available, ask the on-device model to reword it more naturally, and check every number in its output appears in the input; if not, show the template. This keeps the app honest (no invented facts) and works on every phone.

**Apple Foundation Models (iOS 26+).** Free, offline, no API key, ~3B model ([Apple tech report](https://machinelearning.apple.com/research/apple-foundation-models-tech-report-2025)). Only on Apple Intelligence phones: iPhone 15 Pro and newer ([Apple](https://support.apple.com/en-gb/guide/iphone/aside/iph275f4d617/26/ios/26)). 4,096-token context per session ([TN3193](https://developer.apple.com/tutorials/data/documentation/technotes/tn3193-managing-the-on-device-foundation-model-s-context-window.md)), plenty for this. Use a small `@Generable` struct, no tools, low temperature, and handle `availability` being unavailable (device not eligible, Apple Intelligence off, model not downloaded). Apple doesn't let you pin a model version, so outputs can change with iOS updates; the number check above covers that.

**Gemini Nano via ML Kit GenAI (Android).** Free, on-device, usable by third-party apps; Rewriting and Prompt APIs are Beta, structured output is Alpha ([ML Kit GenAI](https://developers.google.com/ml-kit/genai)). Supported on Pixel 9 and newer, Galaxy S25/S26 and a list of other recent flagships. Must handle `BUSY`, battery-quota and background-use errors; foreground only. The Rewriting API is enough for polishing the template sentence.

**Everyone else** gets the template, which is the same information.

## 3. Free data that can actually improve the forecast

Ranked by expected value for night-time walking:
1. **Streetlight outages**: Chicago 311 "Street Lights All Out", updated daily ([city page](https://chicago.gov/city/en/dataset/street_lights_out.html)). A current outage is new information the crime history doesn't have. Same portal as the crime data, so same download blocker here.
2. **Places open late** (shops, restaurants, gas stations with `opening_hours`) and **lit streets** (`lit=yes`) from OpenStreetMap via Overpass. Query weekly in the pipeline and ship it with the weekly risk tile, never from users' phones; public Overpass servers aren't meant as an app backend ([Overpass docs](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)).
3. **CTA stations and stops** from CTA's free GTFS feed: distance to the nearest station as a static feature.
4. **Weather** from Open-Meteo, free for non-commercial use with attribution ([terms](https://open-meteo.com/en/terms)); call once per week/area from the pipeline, not per user.

Each one is a new column in `week_features` / `static_features`; LightGBM (and the net) pick it up with no other change. Measure each on the 2024 test year the same way and keep only what helps.

## 4. Keeping the model from "deviating"

What already helps, and what I'd add, without paid tools:
- Keep KDE as a baseline every week and alert if LightGBM falls below it; this catches a bad retrain.
- Average several seeds (the neural script does this) or use bagging, so a weekly retrain doesn't reshuffle routes for no reason.
- Rolling-origin backtest (train to month m, test month m+1, repeat) instead of one test year, to see how stable the gain is.
- Check calibration (predicted vs actual counts per risk decile) so "3× city average" means what it says.

## 5. Things not to build
- "Unusual activity" alerts or neighbourhood danger labels: high stigma risk and reported crime partly reflects where police patrol. Explain routes block by block and slot by slot, call predictions uncertain, and prefer positive phrasing ("well-lit, shops open").

## Simulated-data smoke test

Simulated data: ~45k incidents/yr with stable hotspots, slowly drifting local changes, a near-repeat effect, seasons and per-place time-of-day mix. Only proves the code runs; not evidence about Chicago.

2024 (simulated) test weeks, 2 seeds, width 32, CPU, about 25 minutes in total:

| Model | Top 1% | Top 5% | Top 20% | AUC |
|---|---|---|---|---|
| Tuned KDE, best | 19.2% | 44.6% | 73.5% | 0.847 |
| LightGBM | 22.8% | 48.1% | 75.6% | 0.858 |
| Neural net (STNet) | 22.5% | 47.3% | 75.5% | 0.856 |
| Blend (LightGBM + net) | 23.2% | 48.4% | 76.0% | 0.860 |

- Net alone vs LightGBM: AUC −0.20 pts (95% CI −0.29 to −0.11), better in 12 of 51 weeks.
- Blend vs LightGBM: AUC +0.17 pts (95% CI +0.13 to +0.21), better in 44 of 51 weeks.

On simulated data the net alone doesn't beat LightGBM but the blend gives a small, consistent gain. That's the pattern to check for on real data.

## Files
- `run_neural.py`: the neural model step; drop it next to the pipeline's other step files (`chi.py`, `hotspot.py`, ...).
- `make_synthetic.py`: test harness that writes fake data in the pipeline's input format, for checking code without the portal. Don't use its outputs for anything else.
