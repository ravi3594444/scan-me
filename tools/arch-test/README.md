# tools/arch-test

Architecture rules checked on every build (handoff §3, testing §5 "Architecture" row):

- `core/*` imports nothing from Android, AndroidX, AWT, Swing, JNA or D-Bus.
- Layers point one way: `ui → platform → core`.
