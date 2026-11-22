# LineageOS for the Bigme HiBreak Pro

LineageOS 23.2 (Android 16) for the black-and-white **Bigme HiBreak Pro** e-ink phone.

Why you might want it:
- **Android 16** instead of 14, without Bigme's preinstalled apps.
- **Your choice of Google:** none, microG, or the full Google Play.
- **Made for e-ink:** sharp black text, four refresh modes, a mode per app, smooth scrolling.
- **A sleep screen** with the clock, and **front light** with warmth and an automatic mode.
- **Updates from the phone itself:** no reinstall and no computer needed.

It's an unofficial build, not affiliated with LineageOS or Bigme. **Installing it erases your
phone.**

## What works

Calls, SMS, mobile data, VoLTE, Wi-Fi, Bluetooth, both cameras, fingerprint, sensors and charging.

Good to know:
- VoLTE is tested on one carrier so far. If it doesn't work on yours, open an issue.
- It runs on Bigme's own kernel and vendor software, because Bigme hasn't published the kernel
  source.
- The bootloader stays unlocked. You'll see an orange warning at every boot, and it can't be
  locked again.

## Which flavour?

| Flavour | Google | Pick it if |
|---|---|---|
| **microG** (recommended) | microG, a free replacement | You want most apps to work, without a Google account. |
| **vanilla** | none | You want no Google code at all. |
| **gapps** | Google Play and Google apps | You need the Play Store or Google's own apps. |

Switching flavour later means erasing the phone again.

## Installing

**Before you start:**
1. **Back up** anything you care about. Everything on the phone is erased.
2. Check that the phone runs Bigme's firmware **`Bigme_HiBreak_EEA_V1.0_20260312`**. Other
   versions are untested.
3. Install the **Android platform tools** (`adb` and `fastboot`) on your computer.
4. **Unlock the bootloader.** On the phone, turn on Settings → Developer options → *OEM
   unlocking*. On the computer, run `adb reboot bootloader`, then `fastboot flashing unlock`,
   and press **Volume Up** on the phone to confirm. This erases the phone.
5. Set the phone up again (skip everything), turn on *USB debugging* and plug it in.
6. Run `adb shell getprop ro.boot.slot_suffix`. It must print **`_a`**. If it prints `_b`,
   please open an issue first.

**Install:**
1. Download `lineage-23.2-hibreakpro-<version>-<flavour>.zip` from the
   [latest release](https://github.com/eckelhesten/BigmeHibreakPro-LineageOS/releases/latest).
2. Unzip it.
3. On macOS or Linux, open a terminal in the unzipped folder and run:
   ```bash
   bash flash.sh
   ```
   It checks the files and the phone, asks you to type `YES`, then installs and restarts the
   phone. **The first start takes a few minutes.**

On **Windows**, type the commands from `INSTALL.txt` in that folder one by one.

**If something goes wrong:** the phone stays in fastboot mode. Don't restart it; just run
`bash flash.sh` (or the failed command) again.

**Going back to stock** needs Bigme's original images for your firmware, and we can't share
them. If you might want to go back, save your own copy before you start (for example with
[mtkclient](https://github.com/bkerler/mtkclient)).

## Updating

When a new version is out, download the **update zip** for your flavour from the release and
copy it to the phone. Then open Settings → System → Updater → menu (⋮) → *Install local update*,
pick the zip, and restart when it asks. Your apps and data stay. If the new version doesn't
start, the phone goes back to the old one by itself.

## E-ink modes

Choose a mode in Settings → *E-ink*, with the mode tile, or with the side key. You can also set a
mode per app.

**A good start:** *Default* for everything, *Clear* for your e-book app, *Fast* for the browser,
and *Smooth scrolling* on.

| Mode | What it looks like | Speed | Good | Less good |
|---|---|---|---|---|
| **Default** | Sharp black and white, greys as fine dots | ~0.3 s | Everyday use, no flashing | Photos look dotted |
| **Clear** | Perfect greys | ~0.5 s | E-books, comics, photos | Flashes on every change |
| **Balanced** | Smooth greys, no flash | ~0.75 s | Reading with pictures, without flashing | Slowest; can leave faint ghosts |
| **Fast** | Black and white dots | ~0.23 s | Scrolling, typing, maps, video | No real greys; more ghosting |

Speeds are from a tap to the change on screen, at room temperature. The panel is slower in the
cold.

**Questions people ask:**

- **Why does the screen flash?** You're in *Clear*. The flash is what makes the picture perfectly
  clean. Use *Default* or *Balanced* if it bothers you.
- **What are the faint leftovers of the last screen?** Ghosts. Tap the *Refresh* tile (or press
  the side key) for one full clean refresh. Leave *Automatic clean refresh* on.
- **How do page turns look in Clear?** One clean refresh, about half a second after you tap.
- **What does Smooth scrolling do?** While you scroll, the screen switches to *Fast*, then
  redraws in your mode when you stop. Small animations, like a spinner, don't trigger it.
- **What's Fast typing?** *Fast* while the keyboard is open, so letters appear right away.
- **Can Fast mode show greys?** Yes, with *Greys in Fast mode* (beta, off by default): smooth
  greys at the same speed. They can shift a little with temperature.
- **Why not make everything fast and grey?** The panel moves ink with electric pulses, and clean
  greys simply need more pulses. Each mode is a different trade between speed, greys and
  ghosting.

## Building it yourself

The device tree is in `device/bigme/hibreakpro` and the changes to LineageOS are in `patches/`.
You also need Bigme's proprietary files in `vendor/bigme`, which aren't published here; take them
from your own phone. See [docs/building.md](docs/building.md) and [docs/signing.md](docs/signing.md).

## License

This repository is under the [Apache License 2.0](LICENSE). LineageOS, microG and MindTheGapps
keep their own licenses.

## Thanks

LineageOS, microG, MindTheGapps and mtkclient, and the HiBreak Pro owners who keep asking Bigme
for the kernel source.
