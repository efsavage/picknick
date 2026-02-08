Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

param(
    [ValidateSet("msi", "exe")]
    [string]$Type = "msi"
)

$RepoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Mvnw = Join-Path $RepoRoot "mvnw.cmd"

if (!(Test-Path $Mvnw)) {
    throw "mvnw.cmd not found at $Mvnw"
}

if (!(Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage not found. Install JDK 17+ and ensure it is on PATH."
}

Write-Host "Building custom runtime image..."
& $Mvnw -DskipTests clean javafx:jlink

$AppImage = Join-Path $RepoRoot "target\app"
if (!(Test-Path $AppImage)) {
    throw "Expected app image not found at $AppImage"
}

$OutDir = Join-Path $RepoRoot "target\installer"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "Packaging $Type installer..."
& jpackage `
    --type $Type `
    --app-image $AppImage `
    --name "Picknick" `
    --vendor "Eric F. Savage" `
    --dest $OutDir `
    --win-menu `
    --win-shortcut

Write-Host "Done. Installer output: $OutDir"
