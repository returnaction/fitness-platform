param([switch]$CheckOnly)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$openssl = 'C:\Program Files\PostgreSQL\17\bin\openssl.exe'
$container = 'step-ca-step-ca-1'
$names = @('identity-client', 'identity-server', 'accounts-server', 'coins-server')
$secrets = Join-Path $root 'secrets'
if (!(Test-Path -LiteralPath $openssl)) { throw "OpenSSL не найден: $openssl" }
foreach ($name in $names) {
    foreach ($suffix in @('.crt', '-pkcs8.key')) {
        if (!(Test-Path -LiteralPath (Join-Path $secrets "$name$suffix"))) {
            throw "Не найден файл $name$suffix"
        }
    }
}
$remote = (docker exec $container sh -c 'umask 077; mktemp -d /tmp/fitness-renew-XXXXXXXX').Trim()
if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^/tmp/fitness-renew-[A-Za-z0-9]+$') {
    throw 'Не удалось создать временную папку в step-ca. Проверьте, что контейнер запущен.'
}
$stage = Join-Path $secrets ('renew-stage-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    foreach ($name in $names) {
        $cert = Join-Path $secrets "$name.crt"
        $key = Join-Path $secrets "$name-pkcs8.key"
        $new = Join-Path $stage "$name.crt"
        docker cp $cert "${container}:${remote}/$name.crt" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Не удалось скопировать сертификат $name" }
        docker cp $key "${container}:${remote}/$name.key" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Не удалось скопировать ключ $name" }
        docker exec $container step ca renew --mtls=false --ca-url https://localhost:9000 --root /home/step/certs/root_ca.crt --out "$remote/$name-new.crt" "$remote/$name.crt" "$remote/$name.key"
        if ($LASTEXITCODE -ne 0) { throw "Не удалось обновить сертификат $name" }
        docker cp "${container}:${remote}/$name-new.crt" $new | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Не удалось получить сертификат $name" }
        & $openssl verify -CAfile (Join-Path $secrets 'ca.crt') -untrusted $new $new
        if ($LASTEXITCODE -ne 0) { throw "Не пройдена проверка цепочки $name" }
        $certKey = (& $openssl x509 -in $new -pubkey -noout | Out-String).Trim()
        $privateKey = (& $openssl pkey -in $key -pubout | Out-String).Trim()
        if (!$certKey -or $certKey -ne $privateKey) { throw "Ключ не соответствует сертификату $name" }
        $san = & $openssl x509 -in $new -noout -ext subjectAltName | Out-String
        if (($name -eq 'identity-client' -and $san -notmatch 'URI:spiffe://fitness\.local/identity-service') -or
            ($name -ne 'identity-client' -and $san -notmatch 'DNS:localhost')) {
            throw "Сертификат $name не содержит нужного имени"
        }
        & $openssl x509 -in $new -noout -enddate
    }
    if ($CheckOnly) {
        Write-Output 'Проверка выпуска успешна. Файлы сервисов не изменены.'
    } else {
        $backup = Join-Path $secrets ('cert-backup-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $backup | Out-Null
        foreach ($name in $names) { Copy-Item -LiteralPath (Join-Path $secrets "$name.crt") -Destination $backup }
        try {
            foreach ($name in $names) { Copy-Item -LiteralPath (Join-Path $stage "$name.crt") -Destination (Join-Path $secrets "$name.crt") -Force }
        } catch {
            foreach ($name in $names) { Copy-Item -LiteralPath (Join-Path $backup "$name.crt") -Destination (Join-Path $secrets "$name.crt") -Force }
            throw
        }
        Write-Output "Сертификаты обновлены. Резервная копия: $backup"
        Write-Output 'Перезапустите Identity, Accounts и Coins, чтобы они загрузили сертификаты.'
    }
} finally {
    docker exec $container rm -rf -- $remote | Out-Null
    foreach ($name in $names) {
        $file = Join-Path $stage "$name.crt"
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    if ((Test-Path -LiteralPath $stage) -and @(Get-ChildItem -LiteralPath $stage -Force).Count -eq 0) {
        Remove-Item -LiteralPath $stage
    }
}
