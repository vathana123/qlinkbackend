$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

# Spring Boot imports the optional project-root .env; OS variables override it.
# Google ADC reads the process environment, not Spring properties. Import only
# the Firebase startup settings, never the service-account contents.
$envFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*(GOOGLE_APPLICATION_CREDENTIALS|FIREBASE_PROJECT_ID|PUSH_ENABLED)\s*=\s*(.*?)\s*$') {
            $settingName = $Matches[1]
            $settingValue = $Matches[2].Trim('"', "'")
            if (-not [Environment]::GetEnvironmentVariable($settingName, 'Process')) {
                [Environment]::SetEnvironmentVariable($settingName, $settingValue, 'Process')
            }
        }
    }
}

if ($env:PUSH_ENABLED -eq 'true') {
    if (-not $env:FIREBASE_PROJECT_ID) {
        throw 'Set FIREBASE_PROJECT_ID in the ignored .env file before enabling push.'
    }
    if (-not $env:GOOGLE_APPLICATION_CREDENTIALS) {
        throw 'Set GOOGLE_APPLICATION_CREDENTIALS in the ignored .env file before enabling push.'
    }
    $credentialPath = $env:GOOGLE_APPLICATION_CREDENTIALS
    if (-not [IO.Path]::IsPathRooted($credentialPath)) {
        $credentialPath = Join-Path $projectRoot $credentialPath
    }
    if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
        throw 'Firebase service-account file is missing. Restore the file referenced by GOOGLE_APPLICATION_CREDENTIALS; no credentials were read.'
    }
    $env:GOOGLE_APPLICATION_CREDENTIALS = [IO.Path]::GetFullPath($credentialPath)
}

Push-Location $projectRoot
try {
    & .\gradlew.bat bootRun --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Spring Boot exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
