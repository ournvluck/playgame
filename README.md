# Vluck Auto Player

Android prototype for Samsung Galaxy S20+ 5G.

## What this V1 does

- Uses Android MediaProjection to read the screen.
- Detects bright-yellow hazard areas.
- Finds a large non-yellow interval.
- Uses an Android Accessibility Service to send a horizontal swipe.
- Has no root requirement.

## Build without Android Studio

This repository contains a GitHub Actions workflow.

1. Create a GitHub repository.
2. Upload all files from this project.
3. Open the **Actions** tab.
4. Run **Build APK**.
5. Download the `vluck-auto-player-apk` artifact.
6. Extract `app-debug.apk`.
7. Install it on the phone.

## Phone setup

After installation:

1. Open Vluck Auto Player.
2. Tap **Enable Accessibility**.
3. Find Vluck Auto Player in Android Accessibility settings.
4. Enable it.
5. Return to the app.
6. Tap **Start Screen Capture** and approve the Android screen-capture prompt.
7. Open the game and test.

## Important

This is a V1 prototype. The game screenshot provided during development shows a specific visual layout, but the exact player/platform detection is not yet robust enough to claim perfect automatic play.

Test it manually first. Keep the game's STOP/exit control available and do not leave it unattended until the steering behavior has been verified.
