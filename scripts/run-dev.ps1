$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

# Spring Boot imports the optional project-root .env; OS variables override it.

Push-Location $projectRoot
try {
    & .\gradlew.bat bootRun --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Spring Boot exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
