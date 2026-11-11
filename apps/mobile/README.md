# Fitness App Mobile

Android Flutter client for the Java/Spring auth API through Gateway Service.

## Local Android Emulator

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

HTTP is intentionally enabled for the first internal Android version. It must
only be used in a trusted network because it does not protect credentials or
tokens.

## Checks

```powershell
C:\development\flutter\bin\flutter.bat analyze
C:\development\flutter\bin\flutter.bat test
```
