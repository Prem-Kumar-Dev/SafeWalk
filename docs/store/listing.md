# Store listing text (draft)

Draft copy for the App Store and Google Play. Character counts are checked against each store's limits. All text follows the wording rules in `app-spec/01-screens.md` (never "safe" about a route or place, always "reported" crimes, no area is ever called risky). Screenshots wait for the new coral design to land in both apps.

Privacy policy URL for both stores: `https://prem-kumar-dev.github.io/SafeWalk/privacy/`

## Shared facts

- **Category:** Navigation (both stores). Secondary on App Store: Travel.
- **Price:** Free, no in-app purchases, no ads.
- **Age rating:** 4+ (App Store) / Everyone (Play, via the IARC questionnaire). The app mentions crime in general terms but shows no violent content.
- **Area covered:** Chicago only. Say so up front so reviewers and users aren't surprised.

## App Store

**Name** (30 max, 28 used)
```
Lanternway: Lower-Risk Walks
```

**Subtitle** (30 max, 30 used)
```
Walking directions for Chicago
```

**Promotional text** (170 max, 108 used, can be changed without a new build)
```
New: this week's risk forecast is built from the latest City of Chicago crime reports, updated every Monday.
```

**Keywords** (100 max, 97 used, comma-separated)
```
walking,route,night,chicago,walk home,pedestrian,directions,campus,commute,street,late,lower risk
```

**Description** (4000 max, about 1,400 used)
```
Lanternway gives walking directions in Chicago that suggest routes with fewer reported street crimes at this time of day, for only a little extra distance.

Pick where you're going, and Lanternway shows two routes: the direct one, and a lower-risk one with how much lower its predicted risk is and how many minutes longer it takes. Choose the one you're comfortable with and start walking.

HOW IT WORKS
• Routes are scored with a forecast rebuilt every Monday from public City of Chicago crime reports.
• Risk changes with the time of day, so a route at 11 pm can differ from the same route at noon.
• Choose your route preference: Direct, Balanced or Lowest risk.
• Turn-by-turn directions while you walk, and an offer of a new route if you go off it.
• A "Call 911" button is always one tap away and opens your dialer, so it's never dialled by accident.

PRIVATE BY DESIGN
• No account, no ads, no tracking, no analytics.
• Routes are calculated on your phone. Your location and your trips are never sent to us.
• Works offline once the weekly forecast has downloaded.

PLEASE NOTE
Lanternway suggests routes that have had fewer reported street crimes. It is a lower-risk suggestion, not a guarantee of safety. Stay aware of your surroundings, and call 911 in an emergency. Lanternway currently covers the City of Chicago only.

Data: City of Chicago Data Portal. Map data © OpenStreetMap contributors. Map tiles by OpenFreeMap.
```

**Support URL:** `https://github.com/Prem-Kumar-Dev/SafeWalk/issues`
**Marketing URL (optional):** `https://github.com/Prem-Kumar-Dev/SafeWalk`

**App Review notes** (for the reviewer, not public)
```
Lanternway covers Chicago only. Outside Chicago the map opens on the Loop with a "covers Chicago only" notice. To test, long-press the map to drop a start pin ("Start here"), then search for a destination such as "Navy Pier". No login is needed. Location is used only while the app is open and never leaves the device. The Call 911 button opens the dialer with 911 pre-filled and does not place a call.
```

**App Privacy ("nutrition label"):** Data Not Collected. Place search goes through Apple's own MapKit, which Apple doesn't count as collection by the developer.

## Google Play

**App name** (30 max, 28 used)
```
Lanternway: Lower-Risk Walks
```

**Short description** (80 max, 76 used)
```
Walking directions in Chicago that suggest routes with fewer reported crimes
```
The spec's version ends in "street crimes." and is 84 characters, 4 over Play's limit, so "street" and the full stop are dropped here.

**Full description** (4000 max)
Use the App Store description above unchanged. Play allows the same bullets and line breaks.

**Contact details:** Play requires a public contact email on the listing. Website: `https://github.com/Prem-Kumar-Dev/SafeWalk`.

**Data safety form:**
- Does your app collect or share any of the required user data types? **No.**
- Reason: location is used only on the device; no analytics or crash SDKs; downloads are public files and don't include user data. Google's definition of "collected" is data sent off the device to the developer or a third party, and none is.
- Place search uses the Android system Geocoder (a Google service on most phones). It is part of the OS, not an SDK we bundle, so it isn't declared as sharing. If a reviewer disagrees, the fallback answer is: Location → Approximate location → Shared, for App functionality, not stored.
- Is all data encrypted in transit? **Yes** (all requests are HTTPS).
- Do you provide a way to request data deletion? **No data is collected**, so this doesn't apply.

**Ads:** No. **Target audience:** 18 and over (keeps the app out of the Families policy). **News app:** No. **Government app:** No.

## Screenshots to take (after the design lands)

Same five shots for both stores, in this order, with a one-line caption on each. Per the wording rules, no shot may show risk shading off the user's own route, and the map home screen has none:

1. Route preview with both routes showing. "A lower-risk route, a few minutes longer"
2. Route preview at night with a different route. "Routes change with the time of day"
3. Search with recent places. "Search any address in Chicago"
4. Walking, mid-route. "Follow your route, with a heads-up if you stray"
5. Settings with the route preference. "Pick Direct, Balanced or Lowest risk"

Sizes: iPhone 6.9" (1320 × 2868) is the only required App Store size now. Play needs at least 2 phone screenshots (1080 × 1920 or larger) and a 1024 × 500 feature graphic.
