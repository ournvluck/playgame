# Vluck Auto Player V5

V5 fixes the V4 background-capture failure and improves the controller for the recorded game.

## Important V5 fixes
- Uses the required `mediaProjection` foreground-service type when starting the capture service on Android 10+.
- Keeps the capture service alive while the game is in the foreground.
- Shows frame/action counters in the persistent notification so you can verify that the analyzer is actually receiving frames.
- Checks Android's enabled Accessibility-services list instead of relying only on an in-process service instance.
- Does not require orange-face detection before looking for platforms; the player can be temporarily hidden by animation/bubbles without stopping the controller.
- Looks farther down the tower and can steer even when the current centre is safe, because a lower yellow sector may be the real danger.
- Loading screen is ignored until teal/yellow game-board pixels appear.
- Victory -> Continue; Defeat -> Restart.

## First run
1. Enable Vluck Auto Player under Android Accessibility.
2. Open Vluck Auto Player.
3. Approve Android screen capture.
4. Check the notification. It should show `Frames: ... | Actions: ...` and the frame count should increase while the game is visible.
5. Switch to the game.

Android requires explicit user approval for MediaProjection screen capture and Accessibility gestures. The app cannot silently grant those permissions.
