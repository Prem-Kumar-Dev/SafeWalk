# Lanternway app spec (version 1)

Lanternway is an indie app for **native Android and native iOS** that suggests walking routes in Chicago passing fewer reported street crimes, with a clear "lower risk, not a guarantee" message. Everything runs on free services: a weekly GitHub Actions job publishes static files to GitHub Pages, the phone downloads them and does all routing itself, and maps come from MapLibre with OpenFreeMap (OpenStreetMap) tiles. Chicago is the only city at launch.

| File | For | What's in it |
|---|---|---|
| [01-screens.md](01-screens.md) | Android, iOS | Every screen, the exact wording, wording rules, free services used |
| [02-data-files.md](02-data-files.md) | Pipeline, Android, iOS | `manifest.json`, the street-graph file, the weekly risk file, hosting, update rules |
| [03-routing.md](03-routing.md) | Android, iOS | Time slot, snapping, A\* cost, safety levels, route summary, walking mode |
| [reference/safewalk_format.py](reference/safewalk_format.py) | Everyone | Executable version of 02 and 03: writer, reader, validator, reference router |
| [fixtures/](fixtures/) | Android, iOS | A tiny valid data folder plus golden route answers for unit tests |

## How the pieces connect

```
 GitHub Actions (weekly, Mon ~09:00 UTC)               phone (offline after download)
 ┌───────────────────────────────────────┐            ┌──────────────────────────────────┐
 │ Chicago open data → LightGBM forecast │            │ manifest.json (ETag, ≤ every 6 h) │
 │ → risk per edge × 12 slots            │  GitHub    │ graph-<id>.bin   (rarely changes) │
 │ → graph-<id>.bin, risk-<week>.bin     │ ─Pages───▶ │ risk-<week>.bin  (weekly)         │
 │ → validate → manifest.json            │            │ snap → A* → two routes → map      │
 └───────────────────────────────────────┘            └──────────────────────────────────┘
```

## What each thread owns

- **Model / pipeline thread** produces, every week, a folder that passes `python3 reference/safewalk_format.py validate <folder>`: `manifest.json`, `style.json`, one `graph-<graph_id>.bin`, one `risk-<week_start>-<hash>.bin`. Use `encode_graph`, `encode_risk`, `thresholds_from` and `write_manifest` from the reference module rather than re-implementing the byte layout. Edge order comes from the graph, and the risk rows must follow it.
- **Android and iOS threads** read those files, pass every case in `fixtures/expected_routes.json`, and build the screens in 01. Bundle the latest real graph and risk file in the app at build time.
- **This spec** changes only by editing these files. A change to the byte layout bumps `format_version` and keeps version 1 published until both apps support the new one.

## Decisions taken here

- Edge-level risk (not cells) is what the apps download, so the phone never needs the model, the grid or the projection. Risk is relative to the city average **per time slot**, stored × 100 as u16.
- Coordinates are lat/lon microdegrees, so MapLibre and the platform search use them directly.
- Raw DEFLATE, because both platforms inflate it with built-in APIs and no extra library.
- Three safety levels in the UI (Direct 0, Balanced 0.5, Lowest risk 1.0) instead of the demo's five; the two extra levels added little in testing (Strong was +1 point for +3% distance; Maximum was never evaluated).
- No citywide risk map in the app, only colouring along the user's own route, to avoid labelling neighbourhoods.
- No accounts, no analytics, no backend; store privacy labels can say "Data not collected".
- Expected sizes for Chicago: graph about 1 MB and risk about 0.5 MB compressed (estimated from the demo's 33k nodes and 51k edges; the pipeline thread should confirm on the first real build).
