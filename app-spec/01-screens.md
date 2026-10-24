# 01 · Screens and wording

Native on both platforms: **Android** in Kotlin with Jetpack Compose (min SDK 26), **iOS** in Swift with SwiftUI (iOS 16+). Same screens, same words, same behaviour; each follows its platform's own navigation and controls.

Free services only:

| Need | Choice | Cost |
|---|---|---|
| Map rendering | MapLibre Native (Android SDK, iOS SDK via Swift Package) | Free, open source |
| Map style and tiles | OpenFreeMap, style `https://tiles.openfreemap.org/styles/liberty` | Free, no key, no account |
| Place search | iOS `MKLocalSearch`; Android `android.location.Geocoder` (API 33+ async form) | Free, built into the OS |
| Risk data | Static files on GitHub Pages ([02](02-data-files.md)) | Free |
| Routing | On the phone ([03](03-routing.md)) | Free |
| Crash reports | None in version 1 (both stores show crash reports to the developer for free) | Free |

Map attribution must stay visible on the map: "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors". If OpenFreeMap is ever unavailable, the fallback is a Chicago-only Protomaps `.pmtiles` file on the same GitHub Pages site; the style URL is a remote-config value in the manifest folder (`style.json` next to `manifest.json`), so it can be switched without an app release.

The street graph comes from City of Chicago centerlines and the base map from OpenStreetMap, so a route line can sit a few metres off the drawn street. That is expected; do not try to "fix" it by snapping to tiles.

## Screen list

### 1. Welcome (first launch only)

Three short pages, then the agreement:

1. "Walk the way that's had fewer reported street crimes." Picture: two route lines.
2. "Pick where you're going. Lanternway shows the direct route and a lower-risk route, and how much longer it is."
3. "Everything runs on your phone. Your location and trips are never sent anywhere."

Then the **disclaimer** card, which the user must accept once with "I understand":

> Lanternway suggests routes that have had fewer reported street crimes at this time of day. It is a lower-risk suggestion, not a guarantee of safety. Stay aware of your surroundings, and call 911 in an emergency.

Then location permission, asked with the system prompt after a one-line reason: "To start routes from where you are." "Not now" is fine; the app works with a dropped pin.

### 2. Map (home)

- Full-screen MapLibre map centred on the user, or on the Loop if location is off or the user is outside Chicago.
- Search bar at the top: "Where to?"
- Location button, compass when rotated.
- Long-press anywhere on the map: drops a pin with "Walk here" and "Start here".
- No risk colouring of streets or areas on this screen, ever (see Wording rules).
- If the user is outside the Chicago bbox: a slim notice "Lanternway covers Chicago only for now". Search still works for Chicago places.

### 3. Search

- Text field with recent places (stored on the device only, max 20, clearable in Settings) and results as the user types.
- Results limited to the manifest bbox: `MKLocalSearch.Request.region` on iOS, `Geocoder.getFromLocationName(name, max, lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon)` on Android.
- Choosing a result opens Route preview with start = current location (or pin) and end = result.

### 4. Route preview

Top: start and destination fields (tap to change, swap button).

Map: the lower-risk route in the brand colour, drawn over the direct route in grey. Route pieces coloured as in [03 · section 5](03-routing.md#5-route-line-colours). Start and end markers.

Bottom sheet:

- **Leaving**: "Now" (default) or a chosen time. Changing the time recomputes both routes.
- **Route preference**: three-way segmented control: Direct · **Balanced** · Lowest risk. Default Balanced; the last choice is remembered.
- Two cards, the selected one highlighted:
  - "Lower-risk route · 3.4 km · 44 min" with "73% lower predicted risk · 4 min longer" and "Passes 2 higher-risk stretches" (or "No higher-risk stretches").
  - "Direct route · 3.3 km · 42 min" with "Passes 12 higher-risk stretches".
- If the direct route is already the lower-risk choice: one card and the line "The direct route is already the lower-risk choice at this time."
- Small print under the cards: "Based on reported crimes. Lower risk, not a guarantee." and, if the forecast is 14+ days old, "Forecast from the week of 19 Oct."
- Primary button: "Start walking".

### 5. Walking

- Map follows the user with heading; route line; distance and time left.
- Next step: "Turn left onto W Adams St" with the distance to it.
- Off-route prompt: "You're off the route. Find a new route from here?" with Yes / Not now.
- **Emergency button** always visible: "Call 911", which opens the phone dialer with 911 filled in (one tap more to call, so it is never dialled by accident).
- "End" button; on arrival: "You've arrived" and back to Map.
- Screen stays awake in this mode only.

### 6. Settings

- Route preference default.
- Units: km or miles (default from the phone's region).
- Clear recent places.
- Map data status: "Forecast for the week of 19 Oct · updated 2 days ago" with "Check for update".
- Links to About, Privacy, the disclaimer again.

### 7. About and how it works

Plain-language explanation:

> Lanternway uses ten years of crime reports published by the City of Chicago, counting street crimes against people (such as robbery and assault) in public places, not domestic incidents. A forecasting model predicts, for each block and each part of the day, how often such crimes are reported compared with the city average. When you ask for a route, your phone finds a path that keeps walking distance low while passing fewer of the higher-risk blocks. In tests on 2024 data the model had not seen, the Balanced route was about 4% longer and passed about half as many real reported crimes as the shortest route.
>
> Reported crime is not the same as all crime, and reporting and policing differ between places. Treat the route as one input to your own judgement.

Data sources and licences: City of Chicago Data Portal (crimes `ijzp-q8t2`, street centerlines `pr57-gg9e`); OpenStreetMap contributors (ODbL); OpenFreeMap; MapLibre. Model version and forecast week from the manifest. Contact email for feedback.

### 8. Privacy

One screen, also used as the store privacy policy page (hosted on the same GitHub Pages site):

> Lanternway does not collect, store on a server, or share your location, your searches or your trips. Routes are calculated on your phone. The app downloads map tiles and the weekly forecast file, which tells those servers your IP address as any download does. Place search uses Apple's or Google's built-in search on your phone, under their privacy terms.

Both store privacy labels: **Data not collected**.

## Wording rules

These apply to every string in both apps, the store listings and screenshots.

Do:

- Talk about **routes and streets at a time of day**: "lower-risk route", "higher-risk stretch", "fewer reported street crimes".
- Always say **reported** crimes.
- Show reduction relative to the user's own direct route ("73% lower predicted risk"), never absolute risk.
- Keep the disclaimer one tap away on every route: "Lower risk, not a guarantee."

Do not:

- Name or label any neighbourhood, community area, ward, park or district as risky, dangerous, unsafe or bad. Place names appear only as search results and street names.
- Colour, shade or heat-map areas or streets that are not on the user's own route.
- Use "safe route", "safe area", "dangerous", "avoid this neighbourhood", "bad part of town", or "danger zone". Never use "safe" about a route or a place.
- Show risk numbers, multiples of the average, crime counts or crime types for a place.
- Send notifications about places ("You are entering…").

Store listing short description: "Walking directions in Chicago that suggest routes with fewer reported street crimes."

## Accessibility and polish

- Dynamic Type / font scaling, VoiceOver / TalkBack labels on every control (route cards read their whole summary).
- Route colours also differ in line pattern (higher-risk stretches dashed) so colour is not the only signal.
- Dark mode, using MapLibre's style for night or OpenFreeMap's dark style.
- All strings in one strings file per platform (English only in version 1), keyed identically on both platforms so wording stays in sync. Key names: `welcome_1`, `welcome_2`, `welcome_3`, `disclaimer_body`, `disclaimer_accept`, `search_hint`, `route_lower_risk_title`, `route_direct_title`, `route_risk_cut`, `route_extra_time`, `route_stretches`, `route_none_stretches`, `route_direct_is_best`, `route_fine_print`, `forecast_age`, `forecast_stale`, `outside_coverage`, `too_far_from_street`, `already_there`, `off_route_prompt`, `call_911`, `arrived`, `update_app`.
