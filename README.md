# Vluck Auto Player V3

This version changes the gameplay controller to match the observed game behaviour:

- The loading screen is ignored. The controller waits until real teal game platforms are visible.
- The character is treated as the fixed reference point near the screen centre.
- The controller finds the **first platform below the character**, rather than scanning arbitrary lower rows.
- It treats both teal platform surface and yellow danger sections as **blocked**.
- It searches for a real empty gap in that next platform.
- If the centre is already inside a sufficiently wide safe gap, it does **nothing** and lets the character fall.
- If the centre is not safe, it rotates the tower with a horizontal drag toward the nearest safe gap.
- It uses feedback from the next frames to learn whether the game's drag direction is inverted and automatically reverses when necessary.
- Victory → taps Continue.
- Defeat → taps Restart.
- The controller avoids steering during loading/transition frames.

The first Android setup still requires user approval for Accessibility and screen capture permissions.
