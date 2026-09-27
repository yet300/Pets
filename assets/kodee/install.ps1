param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$PetId = "kodee"
$SourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PetDir = Join-Path $env:USERPROFILE ".codex/pets/$PetId"

if ((Test-Path $PetDir) -and -not $Force) {
    throw "Already exists: $PetDir. Rerun with -Force to replace pet.json and spritesheet.webp."
}

New-Item -ItemType Directory -Path $PetDir -Force | Out-Null
Copy-Item (Join-Path $SourceDir "pet.json") (Join-Path $PetDir "pet.json") -Force
Copy-Item (Join-Path $SourceDir "spritesheet.webp") (Join-Path $PetDir "spritesheet.webp") -Force

Write-Output "Installed Kodee to $PetDir"
Write-Output "In Codex, open Settings > Pets > Refresh, then select Kodee."
