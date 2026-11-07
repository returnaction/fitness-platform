# Локальный step-ca

Этот Compose предназначен только для разработки. CA доступен лишь на
`127.0.0.1:9000`; ключи и база CA сохраняются в Docker volume. Не используйте
эту CA для production и не публикуйте порт CA в интернет.

```powershell
docker compose -f platform/step-ca/compose.yaml up -d
```

При первом старте образ инициализирует CA и создаёт пароль provisioner внутри
volume. Доступ к Docker и volume означает доступ к секретам CA. Не удаляйте
volume командой `docker compose down -v`: вместе с ним исчезнет CA и придётся
переиздать сертификаты. Для выпуска сертификатов и обновления настроек сервисов
ещё требуется проверенный процесс; включать gRPC до этого не нужно.

## Зачем обновлять сертификаты

Accounts и Coins подтверждают свои имена отдельными серверными сертификатами; Identity подтверждает
свою service identity клиентским сертификатом с URI SAN
`spiffe://fitness.local/identity-service`. Сертификаты имеют ограниченный срок;
после окончания срока mTLS-соединение не установится. Тестовые сертификаты,
выпущенные вручную, по умолчанию короткоживущие. Проверять срок нужно для
каждой конкретной пары, а не полагаться на дату из чата.

## Проверка локальной CA и текущих сертификатов

Команды ниже выполняются из корня репозитория в PowerShell. Они показывают
открытые сведения сертификата, но не закрытый ключ:

```powershell
docker compose -f platform/step-ca/compose.yaml ps
docker compose -f platform/step-ca/compose.yaml exec -T step-ca step ca health --ca-url https://localhost:9000 --root /home/step/certs/root_ca.crt
docker compose -f platform/step-ca/compose.yaml exec -T step-ca step certificate inspect /tmp/fitness-accounts.crt --short
docker compose -f platform/step-ca/compose.yaml exec -T step-ca step certificate inspect /tmp/fitness-identity.crt --short
docker compose -f platform/step-ca/compose.yaml exec -T step-ca step certificate inspect /tmp/fitness-coins.crt --short
```

Файлы в `/tmp` контейнера временные и могут пропасть при его пересоздании.
Локальный сертификат Coins и его ключ для Java находятся в игнорируемых Git
файлах `secrets/coins-server.crt` и `secrets/coins-server-pkcs8.key`.
Для соединения с `localhost` серверный сертификат должен содержать этот DNS SAN;
при использовании другого имени нужен сертификат с соответствующим SAN.
Папка `secrets/` локального проекта игнорируется Git и Docker build context;
доступ к ней должен быть ограничен пользователем компьютера. Не копируйте
туда ключи CA и не отправляйте ключи сервисов в чат, Git или логи.

## Обновление и перевыпуск

Пока действующий сертификат и соответствующий ему закрытый ключ доступны,
`step ca renew` позволяет запросить новый сертификат с тем же ключом. По
документации Smallstep операция должна выполняться до окончания срока; для
истёкшего сертификата понадобится новый выпуск с авторизацией provisioner.
При утечке ключа или необходимости его сменить требуется перевыпуск с новым
ключом, а не простое продление.

Практическая автоматизация продления для этого проекта **ещё не готова**:
нужно безопасно хранить provisioner credentials и проверить выпуск,
копирование и обновление обеих пар. Для Java gRPC локально проверен формат
незашифрованного PEM PKCS#8: исходный `EC PRIVATE KEY` нужно преобразовать
в отдельный файл командой `openssl pkcs8 -topk8 -nocrypt`. Такой файл содержит
незашифрованный ключ, поэтому доступ к папке необходимо ограничить.
Не заменяйте файлы вручную до проверки процесса и не включайте gRPC с
непроверенными сертификатами. После обновления файлов оба приложения сейчас
нужно перезапустить: их TLS-контексты создаются при запуске и не перечитывают
файлы автоматически. Это относится также к сертификату Coins. На сервере потребуется отдельная автоматизация и
мониторинг срока действия до развёртывания.

Справка Smallstep: [запуск step-ca в Docker](https://smallstep.com/docs/tutorials/docker-tls-certificate-authority/),
[обновление сертификата](https://smallstep.com/docs/step-cli/reference/ca/renew/),
[выпуск сертификата](https://smallstep.com/docs/step-cli/reference/ca/certificate/).
