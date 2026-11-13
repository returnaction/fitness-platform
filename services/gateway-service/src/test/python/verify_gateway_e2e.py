"""Specs 012/014: real auth/profile APIs, mTLS and disposable service databases.

Run after packaging Gateway, Identity, Accounts and Coins. Requires Docker and OpenSSL.
Local .env files and user databases are never loaded.
"""

import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[5]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("registration_e2e", ROOT / "scripts/verify-registration-e2e.py")
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)
SECRETS = set()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(port, path, body=None, bearer=None, key=None, method="POST", extra_headers=None, raw_body=None):
    client_request_id = str(uuid.uuid4())
    headers = {"X-Request-Id": client_request_id}
    data = None if method == "GET" else b""
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if bearer is not None:
        headers["Authorization"] = "Bearer " + bearer
    if key:
        headers["Idempotency-Key"] = key
    if raw_body is not None:
        data = raw_body
    headers.update(extra_headers or {})
    outgoing = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(outgoing, timeout=8)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read()
        value = json.loads(raw) if raw else {}
        if path.startswith("/api/") or path == "/unknown":
            identifier = response.headers.get("X-Request-Id")
            require(str(uuid.UUID(identifier)) == identifier, "Gateway must generate a UUID")
            require(identifier != headers["X-Request-Id"], "Gateway reused client request ID")
            if "error" in value:
                require(value["error"]["requestId"] == identifier, "HTTP/JSON request IDs differ")
                require(set(value["error"]) == {"code", "message", "details", "requestId"}, "Unexpected error shape")
                require(isinstance(value["error"]["details"], list), "Error details must be a list")
            if path.startswith("/api/v1/accounts/me"):
                cache_directives = {item.strip().lower() for item in response.headers.get("Cache-Control", "").split(",")}
                require("no-store" in cache_directives, "Profile response lacks no-store")
                require(response.headers.get("Content-Type", "").startswith("application/json"), "Profile response is not JSON")
        collect_tokens(value)
        return response.status, value


def collect_tokens(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in ("accessToken", "refreshToken") and isinstance(item, str):
                SECRETS.add(item)
            else:
                collect_tokens(item)


def verify_profiles(port, token, expired, second_token, database_sql):
    path = "/api/v1/accounts/me"
    fields = {"nickname", "firstName", "lastName", "bio", "dateOfBirth", "timezone", "visibility", "version"}

    def read(bearer=token):
        status, profile = request(port, path, bearer=bearer, method="GET")
        require(status == 200 and set(profile) == fields, "GET profile shape/status mismatch")
        return profile

    def error(status_code, code, method="PUT", body=None, bearer=token, **kwargs):
        status, result = request(port, path, body, bearer, method=method, **kwargs)
        require(status == status_code and result.get("error", {}).get("code") == code,
                f"Expected profile error {status_code}/{code}, got {status}/{result.get('error', {}).get('code')}")
        require(not any(part in json.dumps(result) for part in ("SQL", "Exception", "user_profiles")), "Internal error leaked")
        return result["error"]["details"]

    original, other = read(), read(second_token)
    require(original["version"] == 1, "Provisioned profile version mismatch")
    require(all(original[name] is None for name in ("firstName", "lastName", "bio", "dateOfBirth")), "Initial nullable fields missing")
    private_bio = "private-profile-" + uuid.uuid4().hex
    SECRETS.add(private_bio)
    form = dict(original, nickname="E2e_" + uuid.uuid4().hex[:16], firstName="  Анна  ", lastName="О’Коннор",
                bio="  " + private_bio + "  ", dateOfBirth="2000-02-29", timezone="Europe/Moscow", visibility="PUBLIC")
    status, saved = request(port, path, form, token, method="PUT", extra_headers={"X-User-Id": str(uuid.uuid4())})
    expected = dict(form, firstName="Анна", bio=private_bio, version=2)
    require(status == 200 and saved == expected, "PUT did not return normalized committed profile")
    require(read() == saved and read(second_token) == other, "GET after PUT or owner isolation failed")

    error(409, "profile_version_conflict", body=form)
    error(409, "nickname_already_taken", body=dict(saved, nickname=other["nickname"].upper(), bio="must-not-save"))
    error(400, "invalid_request", body=dict(saved, unexpected=True))
    missing = dict(saved)
    del missing["firstName"]
    error(400, "invalid_request", body=missing)
    error(400, "invalid_request", body=dict(saved, version="2"))
    error(400, "invalid_request", raw_body=b"{", extra_headers={"Content-Type": "application/json"})
    error(415, "unsupported_media_type", raw_body=b"text", extra_headers={"Content-Type": "text/plain"})
    details = error(422, "validation_error", body=dict(saved, nickname="!", timezone="invalid/timezone"))
    require({item["field"] for item in details} >= {"nickname", "timezone"}, "Validation errors not aggregated")
    require(all(set(item) == {"field", "code"} for item in details), "Validation detail shape mismatch")
    for bearer in (None, expired, "malformed"):
        for method in ("GET", "PUT"):
            error(401, "invalid_access_token", method=method, body=saved if method == "PUT" else None, bearer=bearer)
    require(read() == saved, "Rejected request changed profile")

    clear = dict(saved, firstName="  ", lastName=None, bio="", dateOfBirth=None)
    status, cleared = request(port, path, clear, token, method="PUT")
    require(status == 200 and cleared == dict(clear, firstName=None, bio=None, version=3), "Clear fields failed")
    require(read() == cleared, "Cleared fields not persisted")
    require(request(port, "/api/v1/auth/logout-all", bearer=second_token)[0] == 204, "Second owner logout failed")
    for method in ("GET", "PUT"):
        error(401, "invalid_access_token", method=method, body=cleared if method == "PUT" else None, bearer=second_token)

    # Only the disposable Accounts database is made unavailable; Identity remains usable.
    database_sql("postgres", "ALTER DATABASE spec012_test_accounts ALLOW_CONNECTIONS false")
    try:
        database_sql("postgres", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'spec012_test_accounts'")
        for method in ("GET", "PUT"):
            error(503, "profile_unavailable", method=method, body=cleared if method == "PUT" else None)
    finally:
        database_sql("postgres", "ALTER DATABASE spec012_test_accounts ALLOW_CONNECTIONS true")
    require(read() == cleared, "Database outage changed profile or recovery failed")

    payload = token.split(".")[1]
    user_id = str(uuid.UUID(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["sub"]))
    database_sql("spec012_test_accounts", f"DELETE FROM user_profiles WHERE user_id = '{user_id}'")
    for method in ("GET", "PUT"):
        error(503, "profile_unavailable", method=method, body=cleared if method == "PUT" else None)
    count = database_sql("spec012_test_accounts", f"SELECT count(*) FROM user_profiles WHERE user_id = '{user_id}'")
    require(count == "0", "Missing profile was recreated")
    print("OK: profile GET/PUT/GET, normalization/null, owners, 400/401/409/415/422/503, DB outage/recovery, no-store", flush=True)


def await_gateway(port, process):
    end = time.monotonic() + 60
    while time.monotonic() < end:
        require(process.poll() is None, "Gateway stopped before readiness")
        try:
            if request(port, "/actuator/health/readiness", method="GET")[0] == 200:
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.2)
    raise AssertionError("Gateway did not become ready")


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def expired_token(token, directory):
    header, payload, _ = token.split(".")
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    claims.update(iat=int(time.time()) - 120, exp=int(time.time()) - 60)
    unsigned = header + "." + b64(json.dumps(claims).encode())
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(directory / "jwt.key")],
                               input=unsigned.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
    # OpenSSL emits DER INTEGER r/s; JWS ES256 uses two fixed-width 32-byte integers.
    require(signature[0] == 0x30 and signature[2] == 0x02, "Unexpected ECDSA DER signature")
    size = signature[3]
    r = int.from_bytes(signature[4:4 + size], "big").to_bytes(32, "big")
    offset = 4 + size
    require(signature[offset] == 0x02, "Unexpected ECDSA signature s")
    s = int.from_bytes(signature[offset + 2:offset + 2 + signature[offset + 1]], "big").to_bytes(32, "big")
    return unsigned + "." + b64(r + s)


def main():
    container = None
    with tempfile.TemporaryDirectory(prefix="gateway-spec012-e2e-") as temporary:
        directory = Path(temporary)
        try:
            support.certificates(directory)
            container = support.run("docker", "run", "--rm", "-d", "--name", "gateway-spec012-" + uuid.uuid4().hex[:12],
                                    "-e", "POSTGRES_PASSWORD=temporary-test-password", "-p", "127.0.0.1::5432", "postgres:17-alpine")
            for _ in range(60):
                probe = subprocess.run(["docker", "exec", container, "pg_isready", "-U", "postgres"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if probe.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise AssertionError("Disposable PostgreSQL did not start")
            database_port = support.run("docker", "port", container, "5432/tcp").splitlines()[0].rsplit(":", 1)[1]
            names = ("identity", "accounts", "coins", "identity_grpc", "accounts_grpc", "coins_grpc", "gateway", "management", "identity_management")
            ports = {}
            for name in names:
                port = support.free_port()
                while port in ports.values():
                    port = support.free_port()
                ports[name] = port

            def environment(service):
                # Do not inherit Spring overrides or JDBC settings from the developer's shell.
                system_keys = {"PATH", "JAVA_HOME", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE",
                               "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA"}
                env = {name: value for name, value in os.environ.items() if name.upper() in system_keys}
                env.update(support.test_environment(service + "-service"))
                env.update(SPRING_PROFILES_ACTIVE="test", SERVER_PORT=str(ports[service]), SERVER_ADDRESS="127.0.0.1")
                if service != "gateway":
                    database = "spec012_test_" + service
                    support.run("docker", "exec", container, "createdb", "-U", "postgres", database)
                    env.update(DATABASE_URL=f"jdbc:postgresql://127.0.0.1:{database_port}/{database}",
                               DATABASE_USERNAME="postgres", DATABASE_PASSWORD="temporary-test-password")
                return env

            for service in ("accounts", "coins"):
                env = environment(service)
                if service == "accounts":
                    # Bound this fault-injection test; production pool/SQL timeout policy is T007a.
                    env.update(SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT="1000",
                               SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT="500")
                prefix = service.upper() + "_GRPC"
                env.update({prefix + "_PORT": str(ports[service + "_grpc"]),
                            prefix + "_CERTIFICATE": str(directory / "server.crt"),
                            prefix + "_PRIVATE_KEY": str(directory / "server.key"),
                            prefix + "_TRUST_CERTIFICATE": str(directory / "ca.crt")})
                support.await_health(ports[service], support.start(service + "-service", env, directory))

            identity = environment("identity")
            identity.update(IDENTITY_MANAGEMENT_PORT=str(ports["identity_management"]),
                            IDENTITY_JWT_PRIVATE_KEY=str(directory / "jwt.key"), IDENTITY_JWT_PUBLIC_KEY=str(directory / "jwt.pub"),
                            IDENTITY_JWT_KEY_ID="spec012-first", IDENTITY_SESSION_GRPC_PORT=str(ports["identity_grpc"]),
                            IDENTITY_SESSION_GRPC_CERTIFICATE=str(directory / "server.crt"),
                            IDENTITY_SESSION_GRPC_PRIVATE_KEY=str(directory / "server.key"),
                            IDENTITY_SESSION_GRPC_TRUST_CERTIFICATE=str(directory / "ca.crt"),
                            IDENTITY_SESSION_GRPC_ALLOWED_CALLER="spiffe://fitness.local/gateway-service",
                            IDENTITY_REGISTRATION_WORKER_POLL_MS="100")
            for service in ("accounts", "coins"):
                prefix = "IDENTITY_" + service.upper() + "_GRPC"
                identity.update({prefix + "_PORT": str(ports[service + "_grpc"]), prefix + "_CERTIFICATE": str(directory / "client.crt"),
                                 prefix + "_PRIVATE_KEY": str(directory / "client.key"), prefix + "_TRUST_CERTIFICATE": str(directory / "ca.crt")})
            identity_process = support.start("identity-service", identity, directory)
            support.await_health(ports["identity_management"], identity_process)

            gateway = environment("gateway")
            gateway.update(MANAGEMENT_PORT=str(ports["management"]), IDENTITY_HTTP_BASE_URL=f"http://127.0.0.1:{ports['identity']}",
                           ACCOUNT_HTTP_BASE_URL=f"http://127.0.0.1:{ports['accounts']}",
                           GATEWAY_JWKS_URL=f"http://127.0.0.1:{ports['identity']}/.well-known/jwks.json",
                           GATEWAY_JWT_ISSUER=identity["IDENTITY_JWT_ISSUER"], GATEWAY_JWT_AUDIENCE=identity["IDENTITY_JWT_AUDIENCE"],
                           IDENTITY_SESSION_PORT=str(ports["identity_grpc"]), GATEWAY_CERTIFICATE=str(directory / "gateway.crt"),
                           GATEWAY_PRIVATE_KEY=str(directory / "gateway.key"), GATEWAY_TRUST_CERTIFICATE=str(directory / "ca.crt"))
            gateway_process = support.start("gateway-service", gateway, directory)
            await_gateway(ports["management"], gateway_process)
            print("OK: four real services, disposable PostgreSQL, JWKS and mTLS ready", flush=True)

            port = ports["gateway"]
            email, password, key = "spec012-" + uuid.uuid4().hex + "@example.com", "Test-" + uuid.uuid4().hex, str(uuid.uuid4())
            SECRETS.update((email, password, key))
            credentials = {"email": email, "password": password}
            for attempt in range(10):
                status, registration = request(port, "/api/v1/auth/register", credentials, key=key)
                if status == 201:
                    break
                require(status in (503, 504), "Unexpected registration result")
                time.sleep(0.5)
            require(status == 201, "Registration did not finish through Gateway")

            def login():
                status, value = request(port, "/api/v1/auth/login", credentials)
                require(status == 200, "Login failed")
                return value

            active = login()
            expired = expired_token(active["accessToken"], directory)
            SECRETS.add(expired)
            other_credentials = {"email": "profile-" + uuid.uuid4().hex + "@example.com", "password": password}
            other_key = str(uuid.uuid4())
            SECRETS.update((other_credentials["email"], other_key))
            for _ in range(10):
                status, _ = request(port, "/api/v1/auth/register", other_credentials, key=other_key)
                if status == 201:
                    break
                require(status in (503, 504), "Unexpected second registration result")
                time.sleep(0.5)
            require(status == 201, "Second profile was not provisioned")
            status, other_session = request(port, "/api/v1/auth/login", other_credentials)
            require(status == 200, "Second owner login failed")

            def database_sql(database, sql):
                return support.run("docker", "exec", container, "psql", "-U", "postgres", "-d", database,
                                   "-v", "ON_ERROR_STOP=1", "-At", "-c", sql)

            verify_profiles(port, active["accessToken"], expired, other_session["accessToken"], database_sql)
            require(request(port, "/api/v1/auth/logout-all", bearer=expired)[0] == 401, "Expired JWT was accepted")
            for bearer in (None, active["accessToken"], expired, "malformed"):
                require(request(port, "/api/v1/auth/register", credentials, bearer, key)[0] == 201, "Public register rejected bearer")
                require(request(port, "/api/v1/auth/login", credentials, bearer)[0] == 200, "Public login rejected bearer")
                session = login()
                status, rotated = request(port, "/api/v1/auth/refresh", {"refreshToken": session["refreshToken"]}, bearer)
                require(status == 200, "Public refresh rejected bearer")
                require(request(port, "/api/v1/auth/logout", {"refreshToken": rotated["refreshToken"]}, bearer)[0] == 204,
                        "Public logout rejected bearer")
                require(request(port, "/api/v1/auth/logout-all", bearer=rotated["accessToken"])[0] == 401,
                        "Revoked session passed CheckSession")
            print("OK: all public operations bypass absent/valid/expired/malformed bearer; logout revokes session", flush=True)

            session = login()
            _, rotated = request(port, "/api/v1/auth/refresh", {"refreshToken": session["refreshToken"]})
            require(request(port, "/api/v1/auth/refresh", {"refreshToken": session["refreshToken"]})[0] == 401, "Refresh replay accepted")
            require(request(port, "/api/v1/auth/logout-all", bearer=rotated["accessToken"])[0] == 401, "Replay did not revoke session")
            session, second = login(), login()
            require(request(port, "/api/v1/auth/logout-all")[0] == 401, "Unprotected logout-all")
            require(request(port, "/api/v1/auth/logout-all", bearer=session["accessToken"])[0] == 204, "Valid logout-all failed")
            require(request(port, "/api/v1/auth/logout-all", bearer=second["accessToken"])[0] == 401, "logout-all left session active")
            require(request(port, "/api/v1/auth/refresh", {"refreshToken": second["refreshToken"]})[0] == 401, "logout-all left refresh active")
            print("OK: refresh rotation/replay and logout-all revoke real database sessions", flush=True)

            old = login()
            stop(identity_process)
            require(request(port, "/api/v1/auth/logout-all", bearer=old["accessToken"])[0] == 503, "Identity outage did not fail closed")
            require(request(ports["management"], "/actuator/health/readiness", method="GET")[0] == 200, "Outage cleared cached keys")
            support.openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", directory / "jwt.key")
            support.openssl("pkey", "-in", directory / "jwt.key", "-pubout", "-out", directory / "jwt.pub")
            identity["IDENTITY_JWT_KEY_ID"] = "spec012-second"
            identity_process = support.start("identity-service", identity, directory)
            support.await_health(ports["identity_management"], identity_process)
            new = login()
            require(request(port, "/api/v1/auth/logout-all", bearer=new["accessToken"])[0] == 401, "Gateway unexpectedly refreshed JWKS")
            stop(gateway_process)
            gateway_process = support.start("gateway-service", gateway, directory)
            await_gateway(ports["management"], gateway_process)
            require(request(port, "/api/v1/auth/logout-all", bearer=old["accessToken"])[0] == 401, "Restart retained removed key")
            require(request(port, "/api/v1/auth/logout-all", bearer=new["accessToken"])[0] == 204, "Restart did not load new key")
            print("OK: Identity outage returns 503; JWKS changes only after Gateway restart", flush=True)

            for service in ("gateway", "identity", "accounts", "coins"):
                log = (directory / (service + "-service.log")).read_text(encoding="utf-8", errors="replace")
                require(not any(value in log for value in SECRETS), "Sensitive value found in " + service + " log")
            print("OK: request IDs match JSON; test secrets absent from all service logs", flush=True)
        finally:
            for process, log in reversed(support.PROCESSES):
                stop(process)
                log.close()
            if container:
                subprocess.run(["docker", "stop", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)


if __name__ == "__main__":
    main()
