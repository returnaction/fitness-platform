# Локальные ключи подписи JWT

Identity подписывает access token отдельным ключом ES256.
Для локальной среды нужна пара EC P-256: закрытый ключ в
PKCS#8 PEM и открытый ключ в PEM. Файлы в `secrets/` игнорируются Git.

Команды выполняются из корня репозитория в PowerShell. Укажи путь к `openssl`,
если он отличается на твоём компьютере. Команды не перезаписывают существующие
ключи; их замена требует отдельной процедуры ротации.

```powershell
$openssl = 'C:\Program Files\PostgreSQL\17\bin\openssl.exe'
New-Item -ItemType Directory -Force secrets | Out-Null
if ((Test-Path secrets/identity-jwt-private.pem) -or (Test-Path secrets/identity-jwt-public.pem)) {
  throw 'JWT keys already exist; do not overwrite them.'
}
& $openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out secrets/identity-jwt-private.pem
if ($LASTEXITCODE -ne 0) { throw 'Could not generate private key.' }
& $openssl pkey -in secrets/identity-jwt-private.pem -pubout -out secrets/identity-jwt-public.pem
if ($LASTEXITCODE -ne 0) { throw 'Could not generate public key.' }
```

Закрытый ключ не отправляют в чат, Git, образы или логи. Доступ к `secrets/`
нужно ограничить. Для проверки Identity загружает оба ключа и не запускает
подпись, если они не совпадают. Открытый ключ доступен
по `/.well-known/jwks.json`.

JWT обязателен: до запуска Identity подготовь ключи и укажи их пути в
`services/identity-service/.env`. Без корректной пары ключей сервис не запустится. Значения
`issuer`, `audience` и `kid` из `.env.example` предназначены для локальной
разработки; на сервере задаются собственные значения и безопасное хранение
ключей. Ротация JWT-ключей и сохранение старых открытых ключей пока не
автоматизированы. Серверный метод `CheckSession` реализован, но проверяющий
Gateway и его подключение пока отсутствуют; не считать локальную регистрацию
готовой к публичному развёртыванию.
