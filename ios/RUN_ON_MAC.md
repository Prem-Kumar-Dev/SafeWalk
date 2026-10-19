# Run and test SafeWalk iOS on your Mac and iPhone

You can edit in VS Code, but building for an iPhone needs Apple's Xcode tools. There's no way around that.
You open Xcode once to sign in. After that, everything below runs from the VS Code terminal.

## 1. Install the tools (about 15–20 GB of your 69 GB free)

1. Install **Xcode** from the Mac App Store. Xcode 16 or newer is fine. Open it once and accept the licence.
   When it asks which platforms to download, choose **iOS**. The iPhone simulator is part of that download (about 8 GB).
   You only need the simulator to run the tests on the Mac. If space gets tight, skip it and run the tests on your phone instead (step 5).
2. In Terminal:
   ```sh
   sudo xcode-select -s /Applications/Xcode.app
   xcode-select --install          # command line tools, if it says they're missing
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"   # skip if you have Homebrew
   brew install xcodegen
   ```
3. In VS Code, install the **Swift** extension (publisher: swiftlang) for code completion. The optional **SweetPad**
   extension adds build/run buttons for iOS inside VS Code.

## 2. Get the code and make the project

Copy the `ios` folder from the project files onto the Mac, then:

```sh
cd ios
xcodegen            # creates SafeWalk.xcodeproj from project.yml
```

Run `xcodegen` again whenever files are added or `project.yml` changes.

## 3. Sign with your free Apple ID (one time)

1. Open Xcode, go to **Settings → Accounts**, click **+**, choose **Apple ID** and sign in.
   Your team will show up as "Your Name (Personal Team)".
2. Run `open SafeWalk.xcodeproj`. Select the **SafeWalk** target and open **Signing & Capabilities**.
   Tick *Automatically manage signing* and pick your Personal Team.
3. The **Bundle Identifier** is already `com.prem.safewalk` (set in `project.yml`).
   To make your team stick when you re-run `xcodegen`, put it in `project.yml` as
   `DEVELOPMENT_TEAM: "<your team id>"`. The team ID is the 10-character code
   shown under Membership details at developer.apple.com/account, or in the Signing panel.

## 4. Run on your iPhone

1. Connect the iPhone with a cable, unlock it, and tap **Trust This Computer**.
2. On the iPhone, turn on **Settings → Privacy & Security → Developer Mode**. It restarts the phone.
   The Developer Mode setting only appears after the phone has been connected to Xcode once.
3. **Easiest:** in Xcode, pick your iPhone in the device menu at the top and press **⌘R**.
   **From the VS Code terminal:**
   ```sh
   xcrun devicectl list devices                       # note your phone's Identifier
   xcodebuild -project SafeWalk.xcodeproj -scheme SafeWalk \
     -destination 'platform=iOS,id=<phone-identifier>' \
     -allowProvisioningUpdates -derivedDataPath build build
   xcrun devicectl device install app --device <phone-identifier> \
     build/Build/Products/Debug-iphoneos/SafeWalk.app
   xcrun devicectl device process launch --device <phone-identifier> com.prem.safewalk
   ```
   With SweetPad, choose the scheme and the device and press Run.
4. The first launch may say "Untrusted Developer". On the phone, go to **Settings → General → VPN & Device Management**,
   tap your Apple ID and tap **Trust**.

Free Apple ID limits: the app stops opening after **7 days**, so just run it again from the Mac to renew it.
You can have up to 3 of your own apps on the phone at once. A paid Apple Developer account ($99 a year) removes both limits.
You also need it for TestFlight and the App Store.

**What you'll see:** the app ships with the real Chicago street map and forecast, so routes work anywhere in the city,
even offline. When it's online, it picks up the newest weekly forecast from your GitHub Pages site. If you're not in
Chicago, search for a place or long-press the map to drop pins. In the simulator, use **Features → Location → Custom Location**
and set 41.8815, -87.6275.

## 5. Run the route tests

The tests check snapping and routing against every answer in `app-spec/fixtures/expected_routes.json`.

On the simulator:
```sh
xcrun simctl list devices available | grep iPhone      # pick a name from the list
xcodebuild test -project SafeWalk.xcodeproj -scheme SafeWalk \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

On your iPhone, if you skipped the simulator download:
```sh
xcodebuild test -project SafeWalk.xcodeproj -scheme SafeWalk \
  -destination 'platform=iOS,id=<phone-identifier>' -allowProvisioningUpdates
```

The run ends with `** TEST SUCCEEDED **`. In Xcode, **⌘U** runs the same tests.

## If something goes wrong

- **Compile errors on the first build:** this code was written without a Mac. Paste the errors into the iOS thread
  and they'll be fixed.
- **"No profiles for 'com.prem.safewalk'":** pick your team in Signing & Capabilities (step 3.2).
- **"Could not locate device support files":** update Xcode, or open Xcode with the phone connected and wait for it
  to finish "Preparing iPhone".
- **MapLibre package not found:** in Xcode, use **File → Packages → Resolve Package Versions**.
- **Low on space:** clear old build files with `rm -rf ~/Library/Developer/Xcode/DerivedData`.
