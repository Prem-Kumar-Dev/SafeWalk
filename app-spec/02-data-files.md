# 02 · Data files (format version 1)

The apps download three things: a small `manifest.json`, a **street graph** file that changes rarely, and a **risk** file that changes every week. The model-training pipeline writes them; the apps only read them.

The executable definition is [`reference/safewalk_format.py`](reference/safewalk_format.py). It writes, reads and validates every byte described here. If this page and that file disagree, the file is right and this page is fixed.

## Hosting and URLs

- Hosted on **GitHub Pages** from a public data repository (free). Base URL is a build-time constant in each app:
  `DATA_BASE = https://<github-user>.github.io/safewalk-data/v1/chicago/`
- Mirror for when Pages is slow or blocked: `https://cdn.jsdelivr.net/gh/<github-user>/safewalk-data@main/v1/chicago/` (also free). Apps try Pages first, then the mirror.
- Paths in the manifest are relative to the folder the manifest was loaded from.
- Graph and risk file names contain a hash of their content, so a given URL never changes content. Apps may cache them forever. Only `manifest.json` changes in place.
- Old risk files stay published for 8 weeks so an app holding an older manifest can still download what it names.

## manifest.json

```json
{
  "format_version": 1,
  "min_app_format": 1,
  "city": "chicago",
  "display_name": "Chicago",
  "timezone": "America/Chicago",
  "bbox": [41.6445, -87.9401, 42.0230, -87.5240],
  "generated_at": "2026-10-19T09:00:00Z",
  "graph": {
    "path": "graph-878dc684a680fc8d.bin",
    "graph_id": "878dc684a680fc8d",
    "bytes": 1043211, "raw_bytes": 2120552,
    "sha256": "…64 hex…"
  },
  "risk": {
    "path": "risk-2026-10-19-bbdc2dc20596.bin",
    "week_start": "2026-10-19", "valid_until": "2026-10-26",
    "bytes": 512334, "raw_bytes": 1226488,
    "sha256": "…64 hex…"
  },
  "model": { "name": "lightgbm-poisson", "version": "2026.42", "trained_through": "2026-10-11" }
}
```

| Field | Meaning |
|---|---|
| `format_version` | Format of the files this manifest points to. Always 1 for now. |
| `min_app_format` | Lowest file format an app must understand. If an app supports less, it keeps its cached data and shows "Update the app to get new forecasts". |
| `bbox` | `[min_lat, min_lon, max_lat, max_lon]` of the street graph. Used to decide "outside the covered area" before snapping, and to bias place search. |
| `timezone` | The time zone used to pick a time slot. Always `America/Chicago` for Chicago, whatever the phone's own zone is. |
| `graph.graph_id` | First 16 hex chars of SHA-256 of the **decompressed** graph. The risk file repeats it. |
| `bytes`, `sha256` | Of the file as downloaded (compressed). Apps check both before using a file. |
| `raw_bytes` | Size after decompression. Apps check it. |
| `risk.week_start` | Monday (local date) the forecast is for. `valid_until` is the next Monday. |
| `model` | Shown on the About screen only. |

Apps ignore fields they do not know, so new fields can be added without a version bump.

Next to `manifest.json` the folder also holds `style.json`: `{"map_style_url": "https://tiles.openfreemap.org/styles/liberty"}`. Apps read it with the manifest and fall back to the built-in default if it is missing, so the map provider can be changed without an app release.

## Compression

Both binary files are **raw DEFLATE** (RFC 1951, no zlib or gzip header).

- iOS: `try (data as NSData).decompressed(using: .zlib)` (iOS 13+; Apple's `.zlib` is raw DEFLATE).
- Android: `java.util.zip.Inflater(true)` (`nowrap = true`).
- Python: `zlib.decompress(blob, -15)`.

After decompression all numbers are **little-endian**. Arrays follow each other with no gaps; section order is chosen so every array is aligned to its element size, so a decoder can view them in place (`ByteBuffer.order(LITTLE_ENDIAN).asIntBuffer()`, `withUnsafeBytes`) without copying.

## Street graph file (`SWG1`)

An undirected walking graph: junctions are nodes, street segments between junctions are edges. Built from City of Chicago street centerlines (dataset `pr57-gg9e`), keeping road classes 2, 3, 4, 5, 7 and S, dropping expressways, ramps, runways and river lines, then keeping only the largest connected piece. Today: about 33,000 nodes and 51,000 edges, about 1 MB compressed.

Header, 44 bytes:

| Offset | Type | Field |
|---|---|---|
| 0 | 4 bytes | magic `SWG1` |
| 4 | u16 | format version = 1 |
| 6 | u16 | flags = 0 (reserved) |
| 8 | u32 | `n_nodes` |
| 12 | u32 | `n_edges` |
| 16 | u32 | `n_shape` (number of intermediate shape points) |
| 20 | u32 | `n_names` |
| 24 | u32 | `names_bytes` |
| 28 | 4 × i32 | bbox: min_lat, min_lon, max_lat, max_lon, in microdegrees (degrees × 10⁶) |

Sections, in this order:

| Section | Type × count | Content |
|---|---|---|
| nodes | (i32, i32) × `n_nodes` | lat, lon of each junction, microdegrees |
| edge_u | u32 × `n_edges` | first node of each edge |
| edge_v | u32 × `n_edges` | second node of each edge |
| edge_length | f32 × `n_edges` | walking length in metres along the street's shape (≥ 1, and ≥ the straight line between its ends) |
| shape_off | u32 × (`n_edges` + 1) | edge *e*'s intermediate points are `shape[shape_off[e] ..< shape_off[e+1]]` |
| shape | (i32, i32) × `n_shape` | lat, lon of intermediate points, microdegrees, ordered from `edge_u` to `edge_v`, endpoints **not** repeated |
| edge_name | u16 × `n_edges` | index into names; `0xFFFF` means unnamed |
| edge_class | u8 × `n_edges` | 0 unknown, 1 arterial (city class 2), 2 collector (3), 3 local street (4), 4 other walkable (5, 7, S) |
| padding | 0–3 zero bytes | so names start on a multiple of 4 |
| names | UTF-8, `names_bytes` long | `n_names` street names joined with `\n`, e.g. `W Madison St` |

The file ends exactly after `names`.

Edge *e*'s full polyline is: node `edge_u[e]`, its shape points, node `edge_v[e]`. Walking from v to u means reading it backwards.

Edge indices are the join key with the risk file, so the order of edges is fixed by the graph file and never re-sorted by an app.

The graph is rebuilt only when the street data changes (planned monthly, usually identical). A new graph gets a new `graph_id`, and the risk file published with it uses that id.

## Risk file (`SWR1`)

Predicted street-crime risk for every edge in each of the 12 time slots of one week.

Header, 36 bytes:

| Offset | Type | Field |
|---|---|---|
| 0 | 4 bytes | magic `SWR1` |
| 4 | u16 | format version = 1 |
| 6 | u16 | `n_slots` = 12 |
| 8 | u32 | `n_edges` (must equal the graph's) |
| 12 | 16 bytes ASCII | `graph_id` this file was made for |
| 28 | u32 | `week_start`, days since 1970-01-01 (a Monday) |
| 32 | u16 | `scale` = 100 |
| 34 | 2 bytes | zero padding |

Sections:

| Section | Type × count | Content |
|---|---|---|
| thresholds | u16 × 3 × 12 | row 0 elevated, row 1 high, row 2 very high; one value per slot |
| risk | u16 × `n_edges` × 12 | edge-major: risk of edge *e* in slot *s* is at `e * 12 + s` |

The file ends exactly after `risk`.

### What a risk value means

`risk / scale` is the edge's predicted rate of street crime **relative to the city average for that same time slot**. 1.00 is average; 3.00 is three times the average. Values are relative per slot, so a 1.0 at 2 am and a 1.0 at noon are not the same absolute danger, and the app never compares across slots.

How the pipeline computes it (from `export_app.py`): the LightGBM model predicts incidents per hour for each 250 m cell and slot; each edge takes the mean over points every 25 m along its shape; each slot is divided by the length-weighted mean over all edges, so the city average is exactly 1.0; then × 100 and rounded. The validator checks the length-weighted mean is 1.00 ± 0.02 in every slot.

### Thresholds

Cut points in the same units, one set per slot, chosen by street length: the riskiest 20% of street length is at or above `elevated`, 5% at or above `high`, 1% at or above `very_high`. Apps use them only to count "higher-risk blocks" on a route and to colour the route line; they are never shown as numbers.

### Time slots

`slot = (weekend ? 6 : 0) + floor(hour / 4)`, using the wall-clock time in `America/Chicago`. Weekend means Saturday or Sunday (Friday night is a weekday slot, matching how the model was trained).

| Slot | Day | Hours | Slot | Day | Hours |
|---|---|---|---|---|---|
| 0 | Weekday | 12–4 am | 6 | Weekend | 12–4 am |
| 1 | Weekday | 4–8 am | 7 | Weekend | 4–8 am |
| 2 | Weekday | 8 am–12 pm | 8 | Weekend | 8 am–12 pm |
| 3 | Weekday | 12–4 pm | 9 | Weekend | 12–4 pm |
| 4 | Weekday | 4–8 pm | 10 | Weekend | 4–8 pm |
| 5 | Weekday | 8 pm–12 am | 11 | Weekend | 8 pm–12 am |

## How an app updates

1. On launch and on return to the foreground, at most every 6 hours: `GET manifest.json` with `If-None-Match` (ETag). 304 means nothing to do.
2. If `min_app_format` > 1: keep cached data, show the update notice, stop.
3. If `graph.graph_id` differs from the cached graph: download the graph. Then download the risk file. Verify `bytes`, `sha256`, decompress, verify `raw_bytes`, magic, version, that `risk.graph_id` equals the graph's id, and `risk.n_edges == graph.n_edges`.
4. Only after every check passes, swap graph and risk in **together** (write to temp files, then rename). A failed download never replaces working data.
5. Never use a risk file with a graph it was not made for. If they disagree, keep the old pair.

The app bundle ships with a graph and the latest risk file at build time, so the first launch works offline and the app is never empty.

### Old forecasts

Crime locations change slowly (most of the model's signal is 3–5 year history), so an old forecast is still useful, but the app is honest about it:

| Age of `week_start` | App behaviour |
|---|---|
| ≤ 13 days | Normal. |
| 14–55 days | Small note on the route sheet: "Forecast from the week of 19 Oct". |
| ≥ 56 days | Banner: "This forecast is out of date. Connect to the internet to update." Routing still works. |

## Checking a published folder

The pipeline must run this before it publishes, and fail the job if it prints FAIL:

```
python3 reference/safewalk_format.py validate <folder-with-manifest.json>
```

It checks sizes, hashes, magic, ids, edge counts, Monday start, node indices, edge lengths against straight lines (A* depends on it), shape offsets, name indices, road classes, one connected component, a city average of 1.0 per slot, and ordered thresholds.

## Test fixture

[`fixtures/`](fixtures/) is a complete, valid data folder for a 7 × 7 block grid near the Loop, with one bent street, one unnamed street, and a higher-risk band down the middle at night. [`fixtures/expected_routes.json`](fixtures/expected_routes.json) holds the reference answers (time slot, snapped edge and fraction, route edges, length, cost, exposure) for five trips at all three safety levels, including a same-edge trip and a point outside coverage. Both apps should run these as unit tests. Rebuild it with `python3 reference/safewalk_format.py fixture fixtures`.
