# Builds Arnis as the native library the mod loads (arnis_native.dll) and copies it
# into the mod's resources. Runs hidden at low priority; output goes to native\build.log.
# Usage: build-native.ps1 [-Debug]
param([switch]$Debug)

$root = Split-Path $PSScriptRoot
$crate = "$root\native\arnis"
$log = "$root\native\build.log"
$env:RUSTUP_HOME = 'D:\Tools\rust\rustup'
$env:CARGO_HOME = 'D:\Tools\rust\cargo'
$env:CARGO_TARGET_DIR = "$root\native\target"
$profile = if ($Debug) { '' } else { '--release' }
$cargo = 'D:\Tools\rust\cargo\bin\cargo.exe'

$cmd = "`"`"$cargo`" build $profile --lib --no-default-features --features embedded --manifest-path `"$crate\Cargo.toml`" > `"$log`" 2>&1`""
$p = Start-Process cmd.exe -ArgumentList '/c', $cmd -WindowStyle Hidden -PassThru
$p.PriorityClass = 'BelowNormal'
# rustc and the linker are children of cargo; keep the whole build at low priority.
while (-not $p.HasExited) {
	Get-Process rustc, cargo, link -ErrorAction SilentlyContinue | ForEach-Object { try { $_.PriorityClass = 'BelowNormal' } catch { } }
	Start-Sleep -Seconds 5
}

$out = if ($Debug) { 'debug' } else { 'release' }
$dll = "$root\native\target\$out\arnis_native.dll"
if ($p.ExitCode -eq 0 -and (Test-Path $dll)) {
	$dest = "$root\mod\src\main\resources\natives\windows-x86_64"
	New-Item -ItemType Directory -Force $dest | Out-Null
	Copy-Item $dll "$dest\arnis_native.dll" -Force
	"OK  $([math]::Round((Get-Item $dll).Length / 1MB, 1)) MB -> $dest"
} else {
	"FAILED (exit $($p.ExitCode)), last lines of $log :"
	Get-Content $log -Tail 40
}
