# Lanternway for Android

Native Android app (Kotlin, Jetpack Compose, MapLibre with OpenFreeMap tiles) built to `../app-spec/`.

## Layout

| Path | What |
|---|---|
| `core/` | Plain Kotlin/JVM: data-file reader and checks, snapping, A* router, trip summary, walking progress. No Android code, so it runs in unit tests. |
| `app/` | The Android app: screens, MapLibre map, location, place search, data download. |
| `app/src/main/assets/data/` | Data bundled with the app so the first launch works offline (see "Bundled data" below). |
| `tools/` | `demo_to_v1.py` (makes format-v1 files from the web demo's data) and `city_golden.py` (reference routes for the full-city test). |

## Build and run

Open this folder in Android Studio (Ladybug or newer) and run the `app` configuration, or:

```
./gradlew :app:installDebug
```

Versions: AGP 8.11.1, Kotlin 2.2.0, Compose BOM 2025.07.00, MapLibre Android 13.7.1, compileSdk/targetSdk 35, minSdk 26.
If Android Studio offers to upgrade AGP or the Compose BOM, accepting is fine.

## Tests

```
./gradlew :core:test                                     # spec fixture: every case in expected_routes.json
./gradlew :core:test -PsafewalkData=<folder>             # plus full-city routes vs the Python reference
```

`-PcoreOnly` skips the app module (useful on machines without the Android SDK; you also need a root build file
without the Android plugins there).

Status on 18 Oct 2026: all 5 fixture tests pass, and on the full Chicago graph (33,168 nodes, 51,096 edges) the
Kotlin router returns exactly the reference router's edges, lengths and costs for 6 random 1–6 km trips at all
three levels, in 0.4–4.5 ms per route on a laptop JVM. Load + index takes about 0.2 s.

The app module itself has **not been compiled yet**: the cloud machine it was written on can't reach Google's
Maven repository. Expect a few small compile fixes on the first Android Studio build.

## Data

- Downloads `manifest.json` from `BuildConfig.DATA_BASE` (GitHub Pages), falling back to `DATA_MIRROR` (jsDelivr),
  at most every 6 hours, with ETag. Both are set in `app/build.gradle.kts` and point at `v1/chicago/` in
  `Prem-Kumar-Dev/SafeWalk`, where the weekly job publishes.
- Verifies size, SHA-256, raw size, graph id and edge counts before swapping graph and risk in together
  (`DataRepository`). A bad download never replaces working data. A bad risk file means Direct-only routing.
- `style.json` next to the manifest can switch the map style without an app release.

### Bundled data

`app/src/main/assets/data/` holds a copy of the published `v1/chicago/` folder (model 2026.43, week of 19 Oct 2026):
the manifest, the graph and risk files it names, and `style.json`. The app replaces it with newer weeks on its own;
the bundled copy only matters for the first launch offline. To refresh it for both apps before a release, run this
from the repo root, then `python3 app-spec/reference/safewalk_format.py validate <folder>` on each:

```
python3 - <<'PY'
import json, shutil, glob, os
src = "v1/chicago"; m = json.load(open(f"{src}/manifest.json"))
for dst in ["android/app/src/main/assets/data", "ios/SafeWalk/Data"]:
    for f in glob.glob(f"{dst}/*"): os.remove(f)
    for f in ["manifest.json", "style.json", m["graph"]["path"], m["risk"]["path"]]: shutil.copy(f"{src}/{f}", dst)
PY
```

## Spec decisions followed

- No risk colouring anywhere except along the user's own route; higher-risk stretches are dashed as well as coloured.
- Three levels: Direct, Balanced (default, remembered), Lowest risk.
- Place search uses the phone's built-in `Geocoder`, limited to the manifest bbox. No accounts, analytics or backend.
- Location is foreground only, through the platform `LocationManager` (no Google Play services dependency).
- "Call 911" opens the dialer with the number filled in; it never dials by itself.

## Before release

- Set `feedback_email` in `res/values/strings.xml` (the About screen hides the line while it's empty).
- Refresh the bundled data (see "Bundled data").
- Add a release signing config.
