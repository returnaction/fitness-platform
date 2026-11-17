#!/usr/bin/env python3
"""Daily reward e2e through real Gateway, Identity, Accounts and Coins."""

import json
import os
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PROCESSES = []
TEST_PASSWORD = "e2e-secret-" + uuid.uuid4().hex


def run(*args):
    return subprocess.run(
        args,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout.strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def openssl(*args):
    run("openssl", *map(str, args))


def certificates(directory):
    ca_key, ca = directory / "ca.key", directory / "ca.crt"
    config = directory / "openssl.cnf"
    config.write_text("[req]\ndistinguished_name=dn\n[dn]\n", encoding="ascii")
    openssl("req", "-config", config, "-x509", "-newkey", "rsa:2048",
            "-nodes", "-keyout", ca_key, "-out", ca, "-days", "1",
            "-subj", "/CN=temporary-test-ca")
    extensions = (
        ("server", "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n"),
        ("client", "subjectAltName=URI:spiffe://fitness.local/identity-service\nextendedKeyUsage=clientAuth\n"),
        ("gateway", "subjectAltName=URI:spiffe://fitness.local/gateway-service\nextendedKeyUsage=clientAuth\n"),
    )
    for name, value in extensions:
        key = directory / (name + ".key")
        csr = directory / (name + ".csr")
        cert = directory / (name + ".crt")
        ext = directory / (name + ".ext")
        ext.write_text(value, encoding="ascii")
        openssl("genpkey", "-algorithm", "RSA", "-pkeyopt",
                "rsa_keygen_bits:2048", "-out", key)
        openssl("req", "-config", config, "-new", "-key", key, "-out", csr,
                "-subj", "/CN=" + name)
        openssl("x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key,
                "-CAcreateserial", "-out", cert, "-days", "1",
                "-extfile", ext)
    jwt_key, jwt_public = directory / "jwt.key", directory / "jwt.pub"
    jwt_ec = directory / "jwt-ec.key"
    openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", jwt_ec)
    openssl("pkcs8", "-topk8", "-nocrypt", "-in", jwt_ec, "-out", jwt_key)
    openssl("pkey", "-in", jwt_key, "-pubout", "-out", jwt_public)


def service_jar(service):
    target = ROOT / "services" / service / "target"
    jars = [
        path for path in target.glob(service + "-*.jar")
        if not path.name.endswith(".original")
    ]
    if not jars:
        raise RuntimeError("Build the services first: " + service)
    return max(jars, key=lambda path: path.stat().st_mtime)


def start(service, environment, directory):
    jar = service_jar(service)
    staged = directory / jar.name
    if not staged.exists():
        shutil.copy2(jar, staged)
    log = (directory / (service + ".log")).open("a", encoding="utf-8")
    process = subprocess.Popen(
        ["java", "-jar", str(staged)],
        cwd=ROOT,
        env=environment,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    PROCESSES.append((process, log))
    return process


def await_health(port, process, log_path=None):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            if log_path and log_path.exists():
                print("===== " + log_path.name + " =====", flush=True)
                print(log_path.read_text(encoding="utf-8", errors="replace")[-12000:], flush=True)
            raise RuntimeError("Service exited during startup: " + str(process.returncode))
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/actuator/health", timeout=2
            ) as response:
                if json.load(response).get("status") == "UP":
                    return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError("Service did not become healthy on port " + str(port))


def request_json(url, method="GET", body=None, token=None, idempotency_key=None):
    headers = {}
    data = b""
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if idempotency_key is not None:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, dict(response.headers), json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), json.load(error)


def sql(container, database, statement):
    return run(
        "docker", "exec", container, "psql", "-U", "postgres",
        "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", statement,
    )


def register(gateway, email):
    key = uuid.uuid4()
    deadline = time.monotonic() + 45
    while True:
        status, _, body = request_json(
            gateway + "/api/v1/auth/register",
            method="POST",
            body={"email": email, "password": TEST_PASSWORD},
            idempotency_key=str(key),
        )
        if status == 201:
            return body
        if status != 503 or time.monotonic() >= deadline:
            raise AssertionError("Registration failed: HTTP " + str(status) + " " + json.dumps(body))
        time.sleep(1)


def main():
    container = None
    lock_holder = None
    with tempfile.TemporaryDirectory(prefix="fitness-daily-reward-e2e-") as temp:
        directory = Path(temp)
        try:
            certificates(directory)
            name = "fitness-daily-reward-" + uuid.uuid4().hex[:12]
            container = run(
                "docker", "run", "--rm", "-d", "--name", name,
                "-e", "POSTGRES_PASSWORD=temporary-test-password",
                "-p", "127.0.0.1::5432", "postgres:17-alpine",
            )
            for _ in range(60):
                probe = subprocess.run(
                    ["docker", "exec", container, "pg_isready", "-U", "postgres"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
                if probe.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("Temporary PostgreSQL did not start")
            database_port = run("docker", "port", container, "5432/tcp").splitlines()[0].rsplit(":", 1)[1]
            for database in ("test_identity", "test_accounts", "test_coins"):
                run("docker", "exec", container, "createdb", "-U", "postgres", database)

            ports = {}
            for service in (
                "identity", "identity_management", "identity_grpc",
                "accounts", "accounts_grpc",
                "coins", "coins_grpc",
                "gateway", "gateway_management",
            ):
                ports[service] = free_port()

            issuer = f"http://localhost:{ports['identity']}"
            audience = "fitness-gateway"

            def base_environment(database):
                env = os.environ.copy()
                env.update({
                    "SPRING_PROFILES_ACTIVE": "e2e",
                    "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{database_port}/{database}",
                    "DATABASE_USERNAME": "postgres",
                    "DATABASE_PASSWORD": "temporary-test-password",
                    "DATABASE_POOL_MIN_IDLE": "2",
                    "DATABASE_POOL_MAX_SIZE": "10",
                })
                return env

            accounts_env = base_environment("test_accounts")
            accounts_env.update({
                "SERVER_PORT": str(ports["accounts"]),
                "ACCOUNTS_GRPC_ADDRESS": "127.0.0.1",
                "ACCOUNTS_GRPC_PORT": str(ports["accounts_grpc"]),
                "ACCOUNTS_GRPC_CERTIFICATE": str(directory / "server.crt"),
                "ACCOUNTS_GRPC_PRIVATE_KEY": str(directory / "server.key"),
                "ACCOUNTS_GRPC_TRUST_CERTIFICATE": str(directory / "ca.crt"),
            })
            accounts = start("accounts-service", accounts_env, directory)
            await_health(ports["accounts"], accounts, directory / "accounts-service.log")

            coins_env = base_environment("test_coins")
            coins_env.update({
                "SERVER_PORT": str(ports["coins"]),
                "COINS_GRPC_ADDRESS": "127.0.0.1",
                "COINS_GRPC_PORT": str(ports["coins_grpc"]),
                "COINS_GRPC_CERTIFICATE": str(directory / "server.crt"),
                "COINS_GRPC_PRIVATE_KEY": str(directory / "server.key"),
                "COINS_GRPC_TRUST_CERTIFICATE": str(directory / "ca.crt"),
            })
            coins = start("coins-service", coins_env, directory)
            await_health(ports["coins"], coins, directory / "coins-service.log")

            identity_env = base_environment("test_identity")
            identity_env.update({
                "SERVER_PORT": str(ports["identity"]),
                "IDENTITY_MANAGEMENT_PORT": str(ports["identity_management"]),
                "IDENTITY_SESSION_GRPC_ADDRESS": "127.0.0.1",
                "IDENTITY_SESSION_GRPC_PORT": str(ports["identity_grpc"]),
                "IDENTITY_SESSION_GRPC_CERTIFICATE": str(directory / "server.crt"),
                "IDENTITY_SESSION_GRPC_PRIVATE_KEY": str(directory / "server.key"),
                "IDENTITY_SESSION_GRPC_TRUST_CERTIFICATE": str(directory / "ca.crt"),
                "IDENTITY_SESSION_GRPC_ALLOWED_CALLER": "spiffe://fitness.local/gateway-service",
                "IDENTITY_JWT_PRIVATE_KEY": str(directory / "jwt.key"),
                "IDENTITY_JWT_PUBLIC_KEY": str(directory / "jwt.pub"),
                "IDENTITY_JWT_KEY_ID": "e2e-key",
                "IDENTITY_JWT_ISSUER": issuer,
                "IDENTITY_JWT_AUDIENCE": audience,
                "IDENTITY_ACCOUNTS_GRPC_HOST": "127.0.0.1",
                "IDENTITY_ACCOUNTS_GRPC_PORT": str(ports["accounts_grpc"]),
                "IDENTITY_ACCOUNTS_GRPC_CERTIFICATE": str(directory / "client.crt"),
                "IDENTITY_ACCOUNTS_GRPC_PRIVATE_KEY": str(directory / "client.key"),
                "IDENTITY_ACCOUNTS_GRPC_TRUST_CERTIFICATE": str(directory / "ca.crt"),
                "IDENTITY_COINS_GRPC_HOST": "127.0.0.1",
                "IDENTITY_COINS_GRPC_PORT": str(ports["coins_grpc"]),
                "IDENTITY_COINS_GRPC_CERTIFICATE": str(directory / "client.crt"),
                "IDENTITY_COINS_GRPC_PRIVATE_KEY": str(directory / "client.key"),
                "IDENTITY_COINS_GRPC_TRUST_CERTIFICATE": str(directory / "ca.crt"),
                "IDENTITY_REGISTRATION_RESPONSE_WAIT_MS": "12000",
                "IDENTITY_REGISTRATION_RESPONSE_POLL_MS": "250",
                "IDENTITY_REGISTRATION_WORKER_POLL_MS": "1000",
                "IDENTITY_REGISTRATION_WORKER_BATCH_SIZE": "20",
                "IDENTITY_REGISTRATION_WORKER_PARALLELISM": "4",
                "IDENTITY_REGISTRATION_WORKER_LEASE_SECONDS": "30",
                "IDENTITY_REGISTRATION_RETRY_INITIAL_SECONDS": "1",
                "IDENTITY_REGISTRATION_RETRY_MAX_SECONDS": "5",
                "IDENTITY_REGISTRATION_RETRY_JITTER_FRACTION": "0",
            })
            identity = start("identity-service", identity_env, directory)
            await_health(ports["identity_management"], identity, directory / "identity-service.log")

            gateway_env = os.environ.copy()
            gateway_env.update({
                "SPRING_PROFILES_ACTIVE": "e2e",
                "SERVER_PORT": str(ports["gateway"]),
                "MANAGEMENT_PORT": str(ports["gateway_management"]),
                "GATEWAY_ADDRESS": "127.0.0.1",
                "IDENTITY_HTTP_BASE_URL": f"http://127.0.0.1:{ports['identity']}",
                "ACCOUNT_HTTP_BASE_URL": f"http://127.0.0.1:{ports['accounts']}",
                "COINS_HTTP_BASE_URL": f"http://127.0.0.1:{ports['coins']}",
                "GATEWAY_JWKS_URL": f"http://127.0.0.1:{ports['identity']}/.well-known/jwks.json",
                "GATEWAY_JWT_ISSUER": issuer,
                "GATEWAY_JWT_AUDIENCE": audience,
                "IDENTITY_SESSION_HOST": "127.0.0.1",
                "IDENTITY_SESSION_PORT": str(ports["identity_grpc"]),
                "GATEWAY_TRUST_CERTIFICATE": str(directory / "ca.crt"),
                "GATEWAY_CERTIFICATE": str(directory / "gateway.crt"),
                "GATEWAY_PRIVATE_KEY": str(directory / "gateway.key"),
                "SESSION_SHUTDOWN_TIMEOUT": "5s",
            })
            gateway_process = start("gateway-service", gateway_env, directory)
            await_health(ports["gateway_management"], gateway_process, directory / "gateway-service.log")

            gateway = f"http://127.0.0.1:{ports['gateway']}"
            email = "reward-e2e-" + uuid.uuid4().hex + "@example.com"
            registered = register(gateway, email)
            token = registered["session"]["accessToken"]
            user = registered["user"]["id"]

            status, headers, body = request_json(
                gateway + "/api/v1/coins/daily-reward/status", token=token
            )
            assert status == 200, status
            assert headers.get("Cache-Control") == "no-store", headers
            assert body["available"] is True and body["amount"] == 10, body
            assert "claimedForDate" not in body and body["nextClaimAt"] is None, body
            assert body["availableBalance"] == 0 and body["lockedBalance"] == 0, body
            print("OK: initial daily-reward status and zero balance", flush=True)

            key = str(uuid.uuid4())
            status, _, first = request_json(
                gateway + "/api/v1/coins/daily-reward/claims",
                method="POST", token=token, idempotency_key=key,
            )
            assert status == 200, status
            assert first["claimedAmount"] == 10, first
            assert first["availableBalance"] == 10 and first["lockedBalance"] == 0, first
            assert first["granted"] is True, first
            assert "claimedForDate" not in first and first["nextClaimAt"] and first["serverTime"], first
            assert (datetime.fromisoformat(first["nextClaimAt"].replace("Z", "+00:00"))
                    - datetime.fromisoformat(first["serverTime"].replace("Z", "+00:00"))) == timedelta(hours=24)
            assert sql(container, "test_coins",
                       f"SELECT available_balance FROM coin_wallets WHERE user_id='{user}'") == "10"
            assert sql(container, "test_coins",
                       f"SELECT count(*) FROM daily_reward_claims WHERE user_id='{user}'") == "1"
            print("OK: first claim credited exactly 10 coins", flush=True)

            status, _, repeated = request_json(
                gateway + "/api/v1/coins/daily-reward/claims",
                method="POST", token=token, idempotency_key=key,
            )
            assert status == 200 and repeated == first, repeated
            assert sql(container, "test_coins",
                       f"SELECT count(*) FROM daily_reward_claims WHERE user_id='{user}'") == "1"
            assert sql(container, "test_coins",
                       f"SELECT available_balance FROM coin_wallets WHERE user_id='{user}'") == "10"
            print("OK: same Idempotency-Key returned the stored snapshot", flush=True)

            status, _, unavailable = request_json(
                gateway + "/api/v1/coins/daily-reward/claims",
                method="POST", token=token, idempotency_key=str(uuid.uuid4()),
            )
            assert status == 200 and unavailable["granted"] is False, unavailable
            assert unavailable["claimedAmount"] == 0, unavailable
            assert unavailable["availableBalance"] == 10, unavailable
            assert sql(container, "test_coins",
                       f"SELECT count(*) FROM daily_reward_claims WHERE user_id='{user}'") == "1"
            print("OK: new key in the unavailable period did not credit coins", flush=True)

            status, _, after = request_json(
                gateway + "/api/v1/coins/daily-reward/status", token=token
            )
            assert status == 200 and after["available"] is False, after
            assert after["availableBalance"] == 10 and after["nextClaimAt"], after
            print("OK: status exposes timer and updated balance", flush=True)

            status, headers, unauthorized = request_json(
                gateway + "/api/v1/coins/daily-reward/status"
            )
            assert status == 401, status
            assert unauthorized["error"]["code"] == "invalid_access_token", unauthorized
            print("OK: daily-reward endpoints require a Bearer token", flush=True)

            for invalid_key in (None, "not-a-uuid"):
                status, headers, invalid = request_json(
                    gateway + "/api/v1/coins/daily-reward/claims",
                    method="POST", token=token, idempotency_key=invalid_key,
                )
                assert status == 400 and invalid["error"]["code"] == "invalid_request"
                assert headers.get("Cache-Control") == "no-store", headers
                assert headers.get("X-Request-Id") == invalid["error"]["requestId"], headers
            print("OK: missing/invalid UUID returns Coins 400 through Gateway with requestId and no-store", flush=True)

            second_email = "reward-lock-" + uuid.uuid4().hex + "@example.com"
            second = register(gateway, second_email)
            second_token = second["session"]["accessToken"]
            second_user = second["user"]["id"]
            lock_holder = subprocess.Popen(
                [
                    "docker", "exec", container, "psql", "-U", "postgres",
                    "-d", "test_coins", "-v", "ON_ERROR_STOP=1", "-c",
                    "BEGIN; SELECT user_id FROM coin_wallets "
                    f"WHERE user_id='{second_user}' FOR UPDATE; SELECT pg_sleep(8); COMMIT;",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            time.sleep(0.5)
            started = time.monotonic()
            status, headers, busy = request_json(
                gateway + "/api/v1/coins/daily-reward/claims",
                method="POST", token=second_token, idempotency_key=str(uuid.uuid4()),
            )
            elapsed = time.monotonic() - started
            assert status == 503, (status, busy)
            assert busy["error"]["code"] == "coins_unavailable", busy
            assert headers.get("Retry-After") == "1", headers
            assert headers.get("Cache-Control") == "no-store", headers
            assert headers.get("X-Request-Id") == busy["error"]["requestId"], headers
            assert elapsed < 5, elapsed
            print("OK: busy wallet returned 503 coins_unavailable with Retry-After", flush=True)

            status, _, isolated = request_json(
                gateway + "/api/v1/coins/daily-reward/status", token=second_token
            )
            assert status == 200 and isolated["availableBalance"] == 0, isolated
            assert isolated["available"] is True, isolated
            print("OK: second user sees only their own wallet", flush=True)
        finally:
            if lock_holder and lock_holder.poll() is None:
                lock_holder.terminate()
                lock_holder.wait(timeout=10)
            for process, log in reversed(PROCESSES):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                log.close()
            if container:
                subprocess.run(
                    ["docker", "stop", container],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )


if __name__ == "__main__":
    main()
