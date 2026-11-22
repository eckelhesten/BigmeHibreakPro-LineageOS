# LineageOS for the Bigme HiBreak Pro

**LineageOS 23.2 (Android 16)** for the black-and-white **Bigme HiBreak Pro** (`B651`).

Clean Android without Bigme's bundled apps, paired with a custom e-ink stack built for this screen:

- **Your choice of Google:** microG, vanilla (no Google), or Google Play.
- **Made for e-ink:** Crisp 1-bit text, four refresh modes, per-app modes (including colour inversion for dark apps), smooth scrolling, and fast typing.
- **Hardware support:** Front light with warmth and auto-brightness, sleep screen (clock, optional weather, next calendar event), Quick Settings tiles, and remappable side keys.
- **On-device updates:** Seamless A/B updates straight from Settings, with automatic rollback if an update fails to boot.

*Disclaimer: Flash at your own risk. Installing this erases your phone, and I am not responsible if you brick your device or if it causes thermonuclear war.*

## What Works & Caveats

**Works:** Calls, SMS, mobile data, VoLTE, Wi-Fi, Bluetooth, both cameras, fingerprint reader, sensors, front light, side keys, and charging.

**Keep in mind:**
- **Banking & ID apps** may not work as intended if they block custom ROMs or unlocked bootloaders.
- **VoLTE** is verified on one carrier so far. If it doesn't work on yours, [open an issue](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/issues) with your carrier's name.
- **Bootloader stays unlocked:** You'll see an orange warning on every boot.

## Flavours

| Flavour | Google services | Pick this if |
|---|---|---|
| **microG** *(recommended)* | Official microG Services & Companion | You want most apps to work without a Google account |
| **vanilla** | None | You want zero Google code |
| **gapps** | MindTheGapps (Play Store & apps) | You need Google Play and Google's apps |

Switching flavours later requires a wipe.

## Installing

### Before you start

1. **Back up everything.** Both unlocking and flashing wipe the phone. Returning to stock later requires Bigme's original images (which can't be shared here), so dump your partitions first with [mtkclient](https://github.com/bkerler/mtkclient) if you want a way back.
2. **Check stock firmware:** Your phone must run **`Bigme_HiBreak_EEA_V1.0_20260312`**. Other versions are untested.
3. **Install [Android Platform-Tools](https://developer.android.com/tools/releases/platform-tools)** (`adb` and `fastboot`) on your computer.
4. **Unlock the bootloader:** Turn on **Settings → Developer options → OEM unlocking**, then run:
   ```bash
   adb reboot bootloader
   fastboot flashing unlock
   ```
   Confirm with **Volume Up** on the phone (wipes data).
5. **Re-enable USB debugging:** Boot back into stock Android, skip setup, enable **USB debugging**, and reconnect USB.
6. **Verify slot A is active:**
   ```bash
   adb shell getprop ro.boot.slot_suffix
   ```
   This must print **`_a`** (Bigme's `fastbootd` only flashes reliably from slot A). If it prints `_b`, stop and [open an issue](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/issues).

### Flash

1. Download `lineage-23.2-hibreakpro-<version>-<flavour>.zip` from the [latest release](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/releases/latest) and unzip it.
2. **macOS / Linux:** Open a terminal in the unzipped folder and run:
   ```bash
   bash flash.sh
   ```
   It checks checksums and slot A, asks you to type `YES`, flashes, wipes data, and reboots.
3. **Windows:** Run the commands in `INSTALL.txt` one by one from the unzipped folder.
4. **Wait for the first boot** (takes a few minutes).

> [!NOTE]
> If a step fails, the phone stays safely in fastboot mode. **Don't reboot**; just run `bash flash.sh` (or repeat the failed command) again.

## Updating

1. Copy the new **update zip** for your flavour onto the phone.
2. Go to **Settings → System → Updater → ⋮ → Install local update** and pick the zip.
3. Reboot when prompted. Your apps and data stay intact.

## E-ink Modes

Change modes in **Settings → E-ink & front light**, from the **E-ink mode** Quick Settings tile, or with a remapped **side key**. You can also set a mode per app (including **Default, colours inverted** for dark apps).

**Recommended starting point:** **Default** globally, **Clear** for e-book apps, **Fast** for browsing, and **Smooth scrolling** on.

| Mode | Look | Speed | Best for | Trade-off |
|---|---|---|---|---|
| **Default** | Sharp black text, dithered greys | ~0.3 s | Everyday use | Photos look dotted |
| **Clear** | Full 16-level greyscale | ~0.5 s | E-books, comics, photos | Flashes black on every update |
| **Balanced** | Smooth 16-level greyscale, no flash | ~0.75 s | Illustrated reading | Slowest; can leave faint ghosts |
| **Fast** | Dithered black and white | ~0.23 s | Scrolling, typing, maps, video | No true greys by default; more ghosting |

*Measured from tap to screen update at room temperature; e-ink is slower in the cold.*

<details>
<summary><strong>E-ink FAQ</strong></summary>

- **Why does the screen flash black?** That's **Clear** mode clearing residual charge for zero ghosting. Use **Default** or **Balanced** if you don't want flashing.
- **How do I clear faint leftovers (ghosting)?** Tap the **Refresh screen** tile (or bind a side key to it) for a full refresh, and keep **Automatic clean refresh** on.
- **How do page turns work in Clear mode?** Page-turn animations are collapsed into a single clean refresh ~0.5 s after tapping.
- **What do Smooth scrolling & Fast typing do?** They temporarily drop into **Fast** mode while scrolling or typing, then redraw cleanly in your chosen mode when motion stops. Small spinners don't trigger it.
- **Can Fast mode show real greys?** Yes. Turn on **Greys in Fast mode** (beta, off by default) in **Settings → E-ink & front light** for smooth greys at Fast speeds. Tones may shift slightly with temperature.
- **Why isn't there one mode that's both fast and greyscale?** E-ink moves charged pigment with timed voltage pulses; clean 16-level greys need longer pulse sequences than black-and-white transitions.

</details>

## Building from Source

Device tree and HiBreakParts live in `device/bigme/hibreakpro`, LineageOS patches in `patches/`, manifests in `manifests/`, and build scripts in `scripts/`. Extract your phone's proprietary files into `vendor/bigme/hibreakpro`, then see [docs/building.md](docs/building.md) and [docs/signing.md](docs/signing.md).

## Support the Project

Building and maintaining this ROM takes a lot of cloud build server time and AI tokens (around €150 out of pocket so far). If it's breathing new life into your HiBreak Pro, consider chipping in on Ko-fi:

<a href="https://ko-fi.com/eckelhesten"><img src="https://img.shields.io/badge/Support%20on-Ko--fi-FF5E5B?logo=ko-fi&logoColor=white" alt="Support on Ko-fi" height="24"></a>

## License & Credits

Licensed under the [Apache License 2.0](LICENSE). LineageOS, microG, and MindTheGapps keep their own licenses.

Built on the work of LineageOS, microG, MindTheGapps, and `mtkclient`.
