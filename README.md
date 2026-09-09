# stickerboard

Android sticker keyboard that sits beside Gboard. Switch to it, tap a sticker, Gboard comes back.

## Build

Needs the same Android SDK/JDK as a normal Gradle app.

```sh
bash scripts/check-no-compose-effect.sh
./gradlew assembleDebug
```

Sideload a debug build with `./gradlew installDebug` (or wireless adb). Debug and release use different keys — do not install debug over a release phone install, or the other way around. Android will refuse, and uninstalling to force it deletes every sticker.

## Release

Stickers live in the app’s private data. They survive an update only if the APK is signed with the **same** key as the copy already on the phone.

Always sign release with `~/.android/stickerboard-release.jks` (passwords in `~/.android/stickerboard-release.properties`). Never generate a new key. `./gradlew assembleRelease` / `installRelease` use that keystore and fail if it is missing.

```sh
./gradlew assembleRelease
ANDROID_SERIAL=<phone> ./gradlew installRelease
```

For wireless adb, `ANDROID_SERIAL` is `host:port` from `adb devices` (not an emulator serial). Use `installRelease` or `adb install -r`. Never `adb uninstall com.portavion.stickerboard` to update — that wipes stickers.

Share `app/build/outputs/apk/release/app-release.apk`. Recipients who already have stickerboard can sideload that file over the existing install; they must not uninstall first.

## Use

1. Open the app and add PNG, WebP, GIF, or JPEG stickers, share an image to stickerboard, or copy an image and tap **Paste**.
2. Enable the stickerboard keyboard in system settings.
3. In a chat, long-press Gboard’s spacebar and pick stickerboard.
4. Tap a sticker to insert. Long-press copies it for Gboard’s clipboard.

The privacy policy is at https://portavion.github.io/stickerboard/.

## Backup

Export writes a zip you can unzip by hand: every sticker at the root under its
own filename, and `looks/{name}.png` for each saved look. Import reads such a
zip back. Nothing in your library is overwritten; a sticker whose name is
already taken is written under a new name, and its look moves with it.
