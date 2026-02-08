param(
    [ValidateSet("msi", "exe")]
    [string]$Type = "msi"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Mvnw = Join-Path $RepoRoot "mvnw.cmd"

if (!(Test-Path $Mvnw)) {
    throw "mvnw.cmd not found at $Mvnw"
}

$JPackage = Get-Command jpackage -ErrorAction SilentlyContinue
if (!$JPackage -and $env:JAVA_HOME) {
    $Candidate = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
    if (Test-Path $Candidate) {
        $JPackage = $Candidate
    }
}
if (!$JPackage) {
    throw "jpackage not found. Install JDK 17+ and ensure it is on PATH, or set JAVA_HOME to the JDK."
}

Write-Host "Building custom runtime image..."
& $Mvnw -DskipTests clean javafx:jlink

$AppImage = Join-Path $RepoRoot "target\app"
if (!(Test-Path $AppImage)) {
    throw "Expected app image not found at $AppImage"
}

$IconPath = Join-Path $RepoRoot "icon.ico"
if (!(Test-Path $IconPath)) {
    throw "Icon not found at $IconPath. Create icon.ico in the repo root."
}

$OutDir = Join-Path $RepoRoot "target\installer"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "Packaging $Type installer..."
& $JPackage `
    --type $Type `
    --app-image $AppImage `
    --name "Picknick" `
    --vendor "Eric F. Savage" `
    --icon $IconPath `
    --dest $OutDir `
    --win-menu `
    --win-shortcut

Write-Host "Done. Installer output: $OutDir"
