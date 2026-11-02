# Hovel start screen assets

Compose resources for the Hovel start screen. The room and furniture images are byte-identical copies of the Hovel game art from
`Timwestberg/game-platform` at `afe75508cc83e10c1bd5abfdf01fcbdb17ed42ec`,
`packages/game-bridge/godot4/games/hovel/assets/`. Provenance for each image is
recorded there; the SHA-256 values below match that repository's manifests.

| File | Source path | SHA-256 |
| --- | --- | --- |
| `drawable/hovel_room_shell.png` | `environment/starter_v1/hovel_room_shell.png` | `6a5c41bc75033b8efb530826b008195dcbe262527bc428e4da3ffed7c4329963` |
| `drawable/hovel_furniture_sheet.png` | `environment/starter_v1/hovel_furniture_sheet.png` | `a778c7e0cbb564a36a9321e4161e074ca3e71cf488e153f9a78b09be73fbbde2` |

`font/pixelify_sans.ttf` is Pixelify Sans from `google/fonts` (`ofl/pixelifysans`), the
display face the Hovel web host loads. It is licensed under the SIL Open Font
License 1.1; see `files/hovel/pixelify_sans_OFL.txt`.

`drawable/hovel_robot.png` is a transparent Godot Compatibility render of the shared
Ember robot in game-platform commit `e9929a07da1d6e7a1d09758ed740155d91dc4c35`,
using `robot/room_robot.gd` and the canonical web model, in Ember's default look.
Reproduce with `tools/capture-hovel-robot.gd`, passing the game-platform room as
Godot's `--path` and `-- --output=/absolute/hovel_robot.png` after the script.
SHA-256: `352cbfc585e6c54eddf8b1200a837070ff0b25ab4c527e183093dc538ddab332`.
