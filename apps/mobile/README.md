# Fitness App Mobile

Android Flutter client for the Java/Spring auth API through Gateway Service.

## Backend prerequisites

Run commands below from the repository root unless stated otherwise.
Prepare PostgreSQL outside Docker using
[database provisioning](../../docs/database-provisioning.md),
[JWT keys](../../docs/local-jwt-keys.md), and the
[Gateway setup](../../services/gateway-service/README.md). Registration needs
Accounts and Coins as well as Identity. Gateway needs the configured Identity
JWKS and mTLS CheckSession endpoint.

Each service needs its ignored `services/<service>/.env` and the certificates
referenced by it. A new Git worktree does not inherit ignored files: copy the
approved local configuration and referenced secrets from the existing checkout,
preserving relative paths. Do not overwrite existing configuration or commit
secrets. Switching branches inside the same checkout normally keeps these files.

Build the four services:

```powershell
.\mvnw.cmd -pl services/accounts-service,services/coins-service,services/identity-service,services/gateway-service -am package
```

Run each command in a separate terminal, from the repository root so that
relative configuration and certificate paths resolve correctly:

```powershell
java -jar services/accounts-service/target/accounts-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/coins-service/target/coins-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Service startup can apply Liquibase migrations to the configured databases;
use the agreed local test databases. Check Gateway management on the host at
`http://127.0.0.1:8089/actuator/health`; the app uses API port 8088 only.

## Local Android Emulator

Run Flutter commands from `apps/mobile`. Start an Android virtual device in
Android Studio Device Manager and wait for its unlocked home screen.
Use `flutter devices` to select its ID when several devices are connected.

Start Gateway and its documented Java-service dependencies on the host computer.
The Gateway API template address is `http://127.0.0.1:8088`. Android Emulator
reaches it as `http://10.0.2.2:8088`, which is the default API URL. The mobile
app never calls Identity directly and has no fallback address.

```powershell
C:\development\flutter\bin\flutter.bat run
```

Use another environment address only through a build-time setting:

```powershell
C:\development\flutter\bin\flutter.bat run --dart-define=API_BASE_URL=http://10.0.2.2:8088
```

## Physical phone over USB

Ask the owner to connect and unlock the phone and approve USB debugging before
device tests. The device must appear as `device`, not `unauthorized` or `offline`.
For the local SDK layout used by this project:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$deviceId = '<device ID from the previous command>'
& $adb -s $deviceId reverse tcp:8088 tcp:8088
C:\development\flutter\bin\flutter.bat run -d $deviceId --dart-define=API_BASE_URL=http://127.0.0.1:8088
```

This maps the phone's loopback port to Gateway on the computer. Keep Identity
and Gateway bound to host loopback; no LAN publication is needed. Repeat the
reverse command after reconnecting USB if the mapping has disappeared.
The URL is a build-time setting: an emulator APK with `10.0.2.2` is not the
USB-phone build. Rebuild with the appropriate `--dart-define` when changing it.

## Failure behavior

Refresh runs once when restoring a stored session. Only confirmed
`503 identity_unavailable` keeps the pair and offers `Try again` or local logout.
Network errors, 502/504 and invalid refresh responses clear the pair and require
sign-in; the old one-use refresh token is not retried. Stopping Identity's HTTP
upstream normally gives Gateway 502, so it does not verify the 503 UI scenario.

Explicit logout clears local tokens even if the remote request fails. A secure
storage deletion failure shows a separate local-cleanup action and does not
claim successful logout. Registration retries keep the same idempotency key
while the submitted email and password remain unchanged in the open form.

HTTP is intentionally enabled for the first internal Android version. It must
only be used in a trusted network because it does not protect credentials or
tokens.

## Checks

```powershell
C:\development\flutter\bin\flutter.bat analyze
C:\development\flutter\bin\flutter.bat test
C:\development\flutter\bin\dart.bat format --output=none --set-exit-if-changed lib test
C:\development\flutter\bin\flutter.bat build apk --debug
```

The 48 automated tests cover HTTP contracts with a substituted transport,
repository races, session failure policy, storage failures and UI transitions.
They do not prove real-device failure injection. See
[Spec 008 verification](../../specs/008-mobile-flutter-auth/verification.md) for
actual phone results and outstanding emulator checks. The observed Pixel_7
attempt remained RUNNING_LOCKED with a black screen; its successful APK install
is not an application smoke-test pass. Build and emulator startup should run
sequentially on a memory-constrained host.
