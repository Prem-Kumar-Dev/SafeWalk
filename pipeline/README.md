# SafeWalk Chicago model, version 2

The weekly risk model behind the app: what changed, how much it helped, and how to run it.

## Results on 2024 (the holdout year, never used for any choice)

The choices were made on 2023 first, with a model trained only on data before 2023. The 2024 numbers below confirm them.

The metric is the share of next week's street crimes that fall inside the riskiest 1%, 5% or 20% of the city. AUC is the area under that curve.

| Model | Top 1% | Top 5% | Top 20% | AUC |
|---|---|---|---|---|
| Tuned KDE, 5-year history | 14.6% | 33.7% | 65.7% | 0.819 |
| Old LightGBM (version 1) | 15.0% | 33.9% | 66.1% | 0.822 |
| New LightGBM (more data and sources, 3 seeds) | 15.1% | 34.6% | 66.8% | 0.825 |
| Neural net alone | 14.9% | 33.9% | 66.5% | 0.823 |
| **New model: 70% LightGBM + 30% neural net** | **15.2%** | **34.7%** | **67.0%** | **0.827** |

- **New model vs old:** +0.45 AUC points (95% CI +0.40 to +0.51). It is better in 52 of 52 weeks of 2024, and also in 50 of 52 weeks of 2023.
- **Severity-weighted** (homicide 10, sexual assault 8, robbery 3 to 5, battery 2 to 4, assault 1 to 2): the share of harm inside the top 5% rose from 32.8% to 33.6%.
- **For scale:** the whole gain is under 1 point. Crime locations are very stable, so the 5-year history already carries most of the signal, and a large jump was never likely. The new sources add real information, but not much of it.

## What each data source did

Each source was tested alone on 2023. The change is in AUC points, paired by week, with a 95% CI.

| Source | Kept? | Effect |
|---|---|---|
| Business licences (bars, liquor stores, late-hour, restaurants, gas stations) | Yes | +0.08 (+0.05 to +0.11) |
| CTA 'L' stations, daily station entries, bus stops and boardings | Yes | +0.04 (+0.02 to +0.07) |
| 311 streetlight and alley-light outages, graffiti, abandoned cars, vacant buildings | Yes | +0.01, and it helps the top 1% |
| Other crime types (narcotics, weapons, damage, disorder, indoor violence) | Yes | +0.01, and it helps the top 1% |
| Severity-weighted crime history | Yes | 0.00 alone, helps the severity-weighted metric |
| Park District big-event permits | Yes | 0.00 alone, but used when combined |
| Weather (Open-Meteo) | **No** | +0.03 even with the actual weather of the week, which a real forecast can't match, and the free forecast API is blocked here |
| OpenStreetMap (Overpass) | **Not tested** | Still blocked by the network. Business licences cover the same ground (bars, late-night places) with real history. |

- **All sources combined:** +0.17 on 2023.
- **Longer training (from 2017 instead of 2020):** another +0.08.
- **Severity-weighted target** (training on harm instead of counts): this lifts the harm metric but costs more on plain crime counts, so the model keeps the count target with severity-weighted history as inputs.
- **Neural net** (`ai_options/run_neural.py`): about level with LightGBM on its own. Blended at 70/30, it adds +0.15 on 2023 (better in 48 of 52 weeks) and +0.15 on 2024 (46 of 52), so it is kept. The 70/30 weight was picked on 2023.

## Routes, checked against real 2024 crimes

The test is the original one: 250 random 1 to 4 km walks, with the risk map built only from 2015 to 2023 data, counting real 2024 crimes within 50 m of each route.

- **New map vs old map:** about the same. At +4% extra distance, weekday 8 pm to 12 am routes pass 43.4% fewer crimes with the new map vs 43.1% with the old one. Weekend 12 to 4 am routes pass 49.7% vs 48.4%. The prediction gain is too small to show up in a 250-route test.
- **Worst-block scoring** (penalising the riskiest blocks harder, cost = length × (1 + λ × risk^p) with p = 1.5 or 2): **no better** than the app's linear rule at the same extra distance. At +4% distance it gives 39 to 42% fewer crimes vs 43%. So `03-routing.md` stays as it is. The worst block on a route is already cut from about 2.7× to 1.7× the city average at Balanced.
- Full tables are in `results/route_summary2.csv` and `results/route_matched2.csv`.

## Files

- `pipeline/`: all the code.
  - `download_all.py` fetches every source.
  - `sources.py` and `features.py` build the features.
  - `experiments.py` runs the 2023 and 2024 tests.
  - `stnet.py` is the neural net.
  - `train_weekly.py` is the weekly job.
  - `export_data.py` writes the app files using `reference/safewalk_format.py`.
  - `model_config.json` holds the chosen setup.
  - `.github/workflows/weekly.yml` is the free weekly run.
- `sample-output/v1/chicago/`: a real data folder for the week of 19 Oct 2026 (manifest, graph and risk file). It passes `safewalk_format.py validate`.
- `results/`: every experiment (`experiments.csv`), feature importance and route tables.

## Weekly retraining, free

- **Where it runs:** GitHub Actions on a **public** repository, which gets unlimited free minutes. GitHub Pages serves the files at the URL the app spec expects (`https://prem-kumar-dev.github.io/SafeWalk/v1/chicago/`), with jsDelivr as the mirror.
- **Setup:** this repository already has it: `pipeline/`, the workflow in `.github/workflows/weekly.yml`, and the published `v1/chicago/`. In a fresh copy:
  1. Keep the repository public (free minutes, and Pages on a free plan).
  2. Keep `pipeline/` and `.github/workflows/weekly.yml` where they are.
  3. Turn on Pages (Settings → Pages → Deploy from branch `main`, root).
  4. Optionally add a free Socrata app token as the secret `SOCRATA_APP_TOKEN`.
- **What a run does:** every Monday at 09:00 UTC it downloads the new data, runs the quality check below, retrains, validates and commits `v1/chicago/`. It fails instead of publishing if the quality check or validation fails, and the app keeps last week's forecast. Risk files stay published for 8 weeks.
- **Quality check:** the same recipe is trained as if it were 8 weeks ago and scored on the 8 weeks since, next to the tuned 5-year KDE. If its AUC is below the KDE's, nothing is published. The table lands in the run's summary page and in `pipeline/out/gate.json`. The weeks and margin are `gate` in `model_config.json`; a manual run with **skip_gate** publishes anyway.
- **Alerts:** a failed run opens an issue labelled `weekly-retrain-failed`, assigned to the repository owner, with the log link and the quality-check table. GitHub emails the assignee. Further failures comment on the same issue, and the next good run closes it.
- **Run time:** about 50 minutes on 4 cores here for the forecast, plus about as much again for the quality check. The job allows 5 hours.

## Limits

- The data portal lags about a week, so the forecast for a Monday uses data through the Sunday 15 days earlier.
- Reported crime only, so it is shaped by policing and reporting patterns.
- 311 outage reports reflect who reports as well as where lights are out.
