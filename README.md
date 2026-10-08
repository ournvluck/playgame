# Vluck Auto Player V4

Built around the supplied 78-second game recording.

V4 changes:
- Loading screen is ignored; the controller waits for the real game board.
- Uses the orange face to track the falling character vertically.
- Detects teal safe platforms and yellow danger areas.
- Looks at up to five upcoming platforms instead of only the next platform.
- Chooses a horizontal angle that avoids yellow across the deepest safe route visible.
- Uses small feedback-controlled horizontal drags and automatically reverses the drag direction if a move makes the danger worse.
- Does not move when the current route is already safe.
- Victory: automatically taps Continue.
- Defeat: automatically taps Restart.
- Auto-start setup when the app is opened.
- STOP button remains available.

Android still requires the user to approve Accessibility and screen-capture permissions. Android may show "Restricted setting" for a sideloaded Accessibility service; this is an Android security restriction and cannot be bypassed by the APK.
