# Run and test Lanternway on an Android phone (VS Code + adb, no Android Studio)

This uses about 6 GB of disk space: the JDK, the command-line Android SDK, and Gradle's download cache. No emulator is needed.

## 1. One-time setup

### Windows (PowerShell)

1. **JDK 17.** Install Eclipse Temurin 17:
   ```powershell
   winget install EclipseAdoptium.Temurin.17.JDK
   ```
   Open a new PowerShell window, then check that `java -version` prints 17.
2. **Android command-line tools.** Download "Command line tools only" for Windows from
   https://developer.android.com/studio#command-tools. Unzip it so this path exists:
   `C:\Android\cmdline-tools\latest\bin\sdkmanager.bat`
   (the zip has a `cmdline-tools` folder; rename it to `latest` and put it inside `C:\Android\cmdline-tools\`).
3. **Environment variables.** Set them once, then open a new PowerShell window:
   ```powershell
   setx ANDROID_HOME "C:\Android"
   setx PATH "$env:PATH;C:\Android\platform-tools;C:\Android\cmdline-tools\latest\bin"
   ```
4. **SDK packages:**
   ```powershell
   sdkmanager --licenses            # answer y to each
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```
5. **USB driver.** Pixel phones need the Google USB driver: `sdkmanager "extras;google;usb_driver"`, then in
   Device Manager update the phone's driver from `C:\Android\extras\google\usb_driver`. Samsung, Xiaomi and other
   brands use their own driver from the maker's site. Many phones work without any extra driver.

### Mac (Terminal), if you ever build there

```bash
brew install --cask temurin@17 android-commandlinetools
echo 'export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools' >> ~/.zshrc
echo 'export PATH="$PATH:$ANDROID_HOME/platform-tools"' >> ~/.zshrc
source ~/.zshrc
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```
No USB driver is needed on a Mac. On Mac and Linux, use `./gradlew` instead of `.\gradlew.bat` in the steps below.

### VS Code

Open the `android` folder (File → Open Folder). Useful extensions:
- **Kotlin** (fwcd.kotlin) for syntax highlighting and basic navigation. It's slow on Android projects, so don't
  rely on it for errors; Gradle's output is the source of truth.
- **Gradle for Java** (vscjava.vscode-gradle), if you'd rather click tasks than type them.

Use VS Code's terminal (Ctrl+`) for all the commands below.

## 2. Connect the phone

1. On the phone: Settings → About phone → tap **Build number** 7 times to unlock Developer options.
2. Settings → System → Developer options → turn on **USB debugging**.
3. Plug in the cable. On the phone, accept "Allow USB debugging?" (tick "Always allow from this computer").
4. Check:
   ```powershell
   adb devices
   ```
   You should see one line ending in `device`. If it says `unauthorized`, unlock the phone and accept the prompt.

No cable handy? On Android 11+ use Developer options → **Wireless debugging** → "Pair device with pairing code",
then `adb pair <ip>:<port>` and `adb connect <ip>:<port>`.

## 3. Build, install and open the app

From the `android` folder:

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n app.safewalk/app.safewalk.android.MainActivity
```

The first build downloads Gradle and the libraries, which takes a few minutes. After that, rebuilds take
seconds. To build an APK without installing it: `.\gradlew.bat :app:assembleDebug`; the file is in
`app\build\outputs\apk\debug\app-debug.apk`.

If the build fails, copy the first `e:` error lines (file, line number and message) into the thread and I'll fix them.

## 4. Watch the logs

Only Lanternway's logs (PowerShell):
```powershell
adb logcat --pid=$(adb shell pidof -s com.prem.lanternway)
```
Only errors and crashes from any app:
```powershell
adb logcat *:E
```
Clear the log first with `adb logcat -c` so you only see the new run. On a crash, the useful part starts at
`FATAL EXCEPTION`; paste about 30 lines from there.

## 5. Run the unit tests

```powershell
.\gradlew.bat :core:test
```
This runs every route in `app-spec/fixtures/expected_routes.json` against the Kotlin router. The report is in
`core\build\reports\tests\test\index.html`.

## 6. Things to try on the phone

You don't need to be in Chicago. Search works for Chicago places from anywhere, and long-pressing the map drops a pin.

1. First launch: three welcome pages, the disclaimer, then the location question. "Not now" should still work.
2. Search "Union Station Chicago" → route preview opens. If you're outside Chicago, the start is missing; tap
   **From** and search "Millennium Park".
3. Switch Direct / Balanced / Lowest risk and check that the cards and the route line change. Higher-risk
   stretches are orange and dashed. The direct route is grey underneath.
4. Leaving → pick 11 pm, then 2 pm. Routes often differ between night and day.
5. Long-press near the lake shore or in the river → "Move the pin closer to a street."
6. Settings: switch to miles, check for an update (it will fail quietly until the data repo is published;
   the bundled forecast keeps working), and open About and Privacy.
7. Dark mode: switch the phone to dark theme and reopen the app.
8. Walking mode needs a real walk in Chicago, or a mock-location app (Developer options → "Select mock location
   app") to test "off route" and "arrived".

## Uninstall

```powershell
adb uninstall com.prem.lanternway
```
