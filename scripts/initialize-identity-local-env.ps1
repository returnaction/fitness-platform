# Создаёт локальный файл настроек Identity из безопасного шаблона.
# Скрипт не перезаписывает существующий .env и не генерирует пароль за пользователя.
$ErrorActionPreference = 'Stop'

$serviceDirectory = Join-Path $PSScriptRoot '..\services\identity-service'
$templatePath = Join-Path $serviceDirectory '.env.example'
$environmentPath = Join-Path $serviceDirectory '.env'

if (Test-Path -LiteralPath $environmentPath) {
    Write-Output "Локальный файл уже существует: $environmentPath"
    exit 0
}

Copy-Item -LiteralPath $templatePath -Destination $environmentPath
Write-Output "Создан $environmentPath"
Write-Output 'Укажи в нём настоящий DATABASE_PASSWORD после создания роли PostgreSQL.'
