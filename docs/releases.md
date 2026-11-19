# Plume Custom release delivery

GitHub Releases in [BigDonke/Plume-Custom](https://github.com/BigDonke/Plume-Custom/releases) are the source for TV updates. Keep the repository private and serve the published APK and `latest.properties` from a static HTTPS directory reachable by the TVs. To keep the files private too, restrict network access to the directory. The TV needs to reach it without a browser sign-in.

## Build and sign

1. Increment `versionCode` and set the numeric `versionName` in `app/build.gradle.kts`. Add that version's section to `CHANGELOG.md`.
2. Configure the git-ignored `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` for the key that signed the installed `dev.plume.custom` APK. Each TV can upgrade in place only with its existing signing certificate. Use `apksigner verify --print-certs` on a trusted copy of the installed APK to identify it.
3. Add the manifest URL to the same `keystore.properties` file. Use a hostname so you can change the server IP through DNS without rebuilding the APK.

   ```properties
   plumeUpdateFeedUrl=https://updates.example.org/plume/latest.properties
   ```

4. Build the signed release.

   ```sh
   ./gradlew assembleRelease
   ```

   Pass `-PplumeUpdateFeedUrl=<url>` to override the file for a staging or CI build. A release build with an empty feed URL shows the bundled release notes and skips update checks. Debug builds also skip update checks.

## Publish

1. Create a GitHub release from the corresponding `custom` revision. Use a tag that matches the APK version, such as `v1.7.2`, and attach `app/build/outputs/apk/release/app-release.apk` under the name `Plume-Custom.apk`. Publish it as the latest stable release once the APK is attached.
2. Run the feed tool from a machine with authenticated GitHub CLI access to the fork, Java, and Android SDK Build Tools 36.0.0. Set `ANDROID_HOME` to the SDK path and `PLUME_UPDATE_CERT_SHA256` to the SHA-256 signing certificate digest of the installed APK. Use an output directory outside the Git checkout.

   ```sh
   sh tools/plume-update-feed.sh /path/to/plume-feed
   ```

   The tool downloads the latest stable release and verifies the APK signature, package, and version. It writes `Plume-Custom-<versionCode>.apk` before updating `latest.properties`. Reuse the feed directory across releases so that it can reject version rollbacks and changes to an existing version's APK.
3. If the output directory is separate from the web host, upload the new versioned APK first and replace `latest.properties` last. Keep old versioned APKs available for TVs that cached an earlier manifest. Configure a short cache lifetime for `latest.properties`.

Install the first update-enabled build over the existing app with `adb install -r Plume-Custom.apk`. Publish a higher version to exercise **Plume settings → Updates** end to end. Android may prompt you to allow Plume to install apps and confirm the update.
