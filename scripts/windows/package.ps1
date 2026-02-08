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

Write-Host "Building application JAR and copying dependencies..."
& $Mvnw -DskipTests clean package `
    dependency:copy-dependencies `
    -DincludeScope=runtime `
    -DoutputDirectory=target\dependency

$TargetDir = Join-Path $RepoRoot "target"
$MainJar = Get-ChildItem -Path $TargetDir -Filter "*.jar" | Where-Object {
    $_.Name -notmatch "sources|javadoc"
} | Select-Object -First 1

if (!$MainJar) {
    throw "Main JAR not found in $TargetDir"
}

$DependencyDir = Join-Path $RepoRoot "target\dependency"
if (!(Test-Path $DependencyDir)) {
    throw "Dependency directory not found at $DependencyDir"
}

$InputDir = Join-Path $RepoRoot "target\jpackage-input"
New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
Copy-Item -Force $MainJar.FullName -Destination $InputDir
Copy-Item -Force (Join-Path $DependencyDir "*.jar") -Destination $InputDir

$JavaFxLibs = Get-ChildItem -Path $DependencyDir -Filter "javafx-*.jar"
if (!$JavaFxLibs) {
    throw "JavaFX jars not found in $DependencyDir"
}
$JavaFxModulePath = ($JavaFxLibs | ForEach-Object { $_.FullName }) -join ";"

$IconPath = Join-Path $RepoRoot "icon.ico"
if (!(Test-Path $IconPath)) {
    throw "Icon not found at $IconPath. Create icon.ico in the repo root."
}

$OutDir = Join-Path $RepoRoot "target\installer"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "Packaging $Type installer..."
& $JPackage `
    --type $Type `
    --input $InputDir `
    --main-jar $MainJar.Name `
    --main-class "com.efsavage.picknick.Picknick" `
    --module-path $JavaFxModulePath `
    --add-modules "javafx.controls,javafx.fxml" `
    --name "Picknick" `
    --vendor "Eric F. Savage" `
    --icon $IconPath `
    --dest $OutDir `
    --win-menu `
    --win-shortcut

Write-Host "Done. Installer output: $OutDir"
