# SafeWalk

Safer walking routes for Chicago. A weekly crime-risk model scores every street block, and the Android and iOS apps use that score to suggest a walking route that avoids the riskiest blocks for a small extra distance.

## Repository layout

| Folder | What it holds |
|---|---|
| `pipeline/` | The weekly risk model: data download, features, LightGBM + neural net blend, and export of the app data files. See `pipeline/README.md`. |
| `v1/chicago/` | The live data files the apps download (manifest, street graph, weekly risk file). Rewritten every Monday by the workflow and served by GitHub Pages. |
| `.github/workflows/weekly.yml` | The free weekly retrain job (Mondays 09:00 UTC). |
| `model/` | Model write-up (`model/README.md`), experiment results, and a frozen sample of the data output. |
| `app-spec/` | The app specification: screens, data file format, routing rules, test fixtures and the Python reference implementation of the format (`reference/safewalk_format.py`). |
| `android/` | Native Android app (Kotlin, Jetpack Compose). See `android/README.md` and `android/RUNNING.md`. |
| `ios/` | Native iOS app (SwiftUI, generated with XcodeGen from `project.yml`). See `ios/README.md` and `ios/RUN_ON_MAC.md`. |
| `ai_options/` | Notes and experiments on other AI model options for the risk map. |
| `notebooks/` | The original Chicago analysis notebook and its code as a script. |
| `docs/` | Project summary (Delhi first attempt and Chicago main project) and the HTML app template. |

## Data URL

The iOS app reads `https://prem-kumar-dev.github.io/SafeWalk/v1/chicago/manifest.json`, updated weekly. The Android app currently ships the data bundled in its assets.
