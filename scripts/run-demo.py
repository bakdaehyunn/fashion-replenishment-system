#!/usr/bin/env python3
"""Run isolated Compose demos using only Docker Compose and Python 3."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.request import urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
BACKEND_DB = ("backend-db", "replenishment")
WMS_DB = ("wms-simulator-db", "wms_simulator")
SCENARIOS = ("normal", "timeout", "second-shipment", "zero-shipment")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", nargs="?", choices=(*SCENARIOS, "all"), default="all")
    args = parser.parse_args()
    project = f"fashion-demo-{uuid4().hex[:10]}"
    compose = ["docker", "compose", "-p", project, "-f", str(ROOT / "compose.yaml")]
    env = {
        **os.environ,
        "BACKEND_HOST_PORT": "0", "WMS_HOST_PORT": "0", "FRONTEND_HOST_PORT": "0",
        "WMS_RUN_ID": "normal-local", "WMS_TIMEOUT_RUN_ID": "", "WMS_SHIP_FIRST_RUN_ID": "",
        "WMS_TOKEN": uuid4().hex,
    }

    def run(command, **options):
        return subprocess.run(command, cwd=ROOT, env=env, check=True, **options)

    def docker(*command, **options):
        return run([*compose, *command], **options)

    def load(database, fixture):
        service, name = database
        with (ROOT / fixture).open("rb") as sql:
            docker("exec", "-T", service, "psql", "-U", name, "-d", name, "-v", "ON_ERROR_STOP=1", stdin=sql)

    def query(database, sql):
        service, name = database
        return docker("exec", "-T", service, "psql", "-U", name, "-d", name,
                      "-v", "ON_ERROR_STOP=1", "-Atc", sql, capture_output=True, text=True).stdout.strip()

    def endpoint(service, port):
        address = docker("port", service, str(port), capture_output=True, text=True).stdout.strip()
        return f"http://{address}"

    def ready(url):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                with urlopen(url, timeout=2) as response:
                    if response.status == 200:
                        return
            except OSError:
                pass
            time.sleep(0.4)
        raise RuntimeError(f"Service did not become ready: {url}")

    def smoke(name, base, *options):
        run([sys.executable, f"scripts/smoke-{name}.py", base, *options], timeout=60)

    def expect(database, sql, value):
        actual = query(database, sql)
        if actual != value:
            raise RuntimeError(f"Database check failed: expected {value!r}, got {actual!r}")

    print(f"Compose project: {project}", flush=True)
    try:
        docker("build", timeout=600)
        for scenario in SCENARIOS if args.scenario == "all" else (args.scenario,):
            print(f"\n=== {scenario} ===", flush=True)
            env["WMS_TIMEOUT_RUN_ID"] = "normal-local" if scenario == "timeout" else ""
            docker("up", "-d", "--no-build", "--wait", "--wait-timeout", "90", timeout=150)
            backend = endpoint("backend", 8080)
            wms = endpoint("wms-simulator", 8081)
            frontend = endpoint("frontend", 5173)
            for url in (backend + "/actuator/health", wms + "/actuator/health", frontend + "/"):
                ready(url)
            load(BACKEND_DB, "backend/src/test/fixtures/replenishment.sql")
            load(WMS_DB, "wms-simulator/src/test/fixtures/normal-outbound.sql")
            if scenario == "normal":
                smoke("outbound", frontend)
                smoke("receiving", frontend)
                expect(BACKEND_DB, "SELECT received_qty FROM receiving_inspection_item", "6")
            elif scenario == "timeout":
                smoke("outbound", frontend, "--timeout")
                expect(BACKEND_DB, "SELECT attempt_count FROM wms_outbox WHERE sent", "2")
                expect(WMS_DB, "SELECT count(*) FROM sim_order WHERE status='SHIPPED'", "1")
                expect(BACKEND_DB, "SELECT count(*) FROM inventory_movement WHERE normal_delta=-6", "1")
            else:
                load(BACKEND_DB, "backend/src/test/fixtures/picking-shortage.sql")
                load(WMS_DB, "wms-simulator/src/test/fixtures/picking-shortage.sql")
                if scenario == "zero-shipment":
                    smoke("picking-shortage", frontend, "--zero-shipment")
                    expect(BACKEND_DB, "SELECT count(*) FROM receiving_inspection_item", "0")
                    expect(WMS_DB, "SELECT status FROM sim_order", "CANCELLED")
                else:
                    smoke("picking-shortage", frontend)
                    load(WMS_DB, "wms-simulator/src/test/fixtures/picking-shortage-found.sql")
                    smoke("second-shipment", frontend)
                    expect(BACKEND_DB, "SELECT string_agg(received_qty::text,',' ORDER BY id) FROM receiving_inspection_item", "5,2")
                    expect(WMS_DB, "SELECT count(*) FROM sim_order WHERE status='SHIPPED'", "2")
            print(f"PASS: {scenario} · PostgreSQL {query(BACKEND_DB, 'SHOW server_version')}", flush=True)
            docker("down", "--volumes", timeout=60)
    except (OSError, subprocess.SubprocessError, RuntimeError):
        subprocess.run([*compose, "logs", "--tail", "50"], cwd=ROOT, env=env, timeout=25, check=False)
        raise
    finally:
        subprocess.run([*compose, "down", "--volumes", "--rmi", "local"],
                       cwd=ROOT, env=env, timeout=90, check=False)


if __name__ == "__main__":
    main()
