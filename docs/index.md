---
title: Privacy policy
---

# Privacy policy

stickerboard does not collect personal data. There is no account. The current app has no analytics and no servers. The app declares no `INTERNET` permission.

Contact: [portalier.g@gmail.com](mailto:portalier.g@gmail.com)

## Stickers on your device

Stickers live only on your device, under the app's private directory (`filesDir/stickers`). Android backup is off (`allowBackup` is false). Uninstalling stickerboard deletes those files.

## Sending a sticker

When you send a sticker, Android's `commitContent` path or the clipboard grants the receiving app a temporary read URI through `FileProvider` (`${applicationId}.fileprovider`). stickerboard does not upload that image.

## Export and import

Export writes a zip to a location you choose. Import reads a zip you choose. stickerboard does not upload that zip.

## Cutout

Cutout runs Google ML Kit subject segmentation on the device. Google Play Services may download the segmentation model. Sticker pixels stay on the device for that step.

## Play Data safety

The matching Play Console answers for this APK are no data collected, no data shared, no encryption in transit because there is no transit, and no independent security review.
