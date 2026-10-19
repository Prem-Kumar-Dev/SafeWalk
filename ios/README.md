# SafeWalk for iOS

Native Swift/SwiftUI app built to `app-spec/` (format version 1). iOS 16+, iPhone, portrait.
Map: MapLibre Native (Swift Package) with OpenFreeMap tiles. Search: Apple `MKLocalSearch`. Routing: on the phone.

## Build and run on a Mac

You need Xcode 16 or newer and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
brew install xcodegen
cd ios
xcodegen                 # writes SafeWalk.xcodeproj from project.yml
open SafeWalk.xcodeproj
```

1. In Xcode, pick the **SafeWalk** target > Signing & Capabilities and choose your team. Change the bundle id
   (`com.example.safewalk.SafeWalk`) to your own.
2. Xcode downloads MapLibre the first time (File > Packages > Resolve if it doesn't).
3. Run on an iPhone simulator or your phone. Routing works anywhere in Chicago. In the simulator, set a Chicago
   location with Features > Location > Custom (e.g. 41.8815, -87.6275).
4. Run the tests with **Cmd+U**. `GoldenRouteTests` runs every case in `app-spec/fixtures/expected_routes.json`.

Command-line equivalent:

```sh
xcodebuild test -project SafeWalk.xcodeproj -scheme SafeWalk -destination 'platform=iOS Simulator,name=iPhone 16'
```

This code was written without a Mac, so the first build may need small compile fixes. The routing logic itself was
checked line-for-line against the reference answers with a Python port.

## Before release

- `SafeWalk/Data/` holds the real Chicago street graph and the forecast for the week of 30 Dec 2024, so first launch
  works offline citywide. Before each release, copy in the latest published folder.
- The app checks `https://prem-kumar-dev.github.io/SafeWalk/v1/chicago/manifest.json` (mirror: jsDelivr) for new
  weekly files, set in `DataConfig` in `SafeWalk/Core/DataStore.swift`.
- Set `AppInfo.feedbackURL` in `SafeWalk/Features/InfoViews.swift`.
- Add a 1024 × 1024 app icon to `SafeWalk/Resources/Assets.xcassets/AppIcon.appiconset`.
- App Store privacy label: **Data not collected**. The privacy text is `privacy_body` in `Localizable.strings`.

## Layout

| Path | What |
|---|---|
| `SafeWalk/Core/SafeWalkData.swift` | `manifest.json`, `SWG1` graph and `SWR1` risk readers |
| `SafeWalk/Core/SnapIndex.swift` | 200 m bucket snap index and polyline geometry (spec 03 §2) |
| `SafeWalk/Core/Router.swift` | A* with virtual start/target nodes, route summary, turn list, show/hide rules (03 §3–4) |
| `SafeWalk/Core/DataStore.swift` | Bundled data, manifest check with ETag every 6 h, size/SHA-256/graph-id checks, atomic swap (02) |
| `SafeWalk/Core/TimeSlot.swift` | Chicago-time slot (weekend = Sat/Sun) |
| `SafeWalk/Map/SafeWalkMapView.swift` | MapLibre view: only the user's own routes, coloured per piece, higher-risk pieces dashed |
| `SafeWalk/Features/` | Welcome + disclaimer, map home, search, route preview, walking, settings, about, privacy |
| `SafeWalk/Resources/en.lproj/Localizable.strings` | All strings, keyed the same as Android |
| `SafeWalkTests/` | Golden-route tests and their fixture copy |

The map never shows a citywide risk layer, area shading or risk numbers (spec 01, "Wording rules").
