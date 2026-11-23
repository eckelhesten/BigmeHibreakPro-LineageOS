# LineageOS for Bigme Hibreak Pro

**LineageOS 23.2 (Android 16)** for the black-and-white **Bigme HiBreak Pro** (`B651`).

Clean Android without Bigme's bundled apps and questionable software, paired with a custom e-ink stack built for this screen:

- **Your choice of Google:** microG, vanilla (no Google), or Google Play.
- **Made for e-ink:** Two profiles that just work — **Default** for fast everyday use and **Reading** for crisp pages. E-reader apps switch to Reading automatically.
- **Hardware support:** Front light with warmth and auto-brightness, sleep screen (clock, optional weather, next calendar event), Quick Settings tiles, and remappable side keys.
- **On-device updates:** Seamless A/B updates straight from Settings, with automatic rollback if an update fails to boot.

*Disclaimer: Flash at your own risk. Installing this erases your phone, and I am not responsible if you brick your device or if it causes thermonuclear war.*

## Flavours

- **microG** *(recommended)* — Official microG Services & Companion. Most apps work without a Google account.
- **vanilla** — No Google services at all.
- **gapps** — MindTheGapps. Google Play Store and Google apps.

Switching flavours later requires a wipe.

## Installing

### Requirements

- Black-and-white EEA HiBreak Pro running stock firmware **`Bigme_HiBreak_EEA_V1.0_20260312`** (Color, CN, and other versions are unsupported; the flash script checks this first).
- [Android Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb` and `fastboot`) and a known-good USB cable plugged directly into a rear port (avoid hubs and front ports).
- **Back up everything** — unlocking and flashing wipe the phone. *(Optional: dump stock partitions with [mtkclient](https://github.com/bkerler/mtkclient) if you want a way to return to stock later.)*

### 1. Unlock the bootloader

Skip this if your bootloader is already unlocked.

1. Enable **Settings → Developer options → OEM unlocking** and **USB debugging**.
2. Connect the phone and run:
   ```bash
   adb reboot bootloader
   fastboot flashing unlock
   ```
3. Confirm with **Volume Up** on the phone (wipes data).
4. Boot back into stock Android, skip setup, and re-enable **USB debugging**.

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
3. Type `YES` when prompted. The script verifies checksums, firmware, and unlock state, flashes all partitions, wipes data, and reboots. *(Manual commands are in `INSTALL.txt`.)*
4. Wait a few minutes for the first boot.

> [!NOTE]
> Both slot A and slot B work automatically — do **not** run `fastboot --set-active`. If flashing stops partway through, **don't reboot**; fix the USB cable/port and re-run the flash script to resume safely from fastboot.

## Updating

1. Copy the new update zip for your flavour onto the phone.
2. Open **Settings → System → Updater → ⋮ → Install local update** and select the zip.
3. Reboot when prompted. Apps and data stay intact.

## E-ink Profiles

Two profiles cover everything, with nothing to tune:

- **Default** — Fast and responsive for everyday use (scrolling, browsing, typing, maps, video).
- **Reading** — Crisp pages with minimal ghosting for books, comics, and long articles.

E-reader apps switch to **Reading** automatically. You can also toggle profiles anytime from Quick Settings, a remapped side key, or **Settings → E-ink & front light** (where you can set per-app profiles or invert colours for dark apps). To clear any leftover ghosting manually, tap the **Refresh screen** Quick Settings tile or bind it to a side key.

## Keep in Mind

- **Banking & ID apps** may not work if they block custom ROMs or unlocked bootloaders.
- **VoLTE** is verified on one carrier so far. If it doesn't work on yours, [open an issue](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/issues) with your carrier's name.
- **Bootloader stays unlocked:** You'll see an orange warning on every boot.

## Building from Source

Device tree and HiBreakParts live in `device/bigme/hibreakpro`, LineageOS patches in `patches/`, manifests in `manifests/`, and build scripts in `scripts/`. Extract your phone's proprietary files into `vendor/bigme/hibreakpro`, then see [docs/building.md](docs/building.md) and [docs/signing.md](docs/signing.md).

## Support the Project

Building and maintaining this ROM takes a lot of cloud build server time and AI tokens (around €150 out of pocket so far). If it's breathing new life into your HiBreak Pro, consider chipping in on Ko-fi:

<a href="https://ko-fi.com/eckelhesten"><img src="https://img.shields.io/badge/Support%20on-Ko--fi-FF5E5B?logo=ko-fi&logoColor=white" alt="Support on Ko-fi" height="24"></a>

## License & Credits

Licensed under the [Apache License 2.0](LICENSE). LineageOS, microG, and MindTheGapps keep their own licenses.

Built on the work of LineageOS, microG, MindTheGapps, and `mtkclient`.
