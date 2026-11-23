# Releases and updates

Plume Custom updates itself from the [GitHub releases](https://github.com/BigDonke/Plume-Custom/releases) of this repository. Once a day it asks the GitHub API for the latest release. If that release is newer, **Plume settings → Updates** offers to install its `Plume-Custom.apk`.

Before installing, Plume checks three things:

- The APK matches the SHA-256 that GitHub shows for the release asset.
- The APK has the package `dev.plume.custom` and the version from the release tag.
- Its `versionCode` is higher than the installed one.

Android then accepts the update only if it is signed with the same key as the installed app.

## Release

GitHub Actions builds and publishes every release (see `.github/workflows/release.yml`).

1. Raise `versionCode` and set `versionName` in `app/build.gradle.kts`. Add a section for that version to `CHANGELOG.md`.
2. Commit and push to `main`.
3. Push a tag that matches `versionName`.

   ```sh
   git tag v1.7.3
   git push origin v1.7.3
   ```

The workflow runs the unit tests and builds the release APK. It checks that the APK is signed with the Plume Custom key, then publishes it as `Plume-Custom.apk`. The release notes are that version's section of `CHANGELOG.md`.

The signing key comes from four repository secrets: `KEYSTORE_BASE64` (the `.jks` file in base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Local builds

A release build reads the signing key from `keystore.properties` in the repository root. The file is git-ignored and holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

Only a release build signed through `keystore.properties` checks for updates. Debug builds and release builds without the file skip the check, because an APK signed with another key could not update them. To check the releases of another repository, pass `-PplumeUpdateRepo=owner/name`.

The TVs see it on their next daily check, or right away under **Plume settings → Updates → Check for updates**. Drafts and pre-releases are never offered.

A TV running a build without the updater, or one signed with another key, needs one install by hand with `adb install -r Plume-Custom.apk`. A different signing key needs an uninstall first, which deletes the settings. Save them with **Settings backup** before.
