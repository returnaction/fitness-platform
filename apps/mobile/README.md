# Fitness App Mobile

Android Flutter client for the Java/Spring Identity Service.

## Local Android Emulator

Start Java Identity on the host computer at `http://localhost:8080`. Android
Emulator reaches this host address as `http://10.0.2.2:8080`, which is the
default API URL.

```powershell
C:\development\flutter\bin\flutter.bat run
```

Use another environment address only through a build-time setting:

```powershell
C:\development\flutter\bin\flutter.bat run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

HTTP is intentionally enabled for the first internal Android version. It must
only be used in a trusted network because it does not protect credentials or
tokens.

## Checks

```powershell
C:\development\flutter\bin\flutter.bat analyze
C:\development\flutter\bin\flutter.bat test
```
