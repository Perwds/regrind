# Builds regrind.jar. Needs JDK 25 or newer on PATH; there are no other dependencies.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "out"
New-Item -ItemType Directory -Force $out | Out-Null
$sources = Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 25 -Xlint:none -d $out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& jar --create --file (Join-Path $root "regrind.jar") --main-class regrind.Main -C $out .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Built regrind.jar"
