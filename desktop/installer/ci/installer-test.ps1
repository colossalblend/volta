<#
  The installer, the installed app and the uninstaller, end to end, the way a user meets
  them (windows.yml runs this on windows-latest). Everything happens under a user name
  with Cyrillic letters and spaces: %APPDATA%, %LOCALAPPDATA%, %TEMP% and the install
  folder all point into "…\Арсений Тестов\…".

    1. the jpackage/MSI Volta of earlier builds is installed, with user data;
    2. the new installer is run silently into a Cyrillic folder: files, Apps & Features
       entry, shortcuts, volta:// and the old MSI install gone, the data kept;
    3. the installed app checks itself (--self-test) from there;
    4. smoke test: the app's window comes up, a second start hands over (ack) and brings the
       window back (also from the tray), a hung Volta gets the «не отвечает» dialog, and
       --quit ends it cleanly with no core left behind;
    5. an update over a running Volta (--silent --update --launch, as the in-app updater
       does): Volta is closed and started again, the data survives;
    6. the uninstaller keeps the data by default; with --remove-data it goes; the registry,
       shortcuts, autostart and volta:// are cleaned either way.

  Pictures of real windows go to -Shots; the installers' logs are printed at the end.
#>
param(
    [Parameter(Mandatory = $true)][string]$Setup,
    [Parameter(Mandatory = $true)][string]$Shots,
    [Parameter(Mandatory = $true)][string]$Work,
    [string]$LegacyMsi = ""
)

$ErrorActionPreference = "Continue"
$script:failed = 0
New-Item -ItemType Directory -Force -Path $Shots, $Work | Out-Null

function Fail($message) {
    Write-Host "::error title=Installer test::$message"
    Write-Host "FAIL $message"
    $script:failed++
}
function Ok($message) { Write-Host "ok   $message" }
function Check($condition, $message) { if ($condition) { Ok $message } else { Fail $message } }

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class W {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr h);
    [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr h);
    // A visible window first: Java keeps hidden helper windows (the tray's, for one) too.
    public static IntPtr Find(int pid, string title) {
        IntPtr found = IntPtr.Zero, hidden = IntPtr.Zero;
        EnumWindows((h, l) => {
            uint p; GetWindowThreadProcessId(h, out p);
            if (p == (uint)pid) {
                var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
                if (title == null ? sb.Length > 0 : sb.ToString().StartsWith(title)) {
                    if (IsWindowVisible(h)) { found = h; return false; }
                    if (hidden == IntPtr.Zero) hidden = h;
                }
            }
            return true;
        }, IntPtr.Zero);
        return found != IntPtr.Zero ? found : hidden;
    }
    public static string Titles(int pid) {
        var all = new StringBuilder();
        EnumWindows((h, l) => {
            uint p; GetWindowThreadProcessId(h, out p);
            if (p == (uint)pid) { var sb = new StringBuilder(512); GetWindowText(h, sb, 512); if (sb.Length > 0) all.Append("[" + sb + (IsWindowVisible(h) ? "" : " hidden") + "] "); }
            return true;
        }, IntPtr.Zero);
        return all.ToString();
    }
    public static int ForegroundPid() { uint p; GetWindowThreadProcessId(GetForegroundWindow(), out p); return (int)p; }
    public static int PidOf(IntPtr h) { uint p; GetWindowThreadProcessId(h, out p); return (int)p; }
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    [DllImport("wininet.dll")] public static extern bool InternetSetOption(IntPtr h, int option, IntPtr buffer, int length);
    public static void ProxyChanged() { InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0); }
}
"@

function Shot($name) {
    try {
        $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
        $bmp.Save((Join-Path $Shots "$name.png"))
        $g.Dispose(); $bmp.Dispose()
    } catch { Write-Host "no screenshot ${name}: $_" }
}

function WaitFor([scriptblock]$condition, [int]$seconds) {
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        if (& $condition) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return [bool](& $condition)
}

function Run($exe, [string[]]$arguments, [int]$seconds = 300) {
    Write-Host ">> $exe $($arguments -join ' ')"
    $p = Start-Process -FilePath $exe -ArgumentList $arguments -PassThru
    if (-not $p.WaitForExit($seconds * 1000)) { Fail "$exe did not finish in $seconds s"; try { $p.Kill() } catch {}; return -1 }
    return $p.ExitCode
}

function Quote($s) { return '"' + $s + '"' }

function Step($what) { Write-Host "== $(Get-Date -Format HH:mm:ss) $what" }

# jpackage's Volta.exe puts its folder on PATH and starts itself once more as a child, which
# runs Java (and so the window, the tray and the answer to a second start) while the first
# one waits for it. Windows are looked for in the whole tree.
function TreePids([int]$root) {
    $ids = @($root)
    $kids = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$root" -ErrorAction SilentlyContinue | ForEach-Object { [int]$_.ProcessId })
    foreach ($k in $kids) {
        $ids += $k
        $ids += @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$k" -ErrorAction SilentlyContinue | ForEach-Object { [int]$_.ProcessId })
    }
    return $ids
}
function FindWin($p, $title) {
    foreach ($id in (TreePids $p.Id)) { $h = [W]::Find($id, $title); if ($h -ne [IntPtr]::Zero) { return $h } }
    return [IntPtr]::Zero
}
function TreeTitles($p) { return ((TreePids $p.Id) | ForEach-Object { [W]::Titles($_) }) -join "" }
function StopTree($p) { foreach ($id in (TreePids $p.Id)) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue } }

$inetKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
function ProxyState() {
    $k = Get-ItemProperty $inetKey -ErrorAction SilentlyContinue
    $c = (Get-ItemProperty (Join-Path $inetKey "Connections") -Name DefaultConnectionSettings -ErrorAction SilentlyContinue).DefaultConnectionSettings
    return [pscustomobject]@{ Enable = $k.ProxyEnable; Server = $k.ProxyServer; Override = $k.ProxyOverride; Pac = $k.AutoConfigURL; Conn = $c }
}
$proxyAtStart = ProxyState
Write-Host "Windows proxy at start: enable=$($proxyAtStart.Enable) server=$($proxyAtStart.Server)"
function ProxyBack($when) {
    $now = ProxyState
    if ($now.Enable -eq 1 -and "$($now.Server)" -like "127.0.0.1:*" -and "$($now.Server)" -ne "$($proxyAtStart.Server)") {
        Fail "$when left the Windows proxy on ($($now.Server)); put back"
        $enable = if ($proxyAtStart.Enable) { [int]$proxyAtStart.Enable } else { 0 }
        Set-ItemProperty $inetKey -Name ProxyEnable -Value $enable -Type DWord
        foreach ($pair in @(@("ProxyServer", $proxyAtStart.Server), @("ProxyOverride", $proxyAtStart.Override), @("AutoConfigURL", $proxyAtStart.Pac))) {
            if ($null -eq $pair[1]) { Remove-ItemProperty $inetKey -Name $pair[0] -ErrorAction SilentlyContinue }
            else { Set-ItemProperty $inetKey -Name $pair[0] -Value $pair[1] }
        }
        if ($null -ne $proxyAtStart.Conn) {
            Set-ItemProperty (Join-Path $inetKey "Connections") -Name DefaultConnectionSettings -Value $proxyAtStart.Conn -Type Binary
        }
        [W]::ProxyChanged()
    }
}

# ---------------------------------------------------------------- a Cyrillic user

$user = Join-Path $Work "Арсений Тестов"
$env:APPDATA = Join-Path $user "AppData\Roaming"
$env:LOCALAPPDATA = Join-Path $user "AppData\Local"
$env:TEMP = Join-Path $user "AppData\Local\Temp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:APPDATA, $env:LOCALAPPDATA, $env:TEMP | Out-Null
$data = Join-Path $env:APPDATA "Volta"
$dir = Join-Path $user "Мои программы\Volta"
$exe = Join-Path $dir "Volta.exe"
$appLog = Join-Path $data "logs\volta.log"
$setupLog = Join-Path $Work "setup.log"
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Volta"
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$startLink = Join-Path ([Environment]::GetFolderPath("Programs")) "Volta.lnk"
$desktopLink = Join-Path ([Environment]::GetFolderPath("DesktopDirectory")) "Volta.lnk"
# WScript.Shell reads a shortcut's target through the ANSI code page (Cyrillic comes back as
# «?» on an English Windows); the Shell's own objects read it as it is.
$shellApp = New-Object -ComObject Shell.Application
function LinkTarget($lnk) {
    if (-not (Test-Path $lnk)) { return $null }
    try { return $shellApp.Namespace((Split-Path $lnk)).ParseName((Split-Path $lnk -Leaf)).GetLink.Path } catch { return "($_)" }
}
Write-Host "user folders under $user; install into $dir"

$bytes = [System.IO.File]::ReadAllBytes($Setup)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
Check ($text.Contains('level="asInvoker"')) "the installer asks for no administrator rights (asInvoker manifest)"
Write-Host ("installer size: {0:N1} MB" -f ($bytes.Length / 1MB))

# ---------------------------------------------------------------- the window itself

Step "the installer window"

$ui = Start-Process -FilePath $Setup -ArgumentList @("--lang", "ru", "--log", (Quote (Join-Path $Work "ui.log"))) -PassThru
Start-Sleep -Seconds 6
Check ([W]::Find($ui.Id, "Установка Volta") -ne [IntPtr]::Zero) "the installer window opens ($([W]::Titles($ui.Id)))"
Shot "real-installer-welcome-ru"
Stop-Process -Id $ui.Id -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 1. the old MSI Volta

Step "1. the old MSI Volta"

$legacyDir = Join-Path ([Environment]::GetFolderPath("LocalApplicationData")) "Volta"
function DataList() {
    if (-not (Test-Path $data)) { return "(no data folder)" }
    return ((Get-ChildItem -Recurse -File $data | ForEach-Object { $_.FullName.Substring($data.Length + 1) + " " + $_.Length }) -join "; ")
}
if ($LegacyMsi -and (Test-Path $LegacyMsi)) {
    $code = Run "msiexec.exe" @("/i", (Quote $LegacyMsi), "/qn", "/norestart", "REBOOT=ReallySuppress", "/l*v", (Quote (Join-Path $Work "legacy-install.log"))) 180
    Check ($code -eq 0 -or $code -eq 3010) "the old jpackage/MSI Volta installs (exit $code)"
    Check (Test-Path (Join-Path $legacyDir "Volta.exe")) "old Volta at $legacyDir"

    # What the old (jpackage/WiX) uninstaller does to the user's things, with Volta running
    # as it usually is (in the tray).
    New-Item -ItemType Directory -Force -Path $data | Out-Null
    Set-Content -Path (Join-Path $data "user-marker.txt") -Value "подписки пользователя" -Encoding utf8
    $old = Start-Process -FilePath (Join-Path $legacyDir "Volta.exe") -PassThru
    $null = WaitFor { $old.HasExited -or (Test-Path (Join-Path $data "volta.json")) } 60
    Start-Sleep -Seconds 5
    Write-Host "data before the old uninstaller: $(DataList)"
    $code = Run "msiexec.exe" @("/x", (Quote $LegacyMsi), "/qn", "/norestart", "REBOOT=ReallySuppress", "/l*v", (Quote (Join-Path $Work "legacy-uninstall.log"))) 300
    Write-Host "old uninstaller (msiexec /x, Volta running): exit $code; Volta still running: $(-not $old.HasExited)"
    Write-Host "install folder after: $(if (Test-Path $legacyDir) { (Get-ChildItem $legacyDir -Name) -join ', ' } else { '(gone)' })"
    Write-Host "data after the old uninstaller: $(DataList)"
    Select-String -Path (Join-Path $Work "legacy-uninstall.log") -Pattern "RemoveFolder|RemoveFile|Roaming|RMSESSION|Restart Manager|shut down" -ErrorAction SilentlyContinue |
        Select-Object -First 25 | ForEach-Object { Write-Host "   msi: $($_.Line.Trim())" }
    if (-not $old.HasExited) { Stop-Process -Id $old.Id -Force -ErrorAction SilentlyContinue; Start-Sleep 2 }
    Write-Host "proxy backup left behind: $(Test-Path (Join-Path $data 'proxy-backup.json')); Run entry: $((Get-ItemProperty $runKey -Name Volta -ErrorAction SilentlyContinue).Volta)"

    # Back again: the new installer must move this install over.
    $code = Run "msiexec.exe" @("/i", (Quote $LegacyMsi), "/qn", "/norestart", "REBOOT=ReallySuppress", "/l*v", (Quote (Join-Path $Work "legacy-install2.log"))) 300
    Check ($code -eq 0 -or $code -eq 3010) "the old Volta installs again for the migration test (exit $code)"
} else {
    Write-Host "no legacy MSI given; migration not tested"
}
New-Item -ItemType Directory -Force -Path $data | Out-Null
Set-Content -Path (Join-Path $data "user-marker.txt") -Value "подписки пользователя" -Encoding utf8

# ---------------------------------------------------------------- 2. silent install

ProxyBack "the old MSI Volta"
Step "2. silent install"

$code = Run $Setup @("--silent", "--dir", (Quote $dir), "--log", (Quote $setupLog))
Check ($code -eq 0) "silent install (exit $code)"
foreach ($f in @("Volta.exe", "Uninstall Volta.exe", "install.manifest", "app", "runtime", "app\resources\xray.exe")) {
    Check (Test-Path (Join-Path $dir $f)) "installed: $f"
}
$k = Get-ItemProperty $uninstallKey -ErrorAction SilentlyContinue
Check ($null -ne $k) "Apps & Features entry (HKCU)"
if ($k) {
    Check ($k.DisplayName -eq "Volta") "DisplayName = $($k.DisplayName)"
    Check ($k.Publisher -eq "Volta") "Publisher = $($k.Publisher)"
    Check ($k.DisplayVersion -match '^\d+\.\d+\.\d+') "DisplayVersion = $($k.DisplayVersion)"
    Check ($k.DisplayIcon -like "*$exe*") "DisplayIcon = $($k.DisplayIcon)"
    Check ($k.EstimatedSize -gt 50000) "EstimatedSize = $($k.EstimatedSize) KB"
    Check ($k.InstallLocation -eq $dir) "InstallLocation = $($k.InstallLocation)"
    Check ($k.UninstallString -like "*Uninstall Volta.exe*--uninstall*") "UninstallString = $($k.UninstallString)"
    Check ($k.QuietUninstallString -like "*--uninstall --silent*") "QuietUninstallString = $($k.QuietUninstallString)"
}
Check ((LinkTarget $startLink) -eq $exe) "Start menu shortcut -> $(LinkTarget $startLink)"
Check ((LinkTarget $desktopLink) -eq $exe) "desktop shortcut -> $(LinkTarget $desktopLink)"
$cmd = (Get-ItemProperty "HKCU:\Software\Classes\volta\shell\open\command" -ErrorAction SilentlyContinue).'(default)'
Check ($cmd -like "*$exe*%1*") "volta:// opens Volta ($cmd)"
Check (Test-Path (Join-Path $data "user-marker.txt")) "user data kept through the install"
if ($LegacyMsi) {
    Check (-not (Test-Path (Join-Path $legacyDir "Volta.exe"))) "the old MSI install is removed ($legacyDir)"
    $msiEntries = Get-ChildItem "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue |
        Get-ItemProperty | Where-Object { $_.DisplayName -eq "Volta" -and $_.PSChildName -ne "Volta" }
    Check ($null -eq $msiEntries) "no second Volta in Apps & Features"
}

# ---------------------------------------------------------------- 3. self-test from the installed folder

Step "3. self-test"

$selfLog = Join-Path $Work "self-test.log"
$screens = Join-Path $Shots "app"
$code = Run $exe @("--self-test", (Quote $selfLog), "--shots", (Quote $screens), "--proxy") 300
if (Test-Path $selfLog) { Get-Content $selfLog -Encoding utf8 }
Check ($code -eq 0) "self-test of the installed app (exit $code)"
Copy-Item $selfLog (Join-Path $Shots "self-test.log") -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 4. smoke test

ProxyBack "the self-test"
Step "4. smoke test"

function StartVolta() {
    $p = Start-Process -FilePath $exe -PassThru
    $script:shownBefore = if (Test-Path $appLog) { (Select-String -Path $appLog -Pattern "window shown").Count } else { 0 }
    $okWindow = WaitFor { $p.HasExited -or ((FindWin $p "Volta") -ne [IntPtr]::Zero) } 90
    if ($p.HasExited) { Fail "Volta.exe ended at start (exit $($p.ExitCode))"; return $p }
    Check $okWindow "Volta's window appears (pid $($p.Id))"
    $drawn = WaitFor { (Test-Path $appLog) -and ((Select-String -Path $appLog -Pattern "window shown").Count -gt $script:shownBefore) } 60
    Check $drawn "the window drew its first frames (volta.log: window shown)"
    return $p
}

$volta = StartVolta
Start-Sleep -Seconds 3
Shot "real-app-installed"
$hwnd = FindWin $volta "Volta"
$voltaJava = if ($hwnd -ne [IntPtr]::Zero) { [W]::PidOf($hwnd) } else { $volta.Id }
Write-Host "Volta's window $hwnd belongs to pid $voltaJava (Volta.exe started as pid $($volta.Id))"

# Something else in front, then a second start.
$np = Start-Process notepad.exe -PassThru
Start-Sleep -Seconds 2
$marks = (Select-String -Path $appLog -Pattern "another start asked").Count
$second = Start-Process -FilePath $exe -PassThru
Check ($second.WaitForExit(15000)) "a second start ends by itself"
Check ($second.ExitCode -eq 0) "… with exit code 0 (handed over)"
$acked = WaitFor { (Select-String -Path $appLog -Pattern "another start asked").Count -gt $marks } 10
Check $acked "the running Volta took the second start's message (ack path)"
Start-Sleep -Seconds 2
$fg = [W]::ForegroundPid()
if ($fg -eq $voltaJava) { Ok "Volta's window got the focus" } else { Write-Host "::warning title=Focus::the foreground window belongs to pid $fg, not Volta ($voltaJava); CI desktops often refuse focus changes" }
Select-String -Path $appLog -Pattern "instance:" | Select-Object -Last 4 | ForEach-Object { Write-Host "   $($_.Line)" }
Stop-Process -Id $np.Id -Force -ErrorAction SilentlyContinue

# Hidden to the tray (the window's ✕), then a second start brings it back.
$null = [W]::PostMessage($hwnd, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)   # WM_CLOSE, as the window's ✕
$hidden = WaitFor { -not [W]::IsWindowVisible($hwnd) } 10
Check $hidden "✕ hides the window to the tray"
Check (-not $volta.HasExited) "… and Volta keeps running"
$second = Start-Process -FilePath $exe -PassThru
$null = $second.WaitForExit(15000)
$back = WaitFor { [W]::IsWindowVisible($hwnd) } 10
Check $back "a second start brings the window back from the tray"
Start-Sleep -Seconds 1
Shot "real-app-back-from-tray"

# A hung Volta: frozen, it can't answer; the next start says so instead of nothing.
$javaProc = Get-Process -Id $voltaJava
[W]::NtSuspendProcess($javaProc.Handle) | Out-Null
$third = Start-Process -FilePath $exe -PassThru
$dialog = WaitFor { ((FindWin $third "Volta уже запущена") -ne [IntPtr]::Zero) -or ((FindWin $third "Volta is already running") -ne [IntPtr]::Zero) } 25
Check $dialog "a hung Volta: the next start shows «Volta уже запущена, но не отвечает» ($(TreeTitles $third))"
Start-Sleep -Seconds 1
Shot "real-hung-dialog"
StopTree $third
[W]::NtResumeProcess($javaProc.Handle) | Out-Null
Start-Sleep -Seconds 2

# --quit: out the way «Выход» goes.
$q = Start-Process -FilePath $exe -ArgumentList "--quit" -PassThru
$null = $q.WaitForExit(15000)
Check ($volta.WaitForExit(30000)) "Volta.exe --quit ends the running Volta"
Start-Sleep -Seconds 1
$left = Get-Process | Where-Object { try { $_.Path -and $_.Path.StartsWith($dir) } catch { $false } }
Check ($null -eq $left) "nothing left running from the install folder ($($left.Name -join ', '))"
Check (-not (Test-Path (Join-Path $data "proxy-backup.json"))) "no proxy left switched on (no proxy-backup.json)"
Check (-not (Get-ChildItem (Join-Path $data "logs") -Filter "crash-*.log" -ErrorAction SilentlyContinue)) "no crash logs"

# ---------------------------------------------------------------- 5. update over a running Volta

ProxyBack "the smoke test"
Step "5. update"

$volta = StartVolta
$jsonBefore = Get-Item (Join-Path $data "volta.json") -ErrorAction SilentlyContinue
Check ($null -ne $jsonBefore) "volta.json exists before the update ($($jsonBefore.Length) bytes)"
$code = Run $Setup @("--silent", "--update", "--launch", "--log", (Quote $setupLog))
Check ($code -eq 0) "silent update over a running Volta (exit $code)"
Check ($volta.HasExited) "the old Volta was closed for the update"
$relaunched = WaitFor { $null -ne (Get-Process -Name Volta -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }) } 30
Check $relaunched "the updated Volta was started again (--launch)"
Check (Test-Path (Join-Path $data "user-marker.txt")) "user data survives the update"
$jsonAfter = Get-Item (Join-Path $data "volta.json") -ErrorAction SilentlyContinue
Check (($null -ne $jsonAfter) -and $jsonAfter.Length -gt 10) "volta.json survives the update"
Start-Sleep -Seconds 8
$q = Start-Process -FilePath $exe -ArgumentList "--quit" -PassThru
$null = $q.WaitForExit(15000)
$gone = WaitFor { $null -eq (Get-Process -Name Volta -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }) } 30
Check $gone "the updated Volta quits on --quit"

# The installer and uninstaller windows over an existing install.
$ui = Start-Process -FilePath $Setup -ArgumentList @("--lang", "ru", "--log", (Quote (Join-Path $Work "ui.log"))) -PassThru
Start-Sleep -Seconds 6
Shot "real-installer-update-ru"
Stop-Process -Id $ui.Id -Force -ErrorAction SilentlyContinue
$ui = Start-Process -FilePath (Join-Path $dir "Uninstall Volta.exe") -ArgumentList @("--uninstall", "--lang", "en", "--log", (Quote (Join-Path $Work "ui.log"))) -PassThru
Start-Sleep -Seconds 6
Check ([W]::Find($ui.Id, "Uninstall Volta") -ne [IntPtr]::Zero) "the uninstaller window opens ($([W]::Titles($ui.Id)))"
Shot "real-uninstaller-en"
Stop-Process -Id $ui.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

Copy-Item $appLog (Join-Path $Shots "volta.log") -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 6. uninstall, keeping the data

ProxyBack "the update"
Step "6. uninstall"

function Cleaned($what) {
    Check (-not (Test-Path $uninstallKey)) "${what}: Apps & Features entry removed"
    Check (-not (Test-Path $startLink)) "${what}: Start menu shortcut removed"
    Check (-not (Test-Path $desktopLink)) "${what}: desktop shortcut removed"
    Check (-not (Test-Path "HKCU:\Software\Classes\volta")) "${what}: volta:// removed"
    Check ($null -eq (Get-ItemProperty $runKey -Name Volta -ErrorAction SilentlyContinue)) "${what}: autostart removed"
    $gone = WaitFor { -not (Test-Path $dir) } 15
    Check $gone "${what}: install folder removed ($(if (Test-Path $dir) { (Get-ChildItem -Recurse $dir | Select-Object -First 5 -ExpandProperty FullName) -join ', ' }))"
}

$quiet = (Get-ItemProperty $uninstallKey).QuietUninstallString
Write-Host "QuietUninstallString: $quiet"
$m = [regex]::Match($quiet, '^"([^"]+)"\s*(.*)$')
$code = Run $m.Groups[1].Value @($m.Groups[2].Value.Split(" ") + @("--log", (Quote $setupLog)))
Check ($code -eq 0) "uninstall keeping data (exit $code)"
Cleaned "uninstall"
Check (Test-Path (Join-Path $data "user-marker.txt")) "uninstall keeps %APPDATA%\Volta (marker)"
Check (Test-Path (Join-Path $data "volta.json")) "uninstall keeps volta.json"

# ---------------------------------------------------------------- 7. install again, then uninstall with the data

Step "7. install again, uninstall with the data"

$code = Run $Setup @("--silent", "--dir", (Quote $dir), "--autostart", "--no-desktop-shortcut", "--log", (Quote $setupLog))
Check ($code -eq 0) "install again (exit $code)"
$run = (Get-ItemProperty $runKey -Name Volta -ErrorAction SilentlyContinue).Volta
Check ($run -eq "`"$exe`" --autostart") "autostart entry: $run"
Check (-not (Test-Path $desktopLink)) "no desktop shortcut when not asked"
Check (Test-Path (Join-Path $data "user-marker.txt")) "the data from before is found again"
$code = Run (Join-Path $dir "Uninstall Volta.exe") @("--uninstall", "--silent", "--remove-data", "--log", (Quote $setupLog))
Check ($code -eq 0) "uninstall with data removal (exit $code)"
Cleaned "uninstall --remove-data"
Check (-not (Test-Path $data)) "%APPDATA%\Volta removed when asked"

# ---------------------------------------------------------------- logs

ProxyBack "the uninstallers"
Step "logs"

Write-Host "---- setup.log"
if (Test-Path $setupLog) { Get-Content $setupLog -Encoding utf8 }
Copy-Item $setupLog (Join-Path $Shots "setup.log") -ErrorAction SilentlyContinue
if ($failed -gt 0) { Write-Host "$failed check(s) failed"; exit 1 }
Write-Host "ALL INSTALLER CHECKS PASSED"
exit 0
