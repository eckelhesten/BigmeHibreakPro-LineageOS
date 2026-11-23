# LineageOS for Bigme Hibreak Pro

**LineageOS 23.2 (Android 16)** for the black-and-white **Bigme HiBreak Pro** (`B651`).

Clean Android without Bigme's bloatware and questionable software, with a custom e-ink stack made for this screen:

- microG, vanilla (no Google), or Google Play (MindTheGapps).
- Two e-ink profiles: **Default** for everyday use and **Reading** for books. E-reader apps switch to Reading automatically.
- Front light with warmth and auto-brightness, sleep screen (clock, optional weather, next calendar event), Quick Settings tiles, and remappable side keys.
- A/B updates in Settings, with automatic rollback if an update fails to boot.

*Disclaimer: Flash at your own risk. Installing this wipes your phone. I am not responsible if you brick your device or if it causes thermonuclear war.*

## Flavours

- **microG** (recommended): Official microG Services & Companion. Most apps work without a Google account.
- **vanilla**: No Google services at all.
- **gapps**: MindTheGapps (Google Play Store and Google apps).

Switching flavours later requires a wipe.

## Installing

### Requirements

- Black-and-white EEA HiBreak Pro on stock firmware **`Bigme_HiBreak_EEA_V1.0_20260312`**. Color, CN, and other firmware versions are not supported (the flash script checks this before touching anything).
- [Android Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb` and `fastboot`) and a good USB cable plugged straight into a rear port. Avoid hubs and front panel ports.
- **Back up everything.** Unlocking and flashing both wipe the phone. If you want a way back to stock later, dump your partitions first with [mtkclient](https://github.com/bkerler/mtkclient).

### 1. Unlock the bootloader

Skip this if your bootloader is already unlocked.

1. Turn on **Settings > Developer options > OEM unlocking** and **USB debugging**.
2. Plug in the phone and run:
   ```bash
   adb reboot bootloader
   fastboot flashing unlock
   ```
3. Confirm with **Volume Up** on the phone (this wipes your data).
4. Boot back into stock Android, skip through setup, and turn **USB debugging** back on.

### 2. Flash LineageOS

1. Download your flavour from the [latest release](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/releases/latest) and unzip it.
2. Open a terminal in the unzipped folder and run:
   - **macOS / Linux:**
     ```bash
     bash flash.sh
     ```
   - **Windows (PowerShell):**
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\flash.ps1
     ```
3. Type `YES` when asked. The script checks the hashes, firmware version, and unlock state, then flashes all partitions, wipes data, and reboots. Manual commands are in `INSTALL.txt` if you prefer doing it yourself.
4. Wait a few minutes for the first boot.

> [!NOTE]
> Both slot A and slot B work, so do **not** run `fastboot --set-active`. If flashing fails partway through, **do not reboot**. Fix your cable or USB port and run the flash script again to resume from fastboot.

## Updating

1. Copy the new update zip for your flavour onto the phone.
2. Go to **Settings > System > Updater > ⋮ > Install local update** and select the zip.
3. Reboot when it finishes. Your apps and data stay intact.

## E-ink Profiles

There are two profiles:

- **Default**: Fast refresh for everyday use (scrolling, browsing, typing, maps, video).
- **Reading**: Cleaner, sharper pages for books, comics, and long articles.

E-reader apps switch to Reading automatically. You can also toggle it from Quick Settings, a side key, or **Settings > E-ink & front light** (where you can set per-app profiles or invert colours for dark apps). If you see ghosting, hit the **Refresh screen** Quick Settings tile or bind it to a side key.

## Keep in Mind

- Banking and ID apps may not work if they block custom ROMs or unlocked bootloaders.
- VoLTE has only been tested on one carrier so far. If it does not work on yours, [open an issue](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/issues) with your carrier's name.
- The bootloader stays unlocked, so you will see an orange warning on every boot.

## Building from Source

The device tree and HiBreakParts are in `device/bigme/hibreakpro`, LineageOS patches in `patches/`, manifests in `manifests/`, and build scripts in `scripts/`. Extract the proprietary files from your phone into `vendor/bigme/hibreakpro`, then follow [docs/building.md](docs/building.md) and [docs/signing.md](docs/signing.md).

## Support the Project

Building and maintaining this ROM takes a lot of cloud build server time and AI tokens (around €150 out of pocket so far). If you find it useful, consider chipping in on Ko-fi:

<a href="https://ko-fi.com/eckelhesten"><img src="https://img.shields.io/badge/Support%20on-Ko--fi-FF5E5B?logo=ko-fi&logoColor=white" alt="Support on Ko-fi" height="24"></a>

## License & Credits

Licensed under the [Apache License 2.0](LICENSE). LineageOS, microG, and MindTheGapps keep their own licenses.

Built on LineageOS, microG, MindTheGapps, and `mtkclient`.
