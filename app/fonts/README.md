# Fonts

- **Unbounded** (display: titles, the connection status, big numbers) —
  https://github.com/googlefonts/unbounded, SIL Open Font License 1.1 (`Unbounded-OFL.txt`).
- **Onest** (interface text) — https://github.com/simpals/onest, SIL Open Font License 1.1
  (`Onest-OFL.txt`).

Both ship in `app/src/main/res/font` as variable fonts (weight axis), cut down to Latin and
Cyrillic with `pyftsubset --layout-features='*'` from the google/fonts originals.
