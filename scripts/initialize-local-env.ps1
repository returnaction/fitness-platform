[CmdletBinding()]
param([string]$OpenSslPath)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$secrets = Join-Path $root 'secrets'
$services = @(Get-ChildItem -LiteralPath (Join-Path $root 'services') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName '.env.example') -PathType Leaf } |
    Sort-Object Name)
$certificates = @{
    'accounts-server' = 'DNS:localhost,IP:127.0.0.1'
    'coins-server' = 'DNS:localhost,IP:127.0.0.1'
    'identity-server' = 'DNS:localhost,IP:127.0.0.1'
    'identity-client' = 'URI:spiffe://fitness.local/identity-service'
    'gateway-client' = 'URI:spiffe://fitness.local/gateway-service'
}
$tlsFiles = @('ca.crt')
foreach ($name in $certificates.Keys) {
    $tlsFiles += "$name.crt", "$name-pkcs8.key"
}
$jwtFiles = 'identity-jwt-private.pem', 'identity-jwt-public.pem'

function Test-MaterialSet([string[]]$Names) {
    $present = @($Names | Where-Object { Test-Path -LiteralPath (Join-Path $secrets $_) -PathType Leaf })
    if ($present.Count -eq 0) { return $false }
    if ($present.Count -ne $Names.Count) {
        throw "Incomplete key or certificate set in $secrets. Restore the missing files before retrying."
    }
    return $true
}

function Invoke-OpenSsl {
    & $OpenSslPath @args
    if ($LASTEXITCODE -ne 0) { throw "OpenSSL failed (exit code $LASTEXITCODE)." }
}

if ($services.Count -eq 0) {
    throw 'No service .env.example files found.'
}

$hasTls = Test-MaterialSet $tlsFiles
$hasJwt = Test-MaterialSet $jwtFiles
if (-not $hasTls -or -not $hasJwt) {
    if (-not $OpenSslPath) {
        $postgresOpenSsl = Join-Path $env:ProgramFiles 'PostgreSQL/17/bin/openssl.exe'
        if (Test-Path -LiteralPath $postgresOpenSsl -PathType Leaf) {
            $OpenSslPath = $postgresOpenSsl
        } else {
            $OpenSslPath = (Get-Command openssl -CommandType Application -ErrorAction SilentlyContinue |
                Select-Object -First 1).Source
        }
    }
    if (-not $OpenSslPath) {
        throw 'OpenSSL is required. Pass -OpenSslPath with the full path to openssl.exe.'
    }

    New-Item -ItemType Directory -Path $secrets -Force | Out-Null
    $stage = Join-Path $secrets ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $stage | Out-Null
    Push-Location -LiteralPath $stage
    try {
        $publish = @()
        if (-not $hasTls) {
            '[req]' | Set-Content -LiteralPath request.cnf -Encoding ascii
            Invoke-OpenSsl req -config request.cnf -new -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -sha256 -days 365 `
                -subj '/CN=Fitness Local gRPC CA' -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' `
                -addext 'keyUsage=critical,keyCertSign,cRLSign' -keyout ca.key -out ca.crt

            foreach ($name in ($certificates.Keys | Sort-Object)) {
                $usage = 'serverAuth'
                $purpose = 'sslserver'
                if ($name.EndsWith('-client')) {
                    $usage = 'clientAuth'
                    $purpose = 'sslclient'
                }
                @(
                    'basicConstraints=critical,CA:FALSE'
                    'keyUsage=critical,digitalSignature'
                    "extendedKeyUsage=$usage"
                    "subjectAltName=$($certificates[$name])"
                ) | Set-Content -LiteralPath "$name.ext" -Encoding ascii
                Invoke-OpenSsl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$name-pkcs8.key"
                Invoke-OpenSsl req -config request.cnf -new -key "$name-pkcs8.key" -subj "/CN=$name" -out "$name.csr"
                Invoke-OpenSsl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial `
                    -days 30 -sha256 -extfile "$name.ext" -out "$name.crt"
                Invoke-OpenSsl verify -CAfile ca.crt -purpose $purpose "$name.crt"
            }
            $publish += $tlsFiles
        }
        if (-not $hasJwt) {
            Invoke-OpenSsl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out $jwtFiles[0]
            Invoke-OpenSsl pkey -in $jwtFiles[0] -pubout -out $jwtFiles[1]
            $publish += $jwtFiles
        }

        $published = @()
        try {
            foreach ($name in $publish) {
                $destination = Join-Path $secrets $name
                [IO.File]::Move((Join-Path $stage $name), $destination)
                $published += $destination
            }
        } catch {
            foreach ($path in $published) { Remove-Item -LiteralPath $path }
            throw
        }
    } finally {
        Pop-Location
        Get-ChildItem -LiteralPath $stage -File -Force | Remove-Item
        Remove-Item -LiteralPath $stage
    }
}

foreach ($service in $services) {
    $envFile = Join-Path $service.FullName '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        [IO.File]::Copy((Join-Path $service.FullName '.env.example'), $envFile)
    }
    Write-Output "Ready: services/$($service.Name)/.env"
}

Write-Output 'Set DATABASE_PASSWORD in the Accounts, Coins and Identity .env files.'
Write-Output 'Build and start the four services from the repository root. See apps/mobile/README.md.'
