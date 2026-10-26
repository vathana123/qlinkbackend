$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

# Spring Boot imports the optional project-root .env; OS variables override it.
# Google ADC reads the process environment, not Spring properties. Import only
# this credential path (never the credential contents) for the dev launcher.
if (-not $env:GOOGLE_APPLICATION_CREDENTIALS) {
    $envFile = Join-Path $projectRoot '.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match '^\s*GOOGLE_APPLICATION_CREDENTIALS\s*=\s*(.+?)\s*$') {
                $env:GOOGLE_APPLICATION_CREDENTIALS = $Matches[1].Trim('"', "'")
            }
        }
    }
}

Push-Location $projectRoot
try {
    & .\gradlew.bat bootRun --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Spring Boot exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
