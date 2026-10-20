$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$keyDirectory = Join-Path $projectRoot 'secrets'
$privatePath = Join-Path $keyDirectory 'private.pem'
$publicPath = Join-Path $keyDirectory 'public.pem'

if ((Test-Path -LiteralPath $privatePath) -or (Test-Path -LiteralPath $publicPath)) {
    throw 'A JWT key file already exists. Existing keys will not be overwritten. Keep a matching private.pem/public.pem pair in secrets or configure JWT_PRIVATE_KEY and JWT_PUBLIC_KEY.'
}

New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
$rsa = [System.Security.Cryptography.RSA]::Create(3072)
try {
    # Spring expects PKCS#8 private keys and X.509 SubjectPublicKeyInfo public keys.
    $privatePem = $rsa.ExportPkcs8PrivateKeyPem()
    $publicPem = $rsa.ExportSubjectPublicKeyInfoPem()
    [System.IO.File]::WriteAllText($privatePath, $privatePem)
    [System.IO.File]::WriteAllText($publicPath, $publicPem)
    Write-Output 'Created local JWT keys in secrets/. These files are ignored by Git. Run the application from the project root.'
} finally {
    $rsa.Dispose()
}
