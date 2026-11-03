# Hovel native assets

Compose resources for Hovel's native screens. The room and furniture images are byte-identical copies of the Hovel game art from
`Timwestberg/game-platform` at `afe75508cc83e10c1bd5abfdf01fcbdb17ed42ec`,
`packages/game-bridge/godot4/games/hovel/assets/`. Provenance for each image is
recorded there; the SHA-256 values below match that repository's manifests.

| File | Source path | SHA-256 |
| --- | --- | --- |
| `drawable/hovel_room_shell.png` | `environment/starter_v1/hovel_room_shell.png` | `6a5c41bc75033b8efb530826b008195dcbe262527bc428e4da3ffed7c4329963` |
| `drawable/hovel_furniture_sheet.png` | `environment/starter_v1/hovel_furniture_sheet.png` | `a778c7e0cbb564a36a9321e4161e074ca3e71cf488e153f9a78b09be73fbbde2` |

## Hovel Sans

`font/hovel_sans_*.ttf` is Hovel Sans, the interface face for all native text (`hovelSans`
in `ui/GlassTokens.kt`). It is a Latin subset of Lato 2.015 by Łukasz Dziedzic with hinting
removed, so it renders the same on every OS; the web host serves the same subset as WOFF2.
The SIL Open Font License 1.1 reserves the name Lato for unmodified copies, so the subset is
named Hovel Sans. See `files/hovel/hovel_sans_OFL.txt`.

Source: `https://www.latofonts.com/files/Lato2OFL.zip`, SHA-256
`42b54e96c07e299d967fc3227c7bd63a20d6cfb1dc8fd6dae83628091e20a5b8`.

| File | Built as | Weight | Style | SHA-256 |
| --- | --- | --- | --- | --- |
| `font/hovel_sans_regular.ttf` | `HovelSans-Regular.ttf` | 400 | normal | `b802b74534406259b03ad7e267b27d7e9f04de4e819a60ab355938dd6725d4de` |
| `font/hovel_sans_italic.ttf` | `HovelSans-Italic.ttf` | 400 | italic | `f103e47eda6a05832c6317554bb81f25b78dc6b35d27311d532cda052ab6954d` |
| `font/hovel_sans_medium.ttf` | `HovelSans-Medium.ttf` | 500 | normal | `593621073100f8f06212fe749031cff7db297f33c375ab1365ba73b21acdf353` |
| `font/hovel_sans_semibold.ttf` | `HovelSans-Semibold.ttf` | 600 | normal | `10102f90f077bb09a637e9953714865252458770c5684ec785350ec198203503` |
| `font/hovel_sans_bold.ttf` | `HovelSans-Bold.ttf` | 700 | normal | `270543349595626a1a68e90150c902e1df2c54bfdd830e4d89086299f146517a` |
| `font/hovel_sans_bold_italic.ttf` | `HovelSans-BoldItalic.ttf` | 700 | italic | `5431b6dbaea4161666c9d695cc81e714d6acc0021f68d41b674abc310180f7c7` |
| `font/hovel_sans_heavy.ttf` | `HovelSans-Heavy.ttf` | 800 | normal | `28809fc5cb6bdf1d5425a40fd59fddbc4f8237b52ecfba0f94afefe8d6b1dadb` |
| `font/hovel_sans_black.ttf` | `HovelSans-Black.ttf` | 900 | normal | `4eff2a09ffc58a69dc1249fba0ecf452b14dd4f96292e361d585d70ee54b5803` |

Rebuild with `scripts/make-hovel-sans.py` in `Timwestberg/game-platform` (added in
`aef039d37859d60d87a25f304d4e1427856ea217`; details in
`apps/game-bridge-host/src/components/liquid-glass/fonts/README.md`), using `fonttools==4.55.3`
and `brotli==1.1.0`:

```sh
python3 scripts/make-hovel-sans.py <unzipped Lato2OFL folder> <out folder>
```

Copy `<out folder>/native/HovelSans-*.ttf` here under the names above and update the checksums;
`HovelSansTest` fails until they match. The subset has no ✓ (Lato never had one), so the
companion upgrade card draws its check mark.

## Pixelify Sans

`font/pixelify_sans.ttf` is Pixelify Sans from `google/fonts` (`ofl/pixelifysans`).
No interface text uses it; it stays as a candidate face for the Hovel logo. It is
licensed under the SIL Open Font License 1.1; see `files/hovel/pixelify_sans_OFL.txt`.

## Ember robot

`drawable/hovel_robot.png` is a transparent Godot Compatibility render of the shared
Ember robot in game-platform commit `e9929a07da1d6e7a1d09758ed740155d91dc4c35`,
using `robot/room_robot.gd` and the canonical web model, in Ember's default look.
Reproduce with `tools/capture-hovel-robot.gd`, passing the game-platform room as
Godot's `--path` and `-- --output=/absolute/hovel_robot.png` after the script.
SHA-256: `352cbfc585e6c54eddf8b1200a837070ff0b25ab4c527e183093dc538ddab332`.
