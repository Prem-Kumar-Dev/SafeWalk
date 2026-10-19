# Store launch checklist

What only Prem can do, in the order that avoids waiting. The repo side (privacy page, listing text) is in this folder and in `privacy/`.

## Start this week (the slow parts)

1. **Google Play developer account** at play.google.com/console. $25 once. Choose a **personal** account. Google verifies your identity with a government ID and a phone number, and this can take a few days.
2. **Recruit 12 testers now.** A personal Play account created after November 2023 must run a **closed test with at least 12 testers opted in for 14 days in a row** before it can apply for production. Ask for 15 to 20 so dropouts don't reset the clock. Each tester needs a Google account email, an Android phone, and must keep the app installed for the 14 days. Friends, classmates or a Chicago subreddit all work.
3. **Apple Developer Program** at developer.apple.com/programs. $99 a year, enrolled as an individual. Approval is usually within 48 hours but can take longer.
4. **Pick a support email** that you're happy to show publicly. Both stores require one on the listing. Add it to the Contact section of `privacy/index.html` too, or ask me to.

## Before the first upload

5. **Check the privacy page is live.** GitHub Pages already serves the weekly data from `main`, so once this PR is merged `https://prem-kumar-dev.github.io/SafeWalk/privacy/` should open. Both stores check that the URL works.
6. **iOS bundle ID** is set to `com.prem.safewalk` in `ios/project.yml`. Pick your team in Xcode → Signing & Capabilities, and Xcode registers the ID on your Apple account the first time it signs.
7. **Android package name** is `com.prem.safewalk`, the same as iOS. Play never lets you change it after the first upload.
8. **Android signing key.** Create an upload key (Android Studio → Build → Generate Signed Bundle) and keep the `.jks` file and its passwords somewhere safe outside the repo. Let Play App Signing manage the release key.
9. **App icon.** Both apps still use a placeholder. Item 3 of the plan covers this; both stores need a final icon (1024 × 1024 for Apple, 512 × 512 for Play).

## Google Play, step by step

10. Create the app in Play Console: name "SafeWalk: Lower-Risk Walks", free, app (not game).
11. Fill in App content: privacy policy URL, Data safety (answers in `listing.md`), ads = no, target audience = 18+, content rating questionnaire, news app = no.
12. Build the release bundle: `cd android && ./gradlew :app:bundleRelease`, sign it with your upload key, and upload the `.aab` to **Testing → Closed testing**.
13. Add your testers' emails as a list, send them the opt-in link, and **start the 14-day clock** by publishing the closed test release.
14. After 14 days with 12+ testers opted in, Play Console unlocks **Apply for production**. It asks a few questions about the test, then review usually takes a few days.

## App Store, step by step

15. In App Store Connect create the app with your bundle ID, name "SafeWalk: Lower-Risk Walks", primary language English (U.S.).
16. On a Mac: `cd ios && xcodegen`, open the project, select "Any iOS Device", then Product → Archive → Distribute App → App Store Connect.
17. **TestFlight:** the build appears after processing. Add yourself and a few friends as internal or external testers and do a few real walks. External testers need a short beta review the first time.
18. Fill in the listing from `listing.md`, the App Privacy answer ("Data Not Collected"), the age rating, and the App Review notes, then submit for review.

## Waiting on other work

- **Screenshots** wait until the new coral design is in both apps. The list of shots and sizes is in `listing.md`.
- Real-walk testing (plan item 3) fits inside the Play 14-day closed test window, so the two can run together.
