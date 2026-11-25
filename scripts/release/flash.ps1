# First install of LineageOS for Bigme Hibreak Pro (B/W EEA), from this folder's images.
# Needs: adb and fastboot (Android platform tools), the bootloader unlocked, and stock firmware
# Bigme_HiBreak_EEA_V1.0_20260312 (slot A or slot B; also works when switching LineageOS flavours).
#
# Run from PowerShell in this folder:
#   powershell -ExecutionPolicy Bypass -File .\flash.ps1
#
# WIPES THE PHONE. Stops at the first error and leaves the phone in fastboot; if anything fails,
# DO NOT REBOOT - fix the cable/port and re-run flash.ps1 to resume safely.

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

$ExpectedVendorFp = "Bigme/HiBreak_EEA/Smartphone:12/SP1A.210812.016/1773045151:user/release-keys"
$script:Flashing = $false

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message"
}

function Fail([string]$Message) {
    # Caught below (prints ERROR, the resume warning while flashing, and exits 1).
    throw $Message
}

# Windows PowerShell 5.1 turns native stderr into terminating ErrorRecords when
# $ErrorActionPreference = "Stop" and output is redirected (2>&1 / 2>$null).
# Temporarily switch to "Continue" around every native call and rely on $LASTEXITCODE.
function Invoke-Native {
    param([Parameter(Mandatory)][string]$Exe, [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Exe @Arguments
        $rc = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($rc -ne 0) {
        throw "Command failed ($rc): $Exe $($Arguments -join ' ')"
    }
}

function Invoke-CaptureStdout {
    param([Parameter(Mandatory)][string]$Exe, [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = (& $Exe @Arguments 2>$null | Out-String)
        $rc = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return [pscustomobject]@{ Output = $out.Trim(); ExitCode = $rc }
}

function Invoke-CaptureAll {
    param([Parameter(Mandatory)][string]$Exe, [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $Exe @Arguments 2>&1 | ForEach-Object { "$_" })
        $rc = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return [pscustomobject]@{ Lines = $lines; ExitCode = $rc }
}

function Get-FastbootConnected {
    $res = Invoke-CaptureStdout "fastboot" @("devices")
    return ($res.Output.Length -gt 0)
}

function Get-FastbootVar([string]$Name) {
    $res = Invoke-CaptureAll "fastboot" @("getvar", $Name)
    foreach ($line in $res.Lines) {
        $s = $line.Trim()
        if ($s -match "^$([regex]::Escape($Name)):\s*(.+)$") {
            return $Matches[1].Trim()
        }
    }
    return ""
}

function Get-FastbootUserspace {
    $v = Get-FastbootVar "is-userspace"
    if ($v -ieq "yes") { return "yes" }
    return "no"
}

function Get-FastbootSlot {
    $s = (Get-FastbootVar "current-slot").TrimStart("_")
    if ($s -eq "a" -or $s -eq "b") { return $s }
    return ""
}

function Assert-OneDevice {
    # Exactly one phone, over adb and fastboot together: with two, every command could go to either.
    $adbLines = @((Invoke-CaptureStdout "adb" @("devices")).Output -split "`r?`n" |
        Select-Object -Skip 1 | Where-Object { $_ -match "\s(device|recovery|sideload|unauthorized)$" })
    $fbLines = @((Invoke-CaptureStdout "fastboot" @("devices")).Output -split "`r?`n" |
        Where-Object { $_.Trim().Length -gt 0 })
    $n = $adbLines.Count + $fbLines.Count
    if ($n -gt 1) { Fail "$n phones connected: connect only the one to flash." }
}

function Wait-FastbootMode([string]$WantUserspace) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Get-FastbootConnected) {
            if ((Get-FastbootUserspace) -eq $WantUserspace) {
                # "no" is also what a failed getvar gives (fastbootd going down on its way to the
                # bootloader): the bootloader is there once it answers for its slot too.
                if ($WantUserspace -eq "yes" -or (Get-FastbootSlot) -ne "") {
                    return
                }
            }
        }
        Start-Sleep -Seconds 1
    }
    if ($WantUserspace -eq "yes") {
        throw "Timed out waiting for fastbootd (userspace fastboot)."
    } else {
        throw "Timed out waiting for the bootloader (LK fastboot)."
    }
}

try {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Fail "adb not found: install Android platform tools and add them to PATH."
    }
    if (-not (Get-Command fastboot -ErrorAction SilentlyContinue)) {
        Fail "fastboot not found: install Android platform tools and add them to PATH."
    }

    Write-Step "Checking the images"
    if (-not (Test-Path -LiteralPath "SHA256SUMS")) {
        Fail "SHA256SUMS not found in $PSScriptRoot"
    }
    $checked = 0
    foreach ($line in Get-Content -LiteralPath "SHA256SUMS") {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) { continue }
        $checked++
        if ($trimmed -notmatch "^([0-9a-fA-F]{64})\s+\*?(.+)$") {
            Fail "Malformed line in SHA256SUMS: $trimmed"
        }
        $expected = $Matches[1].ToLowerInvariant()
        $file = $Matches[2].Trim()
        if (-not (Test-Path -LiteralPath $file)) {
            Fail "Missing image file: $file"
        }
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
        if ($actual -ne $expected) {
            Fail "Checksum mismatch for $file (expected $expected, got $actual)"
        }
        Write-Host "${file}: OK"
    }
    if ($checked -eq 0) {
        Fail "SHA256SUMS lists no images."
    }

    Write-Step "Detecting the phone"
    Assert-OneDevice
    $mode = ""
    $adbState = Invoke-CaptureStdout "adb" @("get-state")
    if ($adbState.ExitCode -eq 0 -and $adbState.Output -eq "device") {
        $mode = "adb"
    } elseif (Get-FastbootConnected) {
        if ((Get-FastbootUserspace) -eq "yes") {
            $mode = "fastbootd"
        } else {
            $mode = "bootloader"
        }
    } else {
        Fail "No phone found over adb or fastboot: connect it with a good cable (rear USB port recommended), enable USB debugging, and accept the prompt."
    }

    $slot = ""
    if ($mode -eq "adb") {
        $hw = (Invoke-CaptureStdout "adb" @("shell", "getprop", "ro.boot.hardware")).Output
        if ($hw -ne "mt6877") {
            Fail "Unexpected hardware '$hw', expected 'mt6877' (Bigme HiBreak Pro)."
        }

        $vfp = (Invoke-CaptureStdout "adb" @("shell", "getprop", "ro.vendor.build.fingerprint")).Output
        if ($vfp -ne $ExpectedVendorFp) {
            Fail "Unexpected vendor firmware '$vfp'. This ROM requires the black-and-white EEA HiBreak Pro running stock Bigme_HiBreak_EEA_V1.0_20260312 (not Color, CN, or other versions)."
        }

        $locked = (Invoke-CaptureStdout "adb" @("shell", "getprop", "ro.boot.flash.locked")).Output
        $vstate = (Invoke-CaptureStdout "adb" @("shell", "getprop", "ro.boot.vbmeta.device_state")).Output
        if ($locked -ne "0" -and $vstate -ne "unlocked") {
            Fail "Bootloader is locked (ro.boot.flash.locked='$locked', device_state='$vstate'). Unlock it first: adb reboot bootloader && fastboot flashing unlock"
        }

        $rawSlot = (Invoke-CaptureStdout "adb" @("shell", "getprop", "ro.boot.slot_suffix")).Output
        $slot = $rawSlot.TrimStart("_")
        if ($slot -ne "a" -and $slot -ne "b") {
            Fail "Unexpected active slot '$rawSlot'."
        }
        Write-Host "phone ready over adb: Bigme HiBreak Pro (20260312 vendor), unlocked, slot $slot"
    } else {
        $slot = Get-FastbootSlot
        if ($slot -eq "") {
            Fail "Could not read current-slot from fastboot."
        }
        if ($mode -eq "bootloader") {
            $unlocked = Get-FastbootVar "unlocked"
            if ($unlocked -ine "yes") {
                Fail "Bootloader reports unlocked='$unlocked' (must be unlocked before flashing)."
            }
        }
        Write-Host "phone detected in $mode mode on slot $slot (resuming install)"
    }

    Write-Host ""
    Write-Host "Tip: use a known-good USB cable connected directly to a rear port on your computer."
    Write-Host "This ERASES everything on the phone and installs LineageOS onto slot $slot."
    $answer = Read-Host "Type YES to continue"
    if ($answer -cne "YES") {
        Fail "Cancelled."
    }

    $script:Flashing = $true

    if ($mode -eq "adb") {
        Write-Step "Rebooting to the bootloader"
        Invoke-Native adb @("reboot", "bootloader")
        Wait-FastbootMode "no"
        $mode = "bootloader"
    }

    if ($mode -eq "bootloader") {
        $slot = Get-FastbootSlot
        if ($slot -eq "") { throw "Could not read current-slot in bootloader." }
        $unlocked = Get-FastbootVar "unlocked"
        if ($unlocked -ine "yes") { throw "Bootloader reports unlocked='$unlocked'." }

        Write-Step "Flashing LineageOS recovery (boot_$slot) and entering fastbootd"
        Invoke-Native fastboot @("flash", "boot_$slot", "boot.img")
        Invoke-Native fastboot @("reboot", "fastboot")
        Wait-FastbootMode "yes"
        $mode = "fastbootd"
    }

    if ($mode -eq "fastbootd") {
        $slot = Get-FastbootSlot
        if ($slot -eq "") { throw "Could not read current-slot in fastbootd." }

        $snap = Get-FastbootVar "snapshot-update-status"
        if ($snap -ne "" -and $snap -ne "none") {
            Write-Step "Cancelling pending Virtual A/B snapshot ($snap)"
            Invoke-Native fastboot @("snapshot-update", "cancel")
        }

        Write-Step "Flashing system, system_ext, product (slot $slot)"
        Invoke-Native fastboot @("flash", "system_$slot", "system.img")
        Invoke-Native fastboot @("flash", "system_ext_$slot", "system_ext.img")
        Invoke-Native fastboot @("flash", "product_$slot", "product.img")

        Write-Step "Rebooting to the bootloader"
        Invoke-Native fastboot @("reboot", "bootloader")
        Wait-FastbootMode "no"
    }

    $slot = Get-FastbootSlot
    if ($slot -eq "") { throw "Could not read current-slot in bootloader." }

    Write-Step "Flashing boot, dtbo, vbmeta (slot $slot)"
    Invoke-Native fastboot @("flash", "boot_$slot", "boot.img")
    Invoke-Native fastboot @("flash", "dtbo_$slot", "dtbo.img")
    Invoke-Native fastboot @("flash", "vbmeta_$slot", "vbmeta.img")
    Invoke-Native fastboot @("flash", "vbmeta_system_$slot", "vbmeta_system.img")
    Invoke-Native fastboot @("flash", "vbmeta_vendor_$slot", "vbmeta_vendor.img")

    Write-Step "Wiping data and metadata"
    Invoke-Native fastboot @("-w")
    Invoke-Native fastboot @("format:ext4", "metadata")

    $script:Flashing = $false
    Write-Step "Rebooting into LineageOS (the first boot takes a few minutes)"
    Invoke-Native fastboot @("reboot")
} catch {
    Write-Host ""
    Write-Host "ERROR: $_" -ForegroundColor Red
    if ($script:Flashing) {
        Write-Host ""
        Write-Host "========================================================================" -ForegroundColor Yellow
        Write-Host "FLASHING STOPPED PARTWAY THROUGH - DO NOT REBOOT THE PHONE!" -ForegroundColor Yellow
        Write-Host "Rebooting with half-flashed partitions can trigger a dm-verity bootloop." -ForegroundColor Yellow
        Write-Host "Leave the phone in fastboot mode, check/swap your USB cable (use a rear" -ForegroundColor Yellow
        Write-Host "port directly on the computer, not a hub), and re-run flash.ps1 to" -ForegroundColor Yellow
        Write-Host "resume safely." -ForegroundColor Yellow
        Write-Host "========================================================================" -ForegroundColor Yellow
    }
    exit 1
}
