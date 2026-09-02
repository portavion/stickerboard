# stickerboard

Android sticker keyboard that sits beside Gboard. Switch to it, tap a sticker, Gboard comes back.

## Build

Needs the same Android SDK/JDK as a normal Gradle app.

```sh
bash scripts/check-no-compose-effect.sh
./gradlew assembleDebug
```

Sideload with `./gradlew installDebug` (or wireless adb).

## Use

1. Open the app and add PNG, WebP, GIF, or JPEG stickers.
2. Enable the stickerboard keyboard in system settings.
3. In a chat, long-press Gboard’s spacebar and pick stickerboard.
4. Tap a sticker to insert. Long-press copies it for Gboard’s clipboard.
