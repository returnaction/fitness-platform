"""Определение выпускаемых сервисов на GitHub runner; на VPS Python не нужен."""
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

SERVICES = ("accounts-service", "coins-service", "identity-service", "gateway-service")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def version(xml, inherited=False):
    root = ET.fromstring(xml)
    value = root.findtext("m:version", namespaces=NS)
    if value is None and inherited:
        value = root.findtext("m:parent/m:version", namespaces=NS)
    return (value or "").strip()


def numeric(value):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", value):
        raise ValueError(f"Требуется числовая release-версия: {value!r}")
    parts = list(map(int, value.split(".")))
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def increased(old, new):
    current = numeric(new)
    if old is None:
        return True
    # Первый выпуск: от наследуемой/явной SNAPSHOT к собственной release-версии.
    if old.endswith("-SNAPSHOT"):
        if current < numeric(old.removesuffix("-SNAPSHOT")):
            raise ValueError("Release ниже предыдущей SNAPSHOT-версии")
        return True
    previous = numeric(old)
    if current < previous:
        raise ValueError(f"Понижение версии запрещено: {old} -> {new}")
    if current == previous and old != new:
        raise ValueError("Нельзя менять только запись версии без её повышения")
    return current > previous


def candidates(base):
    # Не маскируем отсутствующий Git commit под первый выпуск всех сервисов.
    initial = base == "0" * 40
    if not initial:
        subprocess.run(["git", "cat-file", "-e", f"{base}^{{commit}}"], check=True)
    result = []
    for service in SERVICES:
        path = f"services/{service}/pom.xml"
        new = version(Path(path).read_text(encoding="utf-8"))
        old = None
        if not initial:
            exists = subprocess.run(["git", "cat-file", "-e", f"{base}:{path}"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if exists.returncode == 0:
                xml = subprocess.check_output(["git", "show", f"{base}:{path}"])
                old = version(xml, inherited=True)
        if increased(old, new):
            result.append({"service": service, "version": new})
    return result


if __name__ == "__main__":
    selected = candidates(os.environ["BASE_SHA"])
    encoded = json.dumps(selected, separators=(",", ":"))
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"candidates={encoded}\n")
    print(encoded)
