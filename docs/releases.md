# Releases and updates

Plume Custom updates itself from the [GitHub releases](https://github.com/BigDonke/Plume-Custom/releases) of this repository. Once a day it asks the GitHub API for the latest release. If that release is newer, **Plume settings → Updates** offers to install its `Plume-Custom.apk`.

Before installing, Plume checks three things:

- The APK matches the SHA-256 that GitHub shows for the release asset.
- The APK has the package `dev.plume.custom` and the version from the release tag.
- Its `versionCode` is higher than the installed one.

Android then accepts the update only if it is signed with the same key as the installed app.

## Build

1. Raise `versionCode` and set `versionName` in `app/build.gradle.kts`. Add a section for that version to `CHANGELOG.md`.
2. Put `keystore.properties` in the repository root. It is git-ignored and holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Use the key that signed the APK on your TVs.
3. Build the release.

   ```sh
   ./gradlew assembleRelease
   ```

Only a release build signed through `keystore.properties` checks for updates. Debug builds and release builds without the file skip the check, because an APK signed with another key could not update them. To check the releases of another repository, pass `-PplumeUpdateRepo=owner/name`.

## Publish

Create the release from `main` with a tag that matches `versionName`, and attach the APK as `Plume-Custom.apk`.

```sh
cp app/build/outputs/apk/release/app-release.apk Plume-Custom.apk
gh release create v1.7.3 Plume-Custom.apk --title "Plume Custom v1.7.3" --notes "What changed"
```

The TVs see it on their next daily check, or right away under **Plume settings → Updates → Check for updates**. Drafts and pre-releases are never offered.

A TV running a build without the updater, or one signed with another key, needs one install by hand with `adb install -r Plume-Custom.apk`. A different signing key needs an uninstall first, which deletes the settings. Save them with **Settings backup** before.
