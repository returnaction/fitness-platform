#!/usr/bin/env python3
"""Проверка регистрации через три процесса и временный PostgreSQL в Docker."""

import argparse
import base64
import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parent.parent
JAVA_HOME = os.environ.get("JAVA_HOME")
JAVA = str(Path(JAVA_HOME) / "bin" / ("java.exe" if os.name == "nt" else "java")) if JAVA_HOME else "java"
PROCESSES = []
TEST_PASSWORD = "e2e-secret-" + uuid.uuid4().hex


class ResponseGate:
    """Прозрачный TCP-прокси; может временно задерживать ответ сервера."""

    def __init__(self, listen_port, target_port):
        self.target_port = target_port
        self.stop_event = threading.Event()
        self.gate = threading.Event()
        self.held = threading.Event()
        self.connections = []
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", listen_port))
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.thread = threading.Thread(target=self._accept, daemon=True)
        self.thread.start()

    def _accept(self):
        while not self.stop_event.is_set():
            try:
                client, _ = self.listener.accept()
                try:
                    server = socket.create_connection(("127.0.0.1", self.target_port), timeout=2)
                except OSError:
                    client.close()
                    continue
                client.settimeout(0.2)
                server.settimeout(0.2)
                self.connections.extend((client, server))
                threading.Thread(target=self._forward, args=(client, server, False), daemon=True).start()
                threading.Thread(target=self._forward, args=(server, client, True), daemon=True).start()
            except socket.timeout:
                continue
            except OSError:
                break

    def _forward(self, source, destination, response):
        try:
            while not self.stop_event.is_set():
                try:
                    data = source.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    break
                if response and self.gate.is_set():
                    self.held.set()
                    while self.gate.is_set() and not self.stop_event.wait(0.05):
                        pass
                if self.stop_event.is_set():
                    break
                destination.sendall(data)
        except OSError:
            pass
        finally:
            for connection in (source, destination):
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def block(self):
        self.held.clear()
        self.gate.set()

    def release(self):
        self.gate.clear()

    def close(self):
        self.stop_event.set()
        self.release()
        self.listener.close()
        for connection in self.connections:
            connection.close()
        self.thread.join(timeout=2)


def run(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def test_environment(service):
    result = {}
    for line in (ROOT / "services" / service / "src" / "test" / "resources" / "test-environment.properties").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    return result


def token_roles(token):
    # Проверка содержимого уже полученного токена; подпись проверяется Java-тестами.
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["roles"]


def openssl(*args):
    run("openssl", *map(str, args))


def certificates(directory):
    ca_key, ca = directory / "ca.key", directory / "ca.crt"
    config = directory / "openssl.cnf"
    config.write_text("[req]\ndistinguished_name=dn\n[dn]\n", encoding="ascii")
    openssl("req", "-config", config, "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-nodes", "-keyout", ca_key, "-out", ca, "-days", "1", "-subj", "/CN=temporary-test-ca")
    for name, extensions in (
        ("server", "subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n"),
        ("client", "subjectAltName=URI:spiffe://fitness.local/identity-service\nextendedKeyUsage=clientAuth\n"),
    ):
        key, csr, cert, ext = (directory / (name + suffix) for suffix in (".key", ".csr", ".crt", ".ext"))
        ext.write_text(extensions, encoding="ascii")
        openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", key)
        openssl("req", "-config", config, "-new", "-key", key, "-out", csr, "-subj", "/CN=" + name)
        openssl("x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key, "-CAcreateserial",
                "-out", cert, "-days", "1", "-extfile", ext)
    jwt_key, jwt_public = directory / "jwt.key", directory / "jwt.pub"
    openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-out", jwt_key)
    openssl("pkey", "-in", jwt_key, "-pubout", "-out", jwt_public)


def start(service, environment, directory):
    jar = ROOT / "services" / service / "target" / (service + "-0.1.0-SNAPSHOT.jar")
    if not jar.is_file():
        raise RuntimeError("Сначала собери сервисы командой mvnw package: " + service)
    log = (directory / (service + ".log")).open("a", encoding="utf-8")
    process = subprocess.Popen([JAVA, "-jar", str(jar)], cwd=ROOT, env=environment, stdout=log,
                               stderr=subprocess.STDOUT)
    PROCESSES.append((process, log))
    return process


def await_health(port, process):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Сервис завершился при запуске, код " + str(process.returncode))
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=2) as response:
                if json.load(response).get("status") == "UP":
                    return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError("Сервис не стал готов вовремя: порт " + str(port))


def register(port, key, email, password=TEST_PASSWORD):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/v1/auth/register",
        data=json.dumps({"email": email, "password": password}).encode(),
        headers={"Content-Type": "application/json", "Idempotency-Key": str(key)}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def login(port, email, password):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/v1/auth/login",
        data=json.dumps({"email": email, "password": password}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def refresh_session(port, token):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/v1/auth/refresh",
        data=json.dumps({"refresh_token": token}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=25) as response:
        return response.status, json.load(response)


def post_auth(port, path, body=None, bearer=None):
    headers = {}
    data = b""
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if bearer is not None:
        headers["Authorization"] = "Bearer " + bearer
    request = urllib.request.Request(f"http://127.0.0.1:{port}/api/v1/auth/{path}",
                                     data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def sql(container, database, statement):
    return run("docker", "exec", container, "psql", "-U", "postgres", "-d", database,
               "-At", "-v", "ON_ERROR_STOP=1", "-c", statement)


def concurrent_register(port, keys, email):
    barrier = threading.Barrier(len(keys), timeout=10)

    def send(key):
        barrier.wait()
        return register(port, key, email)

    with ThreadPoolExecutor(max_workers=len(keys)) as executor:
        return list(executor.map(send, keys))


def assert_single_provisioned_user(container, email):
    user = sql(container, "test_identity", f"SELECT id FROM users WHERE email_canonical='{email}'")
    user = str(uuid.UUID(user))
    assert sql(container, "test_identity", f"SELECT count(*) FROM users WHERE email_canonical='{email}'") == "1"
    assert sql(container, "test_identity", f"SELECT count(*) FROM registration_operations WHERE user_id='{user}'") == "1"
    assert sql(container, "test_accounts", f"SELECT count(*) FROM user_profiles WHERE user_id='{user}'") == "1"
    assert sql(container, "test_coins", f"SELECT count(*) FROM coin_wallets WHERE user_id='{user}'") == "1"
    return user


def lost_response_scenario(container, port, proxy, service):
    email = "e2e-lost-" + uuid.uuid4().hex + "@example.com"
    key = uuid.uuid4()
    table = "user_profiles" if service == "accounts" else "coin_wallets"
    ready_field = "profile_ready" if service == "accounts" else "wallet_ready"
    error_code = service.upper() + "_DEADLINE_EXCEEDED"
    proxy.block()
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(register, port, key, email)
            try:
                deadline = time.monotonic() + 35
                user = None
                while time.monotonic() < deadline:
                    candidate = sql(container, "test_identity", f"SELECT id FROM users WHERE email_canonical='{email}'")
                    if candidate:
                        user = str(uuid.UUID(candidate))
                        committed = sql(container, "test_" + service,
                                        f"SELECT count(*) FROM {table} WHERE user_id='{user}'") == "1"
                        waiting = sql(container, "test_identity", "SELECT EXISTS (SELECT 1 FROM registration_operations "
                                      f"WHERE user_id='{user}' AND status='PENDING' AND NOT {ready_field} "
                                      f"AND last_error_code='{error_code}')") == "t"
                        if committed and waiting and proxy.held.is_set():
                            break
                    time.sleep(0.2)
                else:
                    raise AssertionError("Did not observe committed data and a timed-out " + service + " response")
                assert sql(container, "test_identity", f"SELECT count(*) FROM auth_sessions WHERE user_id='{user}'") == "0"
                if service == "accounts":
                    saved = sql(container, "test_accounts", f"SELECT nickname FROM user_profiles WHERE user_id='{user}'")
                else:
                    saved = sql(container, "test_coins", f"SELECT available_balance FROM coin_wallets WHERE user_id='{user}'")
            finally:
                proxy.release()
            status, body = future.result(timeout=30)
        deadline = time.monotonic() + 45
        while status != 201 and time.monotonic() < deadline:
            assert status == 503, "Unexpected HTTP status " + str(status)
            status, body = register(port, key, email)
        assert status == 201 and body["user"]["id"] == user
        assert assert_single_provisioned_user(container, email) == user
        if service == "accounts":
            assert sql(container, "test_accounts", f"SELECT nickname FROM user_profiles WHERE user_id='{user}'") == saved
        else:
            assert sql(container, "test_coins", f"SELECT available_balance FROM coin_wallets WHERE user_id='{user}'") == saved == "0"
        print("OK: " + service + " committed before a lost response; retry completed without duplicates", flush=True)
    finally:
        proxy.release()


def main():
    if not __debug__:
        raise RuntimeError("Run this test without Python optimization (-O or PYTHONOPTIMIZE)")
    parser = argparse.ArgumentParser(description="Isolated registration end-to-end test")
    parser.add_argument("--local-step-ca", action="store_true", help="Use existing local step-ca certificates read-only")
    options = parser.parse_args()
    container = None
    lock_process = None
    proxies = []
    with tempfile.TemporaryDirectory(prefix="fitness-registration-e2e-") as tmp:
        directory = Path(tmp)
        try:
            certificates(directory)
            cert_directory = ROOT / "secrets" if options.local_step_ca else directory
            if options.local_step_ca:
                for name in ("ca.crt", "identity-client.crt", "identity-client-pkcs8.key",
                             "accounts-server.crt", "accounts-server-pkcs8.key",
                             "coins-server.crt", "coins-server-pkcs8.key"):
                    if not (cert_directory / name).is_file():
                        raise RuntimeError("Missing local step-ca certificate or key: " + name)
            name = "fitness-registration-test-" + uuid.uuid4().hex[:12]
            container = run("docker", "run", "--rm", "-d", "--name", name,
                            "-e", "POSTGRES_PASSWORD=temporary-test-password", "-p", "127.0.0.1::5432",
                            "postgres:17-alpine")
            for attempt in range(60):
                probe = subprocess.run(["docker", "exec", container, "pg_isready", "-U", "postgres"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if probe.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("Временный PostgreSQL не запустился")
            database_port = run("docker", "port", container, "5432/tcp").splitlines()[0].rsplit(":", 1)[1]
            for database in ("test_identity", "test_accounts", "test_coins"):
                run("docker", "exec", container, "createdb", "-U", "postgres", database)
            ports = {}
            for name in ("identity", "accounts", "coins", "accounts_grpc", "coins_grpc",
                         "accounts_grpc_real", "coins_grpc_real", "identity_session_grpc"):
                port = free_port()
                while port in ports.values():
                    port = free_port()
                ports[name] = port
            accounts_proxy = ResponseGate(ports["accounts_grpc"], ports["accounts_grpc_real"])
            proxies.append(accounts_proxy)
            coins_proxy = ResponseGate(ports["coins_grpc"], ports["coins_grpc_real"])
            proxies.append(coins_proxy)

            def environment(service, database, port):
                env = os.environ.copy()
                env.update(test_environment(service))
                env.update({"SPRING_PROFILES_ACTIVE": "e2e", "SERVER_PORT": str(port),
                            "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{database_port}/{database}",
                            "DATABASE_USERNAME": "postgres", "DATABASE_PASSWORD": "temporary-test-password"})
                return env

            identity = environment("identity-service", "test_identity", ports["identity"])
            identity.update({"IDENTITY_JWT_PRIVATE_KEY": str(directory / "jwt.key"),
                             "IDENTITY_JWT_PUBLIC_KEY": str(directory / "jwt.pub"),
                             "IDENTITY_SESSION_GRPC_ADDRESS": "127.0.0.1",
                             "IDENTITY_SESSION_GRPC_PORT": str(ports["identity_session_grpc"]),
                             "IDENTITY_SESSION_GRPC_CERTIFICATE": str(directory / "server.crt"),
                             "IDENTITY_SESSION_GRPC_PRIVATE_KEY": str(directory / "server.key"),
                             "IDENTITY_SESSION_GRPC_TRUST_CERTIFICATE": str(directory / "ca.crt"),
                             "IDENTITY_SESSION_GRPC_ALLOWED_CALLER": "spiffe://fitness.local/test-client",
                             "IDENTITY_ACCOUNTS_GRPC_PORT": str(ports["accounts_grpc"]),
                             "IDENTITY_COINS_GRPC_PORT": str(ports["coins_grpc"]),
                             "IDENTITY_REGISTRATION_WORKER_POLL_MS": "1000",
                             "IDENTITY_REGISTRATION_RATE_CAPACITY": "100",
                             "IDENTITY_REGISTRATION_RETRY_INITIAL_SECONDS": "1"})
            for prefix in ("IDENTITY_ACCOUNTS_GRPC", "IDENTITY_COINS_GRPC"):
                identity[prefix + "_CERTIFICATE"] = str(cert_directory / ("identity-client.crt" if options.local_step_ca else "client.crt"))
                identity[prefix + "_PRIVATE_KEY"] = str(cert_directory / ("identity-client-pkcs8.key" if options.local_step_ca else "client.key"))
                identity[prefix + "_TRUST_CERTIFICATE"] = str(cert_directory / "ca.crt")
            process = start("identity-service", identity, directory)
            await_health(ports["identity"], process)
            key, email = uuid.uuid4(), "e2e-" + uuid.uuid4().hex + "@example.com"
            status, body = register(ports["identity"], key, email)
            if status != 503 or body["error"]["code"] != "registration_unavailable":
                raise AssertionError("При недоступных сервисах ожидался 503")
            assert "session" not in body and "access_token" not in body
            print("OK: unavailable services return 503 without tokens", flush=True)
            assert sql(container, "test_identity", f"SELECT status FROM users WHERE email_canonical='{email}'") == "PENDING"
            login_status, login_body = login(ports["identity"], email, TEST_PASSWORD)
            assert login_status == 503 and login_body["error"]["code"] == "registration_pending"
            assert "access_token" not in login_body and "refresh_token" not in login_body
            wrong_status, wrong_body = login(ports["identity"], email, "wrong-password")
            assert wrong_status == 401 and wrong_body["error"]["code"] == "invalid_credentials"
            for error_body in (body, login_body, wrong_body):
                serialized = json.dumps(error_body)
                for private in (email, str(key), TEST_PASSWORD, "wrong-password", "SELECT ", "Exception"):
                    assert private not in serialized, "Sensitive input or internal detail leaked in an error"
            assert sql(container, "test_identity", "SELECT count(*) FROM auth_sessions") == "0"
            process.kill()
            process.wait(timeout=10)

            for service in ("accounts", "coins"):
                env = environment(service + "-service", "test_" + service, ports[service])
                prefix = service.upper() + "_GRPC"
                env.update({prefix + "_ENABLED": "true", prefix + "_PORT": str(ports[service + "_grpc_real"]),
                            prefix + "_CERTIFICATE": str(cert_directory / (service + "-server.crt" if options.local_step_ca else "server.crt")),
                            prefix + "_PRIVATE_KEY": str(cert_directory / (service + "-server-pkcs8.key" if options.local_step_ca else "server.key")),
                            prefix + "_TRUST_CERTIFICATE": str(cert_directory / "ca.crt")})
                service_process = start(service + "-service", env, directory)
                await_health(ports[service], service_process)
            lock_process = subprocess.Popen(
                ["docker", "exec", "-e", "PGAPPNAME=fitness-e2e-lock", container, "psql", "-U", "postgres",
                 "-d", "test_accounts", "-c", "BEGIN; LOCK TABLE user_profiles IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(120);"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            lock_deadline = time.monotonic() + 15
            while sql(container, "test_accounts", "SELECT EXISTS (SELECT 1 FROM pg_locks "
                      "WHERE relation='user_profiles'::regclass AND mode='AccessExclusiveLock' AND granted)") != "t":
                if lock_process.poll() is not None or time.monotonic() >= lock_deadline:
                    raise AssertionError("Could not acquire the temporary test lock")
                time.sleep(0.1)
            process = start("identity-service", identity, directory)
            await_health(ports["identity"], process)
            lease_deadline = time.monotonic() + 15
            while True:
                active = sql(container, "test_identity", "SELECT EXISTS (SELECT 1 FROM registration_operations o "
                             "JOIN users u ON u.id=o.user_id "
                             f"WHERE u.email_canonical='{email}' AND o.lease_owner IS NOT NULL "
                             "AND o.lease_until > clock_timestamp())")
                blocked = sql(container, "test_accounts", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                              "WHERE datname='test_accounts' AND wait_event_type='Lock' "
                              "AND application_name<>'fitness-e2e-lock')")
                if active == "t" and blocked == "t":
                    break
                if time.monotonic() >= lease_deadline:
                    raise AssertionError("Worker did not hold an active lease")
                time.sleep(0.1)
            process.kill()
            process.wait(timeout=10)
            active_after_crash = sql(container, "test_identity", "SELECT EXISTS (SELECT 1 FROM registration_operations o "
                                     "JOIN users u ON u.id=o.user_id "
                                     f"WHERE u.email_canonical='{email}' AND o.lease_owner IS NOT NULL "
                                     "AND o.lease_until > clock_timestamp())")
            assert active_after_crash == "t", "The lease was released before the crash"
            sql(container, "test_accounts", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                "WHERE application_name='fitness-e2e-lock'")
            lock_process.wait(timeout=10)
            process = start("identity-service", identity, directory)
            await_health(ports["identity"], process)
            deadline = time.monotonic() + 45
            while True:
                status, body = register(ports["identity"], key, email)
                if status == 201:
                    break
                if status != 503 or time.monotonic() >= deadline:
                    raise AssertionError("Повтор регистрации не завершился: HTTP " + str(status))
                time.sleep(1)
            user = str(uuid.UUID(body["user"]["id"]))
            assert body["user"]["status"] == "ACTIVE" and body["session"]["access_token"]
            assert token_roles(body["session"]["access_token"]) == ["USER"]
            assert sql(container, "test_identity", f"SELECT count(*) FROM users WHERE id='{user}' AND status='ACTIVE'") == "1"
            assert sql(container, "test_accounts", f"SELECT count(*) FROM user_profiles WHERE user_id='{user}'") == "1"
            assert sql(container, "test_coins", f"SELECT count(*) FROM coin_wallets WHERE user_id='{user}' AND available_balance=0 AND locked_balance=0") == "1"
            status, repeated = register(ports["identity"], key, email)
            assert status == 201 and repeated["user"]["id"] == user
            assert sql(container, "test_accounts", f"SELECT count(*) FROM user_profiles WHERE user_id='{user}'") == "1"
            assert sql(container, "test_coins", f"SELECT count(*) FROM coin_wallets WHERE user_id='{user}'") == "1"
            assert sql(container, "test_identity", f"SELECT count(*) FROM auth_sessions WHERE user_id='{user}' AND revoked_at IS NULL") == "1"
            assert sql(container, "test_identity", f"SELECT count(*) FROM auth_sessions WHERE user_id='{user}'") == "2"
            direct_email = "e2e-" + uuid.uuid4().hex + "@example.com"
            direct_status, direct = register(ports["identity"], uuid.uuid4(), direct_email)
            assert direct_status == 201 and direct["user"]["status"] == "ACTIVE"
            assert direct["session"]["access_token"] and direct["session"]["refresh_token"]

            same_email = "e2e-same-" + uuid.uuid4().hex + "@example.com"
            same_key = uuid.uuid4()
            results = concurrent_register(ports["identity"], [same_key, same_key], same_email)
            assert all(status in (201, 503) for status, _ in results), [status for status, _ in results]
            status, same_response = register(ports["identity"], same_key, same_email)
            assert status == 201
            assert assert_single_provisioned_user(container, same_email) == same_response["user"]["id"]

            race_email = "e2e-race-" + uuid.uuid4().hex + "@example.com"
            keys = [uuid.uuid4(), uuid.uuid4()]
            results = concurrent_register(ports["identity"], keys, race_email)
            statuses = [status for status, _ in results]
            assert statuses.count(409) == 1 and sum(status in (201, 503) for status in statuses) == 1, statuses
            winner = keys[next(index for index, status in enumerate(statuses) if status in (201, 503))]
            status, winner_response = register(ports["identity"], winner, race_email)
            assert status == 201
            assert assert_single_provisioned_user(container, race_email) == winner_response["user"]["id"]
            print("OK: crash with active lease, mTLS, retries and concurrent requests", flush=True)
            lost_response_scenario(container, ports["identity"], accounts_proxy, "accounts")
            lost_response_scenario(container, ports["identity"], coins_proxy, "coins")
            invalid_email = "invalid@@" + uuid.uuid4().hex + ".example"
            wrong_password = "wrong-" + uuid.uuid4().hex
            cases = [
                ("1-1-4000-8000-1", email, TEST_PASSWORD, 400, "invalid_request"),
                (uuid.uuid4(), invalid_email, TEST_PASSWORD, 422, "validation_error"),
                (key, email, wrong_password, 409, "idempotency_key_conflict"),
                (uuid.uuid4(), email, TEST_PASSWORD, 409, "email_already_registered"),
            ]
            for error_key, error_email, password, expected_status, expected_code in cases:
                error_status, error_body = register(ports["identity"], error_key, error_email, password)
                assert error_status == expected_status and error_body["error"]["code"] == expected_code
                serialized = json.dumps(error_body)
                for private in (str(error_key), error_email, password, "SELECT ", "Exception"):
                    assert private not in serialized, "Sensitive input or internal detail leaked in an error"
            fresh_status, fresh = login(ports["identity"], direct_email, TEST_PASSWORD)
            assert fresh_status == 200
            assert token_roles(fresh["access_token"]) == ["USER"]
            refresh_status, rotated = refresh_session(ports["identity"], fresh["refresh_token"])
            assert refresh_status == 200 and token_roles(rotated["access_token"]) == ["USER"]
            fresh = rotated
            direct_refresh = direct["session"]["refresh_token"]
            assert post_auth(ports["identity"], "logout", {"refresh_token": direct_refresh}) == 204
            assert post_auth(ports["identity"], "refresh", {"refresh_token": direct_refresh}) == 401
            assert post_auth(ports["identity"], "logout-all", bearer=fresh["access_token"]) == 204
            assert post_auth(ports["identity"], "logout-all", bearer=fresh["access_token"]) == 401
            assert post_auth(ports["identity"], "refresh", {"refresh_token": fresh["refresh_token"]}) == 401
            print("OK: logout and logout-all revoke sessions and refresh tokens", flush=True)
            sensitive_values = {
                "email": email,
                "password": TEST_PASSWORD,
                "idempotency key": str(key),
                "access token": body["session"]["access_token"],
                "refresh token": body["session"]["refresh_token"],
                "invalid email": invalid_email,
                "incorrect password": wrong_password,
                "logout refresh token": direct_refresh,
                "logout-all access token": fresh["access_token"],
                "logout-all refresh token": fresh["refresh_token"],
            }
            for service in ("identity-service", "accounts-service", "coins-service"):
                log_contents = (directory / (service + ".log")).read_text(encoding="utf-8", errors="replace")
                for label, value in sensitive_values.items():
                    assert value not in log_contents, label + " leaked into " + service + " log"
            print("OK: test secrets and email absent from service logs", flush=True)
        finally:
            if lock_process and lock_process.poll() is None:
                lock_process.terminate()
            for process, log in reversed(PROCESSES):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                log.close()
            for proxy in proxies:
                proxy.close()
            if container:
                subprocess.run(["docker", "stop", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
