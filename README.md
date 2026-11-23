# Plume Custom

A modified version of [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher) by aradhyas8, with changes for my TCL Google TV.

The original project supports many more TVs and has its own releases. This build does not take updates from it. Please report problems with this build in this repository.

## What is different

- **Video wallpaper and liquid glass.** OpenGL draws the video wallpaper, and the tiles and settings panel get clear glass from the moving video. The video skips the TV's video plane, so it no longer looks washed out.
- **Smooth video.** The wallpaper keeps an even frame cadence while you move between apps, and its loop crossfades over 4 seconds.
- **Continue Watching per app.** Each app gets its own row with its channels and Watch Next shows. Up and Down switch rows from memory in about 3 ms. The row title shows the app's mark.
- **Round tiles.** App tiles with a mark are circles, and every top bar icon sits on a glass disc.
- **Smaller settings panel.** It floats under the top bar with tighter rows and shows the wallpaper picture without waiting.
- **TCL fixes.** The focused tile no longer jumps when the TV wakes.
- **Nuvio mark.** Nuvio shows its own mark in place of a grey square.
- **In-app updates.** A build with an update feed checks for new releases in **Plume settings → Updates**.

The app installs as `dev.plume.custom`, next to the official Plume, and shows up as **Plume Custom**.

## Install

You need Android TV or Google TV on Android 8.0 or newer.

1. Download `Plume-Custom.apk` from [Releases](../../releases).
2. Install it with `adb install -r Plume-Custom.apk`, or copy it over on a USB drive and open it with a file manager.
3. Open **Plume Custom** and follow the **Finish setup** card.
4. Allow the **TV listings** permission when asked. Continue Watching needs it.

### Home button

Select **Plume settings → Home & remote → Home opens Plume**, then turn on **Plume: Home button** in Accessibility. You can also choose Plume Custom as the **Home app**.

> [!WARNING]
> Do not disable the stock launcher. On many TVs this breaks setup screens, input switching or the boot itself, and some TVs then need a factory reset.

### Android 13 and newer

Android 13 and newer greys out the Accessibility switch for sideloaded apps. If your TV has **Settings → Apps → Plume Custom → ⋮ → Allow restricted settings**, use it. If not, unlock it once from a computer on the same network.

1. Select **Settings → System → About** and press **Android TV OS build** seven times.
2. In **Settings → System → Developer options**, turn on **Network debugging** (**USB debugging** on some TVs).
3. Run these commands with the address that Plume shows.

   ```sh
   adb connect <tv-address>:5555
   adb shell pm grant dev.plume.custom android.permission.WRITE_SECURE_SETTINGS
   ```

4. Accept the prompt on the TV, then select **Try again** in Plume. Plume turns the service on by itself.

The same grant lets Plume set its screensaver and turn the Home service on again after a force-stop.

## Troubleshooting

- **Home still opens the old home screen.** Check that **Plume settings → Home & remote → Home opens Plume** shows **On**. A force-stop turns the accessibility service off. Open Plume once, or give it the adb grant above, to turn it on again.
- **Continue Watching is empty.** Allow the TV listings permission in **Settings → Apps → Plume Custom → Permissions**. The app you watch must publish to Android TV's Watch Next list. Also check **Plume settings → Shows row → From apps**.
- **The screensaver option asks for an adb permission.** Google TV hides other screensavers from its own settings. Plume needs the adb grant above to switch it for you.

## Building

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Without `keystore.properties`, Gradle signs it with your local debug key. [Release delivery](docs/releases.md) covers signing and the update feed.

## Credits

- **Plume Launcher** by [aradhyas8](https://github.com/aradhyas8) and its contributors.
- **Screensaver paintings** in [`screensaver/`](screensaver), public domain scans from Wikimedia Commons.
  - Frederic Edwin Church, *The Heart of the Andes*, *The Icebergs*, *Chimborazo*, *Cotopaxi*
  - Ivan Shishkin, *Rye*
  - Jasper Francis Cropsey, *Autumn on the Hudson River*
  - Martin Johnson Heade, *Thunder Storm on Narragansett Bay*
- **Wallpaper pack** in [`wallpapers/`](wallpapers).
- **Aerial Views.** The Apple Aerial scene list comes from the [Aerial Views](https://github.com/theothernt/AerialViews) project (GPL-3.0). NASA scenes come from the NASA Goddard Space Flight Center Scientific Visualization Studio and Earth Observatory.
- **App logos** from [Simple Icons](https://simpleicons.org) (CC0). All trademarks belong to their owners.
- **HDMI input and settings icons** from [Material Symbols](https://fonts.google.com/icons) by Google (Apache 2.0).

## License

Plume Launcher is Copyright (C) 2026 aradhyas8. This modified version was changed by BigDonke in 2026.

Both are released under the [GNU General Public License v3.0](LICENSE). Any distributed version, modified or not, must also be released under the GPL-3.0 with its full source code.
