# Runs the mod's in-game self-test (dev.SelfTest) in a hidden, muted game window at low
# priority, waits until the game closes itself and prints the results and new screenshots.
# Usage: selftest.ps1 [-Width 1280] [-Height 720] [-Lang ru_ru] [-TimeoutMinutes 25]
param([int]$Width = 1280, [int]$Height = 720, [string]$Lang = 'ru_ru', [int]$TimeoutMinutes = 25)

$root = Split-Path $PSScriptRoot
$proj = "$root\mod"
$run = "$proj\run"
New-Item -ItemType Directory -Force $run | Out-Null

# Muted, in the chosen language; the rest of options.txt is left as the game wrote it.
$options = "$run\options.txt"
$lines = if (Test-Path $options) { @(Get-Content $options | Where-Object { $_ -notmatch '^(lang|soundCategory_master|onboardAccessibility|tutorialStep|skipMultiplayerWarning):' }) } else { @() }
$lines += "lang:$Lang", 'soundCategory_master:0.0', 'onboardAccessibility:false', 'tutorialStep:none', 'skipMultiplayerWarning:true'
[IO.File]::WriteAllLines($options, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))

$shots = "$run\screenshots"
$before = if (Test-Path $shots) { @(Get-ChildItem $shots -Filter 'rw-*.png' | ForEach-Object Name) } else { @() }
Set-Content "$run\rw-selftest.flag" 'self-test' -Encoding ascii

$env:GRADLE_USER_HOME = 'D:\Tools\gradle-home'
$proxyArgs = ''
$inet = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
if ($inet.ProxyEnable -eq 1 -and $inet.ProxyServer -match '^([^:;=]+):(\d+)$') {
	$proxyArgs = "-Dhttps.proxyHost=$($Matches[1]) -Dhttps.proxyPort=$($Matches[2]) -Dhttp.proxyHost=$($Matches[1]) -Dhttp.proxyPort=$($Matches[2])"
}
$console = "$run\selftest-console.log"
$cmd = "`"`"$proj\gradlew.bat`" -p `"$proj`" runClient --console=plain $proxyArgs `"--args=--width $Width --height $Height`" > `"$console`" 2>&1`""
$start = Get-Date
$p = Start-Process cmd.exe -ArgumentList '/c', $cmd -WindowStyle Hidden -PassThru
$p.PriorityClass = 'BelowNormal'
$deadline = $start.AddMinutes($TimeoutMinutes)
while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
	Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" |
		Where-Object { $_.CommandLine -match [regex]::Escape($proj) -or $_.CommandLine -match 'GradleDaemon' } |
		ForEach-Object { try { (Get-Process -Id $_.ProcessId -ErrorAction Stop).PriorityClass = 'BelowNormal' } catch { } }
	Start-Sleep -Seconds 3
}
if (-not $p.HasExited) {
	"TIMEOUT after $TimeoutMinutes min"
	Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match [regex]::Escape($run) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
	Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
}
Remove-Item "$run\rw-selftest.flag" -ErrorAction SilentlyContinue
"=== self-test  ($([int]((Get-Date) - $start).TotalSeconds) s)"

$log = "$run\logs\latest.log"
$lines = if ((Test-Path $log) -and (Get-Item $log).LastWriteTime -gt $start) { Select-String -Path $log -Pattern '\[RW-SELFTEST\]' | ForEach-Object { $_.Line -replace '^.*\[RW-SELFTEST\] ', '' } } else { @() }
if ($lines) { $lines } else {
	"No self-test output. Last console lines:"
	Get-Content $console -Tail 30 -ErrorAction SilentlyContinue
}
if (Test-Path $log) {
	Select-String -Path $log -Pattern 'Exception|ERROR|Mixin apply|Caused by' | Select-Object -First 15 | ForEach-Object { $_.Line }
}
if (Test-Path $shots) {
	$new = Get-ChildItem $shots -Filter 'rw-*.png' | Where-Object { $before -notcontains $_.Name } | Sort-Object LastWriteTime
	"screenshots: $($new.Count) new"
	$new | ForEach-Object { "  $($_.FullName)" }
}

