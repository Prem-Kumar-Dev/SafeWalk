# 03 · On-device routing

All routing runs on the phone, offline, against the graph and risk files from [02-data-files.md](02-data-files.md). No server is involved, and the user's start and destination never leave the device. The reference implementation is `route()` and `snap()` in [`reference/safewalk_format.py`](reference/safewalk_format.py); the apps must give the same answers on [`fixtures/expected_routes.json`](fixtures/expected_routes.json).

## Load

On load, build once and keep in memory (well under 10 MB for Chicago):

- Node arrays `lat[]`, `lon[]` (Double or Float, degrees).
- Edge arrays `u[]`, `v[]`, `len[]`, plus `shapeOff[]` and shape points.
- Adjacency in CSR form: for each node, a contiguous run of `(neighbour, edge)` pairs. Each undirected edge appears twice.
- A **snap index**: a uniform grid of 200 m buckets over the bbox; each bucket lists the edges whose polyline bounding box touches it.
- Local metres: `x = (lon − lon_ref) × 111320 × cos(lat_ref)`, `y = (lat − lat_ref) × 110950`, with `lat_ref` the centre of the bbox. Good to well under 1% across Chicago, which is all the snapping and the heuristic need. Edge lengths always come from the file, never from this.

Do the load and every route search off the main thread.

## 1. Pick the time slot

From the departure time the user chose ("Leave now" or a set time), converted to `America/Chicago` wall-clock time:

```
slot = (isSaturdayOrSunday ? 6 : 0) + hour / 4      // integer division
```

A walk that crosses a slot boundary uses the departure slot (walks are short; this is what the evaluation tested).

## 2. Snap start and end to the street

For a point P (GPS fix, map tap, or search result):

1. If P is outside the manifest `bbox` expanded by 200 m: "outside the covered area".
2. Look in P's bucket and the 8 around it. For every candidate edge, project P onto each straight piece of its polyline and keep the closest point. Record `(edge, frac, distance)`, where `frac` is the distance along the polyline from `edge_u` to the projected point divided by the polyline's total length (0 at u, 1 at v).
3. Ties within 0.01 m go to the lower edge index.
4. If the best distance is more than **150 m**, the point is not near a walkable street; say so and do not route.

Snapping to the nearest point on an edge, not the nearest junction, matters: in Chicago a junction can be 200 m away down the block.

## 3. Search

Edge cost, for safety level λ:

```
cost(e) = len[e] × (1 + λ × risk[e*12 + slot] / 100)
```

| Level (UI) | λ | Measured in testing (250 random 1–4 km walks, real 2024 crimes) |
|---|---|---|
| Direct | 0 | Shortest route |
| **Balanced** (default) | 0.5 | +3.5% to +4.5% distance, 44–51% fewer real crimes passed |
| Lowest risk | 1.0 | +6.4% to +7.3% distance, 45–51% fewer |

Virtual nodes: add start S and target T. S connects to both ends of its snapped edge `ea`: to `u` at `cost(ea) × fa`, to `v` at `cost(ea) × (1 − fa)`. T is joined the same way from the ends of `eb`. If `ea == eb`, also add a direct S→T link at `cost(ea) × |fb − fa|`.

Run **A\*** from S to T with heuristic `h(n) = 0.999 × straightLineMetres(n, Tpoint)`, where `Tpoint` is the snapped target point on its polyline. Because every cost is at least the edge length, and every edge length is at least the straight line (the validator checks this), the heuristic never overestimates and A\* returns the true cheapest route. Use a binary heap. On Chicago's graph a cross-city search settles a few thousand nodes and takes a few milliseconds on a mid-range phone; Dijkstra (h = 0) is an acceptable fallback and gives identical routes.

Compute **two routes for every request**: Direct (λ = 0) and the user's chosen level. If the chosen level is Direct, show only one.

## 4. Route result

Walk the predecessor chain back from T to get the ordered list of pieces `(edge, fromFrac, toFrac)`; full edges are `(e, 0, 1)` or `(e, 1, 0)`, the first and last may be partial. From it:

| Value | How |
|---|---|
| Polyline | Each piece's sub-polyline in walking order, joined, consecutive duplicate points dropped. |
| Length | Σ `len[e] × |toFrac − fromFrac|`. |
| Walking time | Length ÷ 1.3 m/s, rounded to the minute. |
| Exposure | Σ piece length × `risk/100`. Used only to compare two routes. |
| Risk reduction | `1 − exposure(safer) / exposure(direct)`, shown as a whole percent. |
| Higher-risk stretches | Number of pieces at least 25 m long with `risk ≥ thresholds.high[slot]`. |
| Extra distance | `length(safer) / length(direct) − 1`, shown as metres and minutes. |
| Turn list | Merge consecutive pieces with the same street name; a new step starts where the name changes. Unnamed pieces take the name "path". Turn direction from the bearing change at the joint: < 30° straight, 30–150° left/right, > 150° U-turn. |

Rules for what to show:

- If the safer route is the same edge list as the direct route, or its risk reduction is under 10%: show one route and say "The direct route is already the lower-risk choice at this time."
- If the safer route is more than 25% longer than direct, still show it, and show the extra minutes prominently so the user can decide.
- Never show raw risk numbers, "× city average", or the thresholds.

### Route explanation

One plain sentence saying why the route is shown, filled from the numbers above (templates only, no AI). Both apps build it in their routing core (`RouteExplanation` in `android/core` and `ios/SafeWalk/Core`) and must produce identical text.

- **Higher-risk blocks avoided**: the direct route's higher-risk stretches whose edge the safer route does not use.
- **Street**: the named street holding more than half of those blocks; "on X" if it holds all of them, ", mostly on X" otherwise, nothing if no street has more than half.
- **Longer**: `round((length(safer) / length(direct) − 1) × 100)`; 0 reads "about the same distance".

| Case | Sentence |
|---|---|
| Safer route shown, blocks avoided | "Avoids 3 higher-risk blocks on S State St, 4% longer." |
| Safer route shown, no high blocks avoided | "Takes lower-risk streets for 23% lower predicted risk, 6% longer." |
| Safer level asked, direct is already lower-risk | "The direct route is already the lower-risk choice at this time." |
| Level Direct, blocks on the route | "Shortest route; passes 2 higher-risk blocks on W Madison St." |
| Level Direct, none | "Shortest route, with no higher-risk blocks at this time." |
| No forecast loaded | "Shortest route. No risk forecast is loaded, so streets aren't compared." |

## 5. Route line colours

Draw the chosen route as one line, coloured per piece by the same slot's thresholds: below `elevated` normal route colour, `elevated` to `high` amber, `high` and above deep orange. The direct route is drawn under it in grey when both are shown. Nothing outside the user's own routes is ever coloured by risk.

## 6. While walking

- Follow the user's position (blue dot, heading). Snap the GPS fix to the route polyline for display only.
- **Off route**: more than 40 m from the route for 15 seconds. Offer "Find a new route from here", which re-runs steps 2–4 from the current position with the same level and the slot for the current time. Never reroute silently.
- **Arrived**: within 25 m of the destination point.
- Keep the screen awake only while walking mode is open. Location runs in the foreground only (no background location in version 1).

## 7. Edge cases

| Situation | Behaviour |
|---|---|
| Start or end outside the bbox | "Lanternway covers Chicago only for now." |
| Start or end more than 150 m from a walkable street | "Move the pin closer to a street." |
| Start and end snap within 20 m of each other | "You're already there." No route. |
| Trip longer than 15 km | Allowed, with a note that it is a long walk. |
| No risk data matches the graph | Route Direct only and say the forecast is not available. Never mix files. |
| Location permission denied | Start defaults to a map pin the user drops; everything else works. |
