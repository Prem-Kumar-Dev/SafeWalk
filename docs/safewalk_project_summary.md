# SafeWalk: crime hotspot prediction and safer walking routes

Project summary for context. Last updated 18 Oct 2026.

## Goal
Use historical crime data to predict which areas are unsafe at which time of day, then use those predictions in an app that warns users and suggests a safer detour.

## Two parts of the project
1. **Delhi (first attempt).** The only public incident-level Delhi data is masked: coordinates are rescaled to 0–1 and dates are replaced by day numbers. The model can be trained and evaluated, but predictions cannot be placed on real streets.
2. **Chicago (main project).** Ten years of official crime data with real locations, plus the city's street network, so the safer-route app works on real roads.

---

## Part 1: Delhi

**Data:** 45,601 verified PCR (100/112) calls about street crime (snatching and robbery), Oct 2019 to Mar 2021, released with the paper *Predictive Hotspot Mapping for Data-driven Crime Prediction* (Sriram, Sinha and Choudhary, IIM Ahmedabad, 2026, arXiv 2602.23750), made with Delhi Police.
Kaggle: https://www.kaggle.com/datasets/ankurzing/spatio-temporal-crime-incidents
Columns: `day_of_crime` (1–555), `time_of_crime` (0–24 h, real), `masked_longitude`, `masked_latitude` (0–1, masked).

**Setup:** 200 × 200 grid (about 250 m cells), six 4-hour windows, weekly blocks; the last 25 weeks are the test set.

**Patterns:** peak at 8–11 pm; 58% of incidents after 4 pm; sharp drop around the March 2020 COVID lockdown.

**Results** (share of next week's incidents inside the riskiest X% of the city):

| Model | Top 5% | Top 20% |
|---|---|---|
| Last week's spots | 7.4% | 21.5% |
| Spatial KDE, default bandwidth | 25.4% | 60.3% |
| Tuned spatio-temporal KDE | 36.1% | 70.8% |
| LightGBM | 37.1% | 70.8% |

LightGBM was better than the tuned KDE in the top 5% in 20 of 25 test weeks, but only by about 1 point.

**Limitation:** masked coordinates. Real coordinates would need the paper's authors or Delhi Police. The data covers Delhi NCT only (not Noida, Greater Noida or Gurugram).

---

## Part 2: Chicago (main project)

### Data (City of Chicago open data portal)
- **Crimes:** dataset `ijzp-q8t2` (Crimes 2001 to Present), filtered to 2015–2024. **264,434 street crimes.**
  - Types: robbery, battery, assault, criminal sexual assault, homicide, kidnapping, theft (pickpocketing and purse snatching only).
  - Places: street, sidewalk, alley, non-residential parking lot, park, gas station, vacant lot, bridge, highway, lakefront, and all CTA transit locations.
  - Domestic incidents are excluded because they are not a risk to passers-by.
  - Per year: 2015 27,069 · 2016 30,687 · 2017 29,621 · 2018 27,608 · 2019 25,864 · 2020 22,303 · 2021 23,371 · 2022 24,558 · 2023 27,138 · 2024 26,215.
- **Streets:** dataset `pr57-gg9e` (transportation centerlines), 56,338 segments with node IDs. Expressways, ramps, runways and river lines are removed for walking, leaving 33,168 junctions, 51,096 segments and 6,406 km of street.

### Method
- **Grid:** 250 m × 250 m cells over a 36 × 44 km box. City area = the 9,128 cells crossed by streets (about 570 km²).
- **Time:** 12 slots = weekday or weekend × six 4-hour windows (12–4 am, 4–8 am, 8 am–12 pm, 12–4 pm, 4–8 pm, 8 pm–12 am). Predictions are made one week ahead using only earlier weeks.
- **Split:** training targets Jan 2021 to Sep 2023 (143 weeks, with history back to 2015); validation Oct–Dec 2023 (13 weeks, used for tuning and early stopping); **test = all 52 weeks of 2024**, never used for any choice.
- **Metric:** rank cells from riskiest to safest and measure the share of next week's crimes inside the top 1%, 5% or 20% of the city, plus the area under that curve (AUC). Plain accuracy is misleading because almost every cell has zero crimes in a given week.
- **Baselines:** last week's spots; spatio-temporal kernel density (KDE) with 1-, 3- and 5-year history, bandwidths tuned on validation. The best was a 5-year history, 0.25-cell spatial bandwidth and a wide time bandwidth.
- **Model:** LightGBM (Poisson objective). Features: crime rates over the last 1, 4, 13, 52, 156 and 260 weeks at several smoothing levels; KDE signals; the same season one and two years earlier; street length by road type per cell; weekday or weekend; time window. Empty rows are down-sampled to 8% and re-weighted.

### Patterns found
- Crime is concentrated: the busiest 5% of cells that ever had a street crime hold 30.7% of all incidents.
- **Weekday peak 3–5 pm** (around school dismissal); **weekend-night peak 12–3 am**. Weekend 12–4 am averages 17.8 incidents per day vs 9.1 on weekdays.
- 37.1% of incidents happen between 8 pm and 4 am.
- Strong summer peak every year; dip in 2020–21; 2024 is a normal year.

### Prediction results on 2024
| Model | Top 1% | Top 5% | Top 20% | AUC |
|---|---|---|---|---|
| Last week's spots | 3.1% | 6.6% | 21.7% | 0.512 |
| Tuned KDE, 1-year history | 14.5% | 32.6% | 63.3% | 0.804 |
| Tuned KDE, 3-year history | 14.5% | 33.2% | 65.2% | 0.816 |
| Tuned KDE, 5-year history | 14.6% | 33.7% | 65.7% | 0.819 |
| **LightGBM** | **14.9%** | **33.8%** | **66.1%** | **0.822** |

- **Ten years of data help:** a 5-year history beats a 1-year history (65.7% vs 63.3% in the top 20%).
- **LightGBM is only slightly better than the tuned KDE:** better AUC in 39 of 52 test weeks, mean gain +0.3 points. Feature importance is dominated by long-history crime rates (5-year KDE 42.5%, 5-year same-slot rate 17.7%, 3-year KDE 12.2%). Crime locations are very stable, so a well-tuned classical method is close to the ceiling.

### Routing results (the main finding)
- **Routing rule:** cost = length × (1 + level × street risk / city-average risk). Level 0 is the shortest route; "Balanced" = 0.5.
- **Test:** 250 random 1–4 km walks. The risk map comes from **2015–2023 data only**. We count **real 2024 crimes within 50 m** of each route, in the same time slot.
- **Controls:** two risk-blind detours of similar length, "avoid main roads" (penalise arterials and collectors) and "random detour."

| Slot | Method | Extra distance | Fewer real 2024 crimes (95% CI) |
|---|---|---|---|
| Weekday 8 pm–12 am | Risk-aware, Balanced | +3.5% | **44.1%** (37.3–50.5) |
| Weekday 8 pm–12 am | Risk-aware, Strong (1.0) | +6.4% | 45.3% |
| Weekday 8 pm–12 am | Avoid main roads | +4.9% to +7.8% | about 20% |
| Weekday 8 pm–12 am | Random detour | +1.9% to +6.4% | about 2–3% (not significant) |
| Weekend 12–4 am | Risk-aware, Balanced | +4.5% | **51.2%** (41.5–59.6) |
| Weekend 12–4 am | Risk-aware, Strong (1.0) | +7.3% | 51.2% |
| Weekend 12–4 am | Avoid main roads | +4.9% to +7.8% | about 22% |
| Weekend 12–4 am | Random detour | +1.9% to +6.4% | about 6–8% (not significant) |

**Conclusion:** routes about 4–5% longer pass about half as many real crimes, and the model's risk map is what makes the difference, not just the act of detouring.

### Demo app: SafeWalk Chicago
- Published artifact: https://claude.ai/artifact/3LStDfnQjubuArzgW9hH5f (private until shared from its Share menu).
- A single HTML page with a canvas street map coloured by risk: Elevated = riskiest 20% of street length (about 1.4× city average or more), High = riskiest 5% (about 3×), Very high = riskiest 1% (about 7×).
- Controls: weekday or weekend, time window, "Use current Chicago time", safety level (Shortest / Light / Balanced / Strong / Maximum). Click a start (A) and a destination (B).
- Shows shortest vs safer route, distance, average risk relative to the city, high-risk blocks crossed, and street names.
- In-browser Dijkstra routing; verified to give identical routes to Python/networkx.
- Uses the forecast for the week starting 30 Dec 2024 (the week after the data ends).
- Example trip, Loop to South Loop, weekday 8 pm–12 am, Balanced: shortest 3.27 km (10.9× city-average risk, 22 high-risk blocks) vs safer 3.40 km (2.8×, 12 high-risk blocks); 73% less predicted risk for 4% more walking.

### Limitations
- Reported crime only; policing and reporting patterns affect the data.
- The city shifts crime locations within the same block for privacy, so results are block-level.
- A lower-risk route is relative, not a guarantee of safety.
- Risk of stigmatising neighbourhoods and feedback loops; the app should support personal route choice, not label communities.
- The forecast ages; a real app would retrain weekly.

### Ideas to extend
- Add features: street lighting, open businesses at night, CTA stations, events, weather.
- Weight crime types by severity.
- Score routes by their riskiest block, not just the average.
- Apply the same pipeline to Delhi once real coordinates are available (only the download and grid steps change).

## Files
- `safewalk_chicago.ipynb`: full runnable Chicago pipeline (about 10 minutes): download, explore, KDE baselines, LightGBM, route evaluation, app build.
- `safewalk_chicago_code.py`: all Chicago code in one file, in the order the notebook runs it.
- `safewalk_app_template.html`: the app's HTML/JS template (data is injected at build time).
- `delhi_crime_hotspots.ipynb` and `delhi_hotspot_code.py`: the Delhi part.
- Tools: Python, pandas, NumPy, SciPy, LightGBM, networkx, matplotlib; the app is plain HTML, Canvas and JavaScript.
