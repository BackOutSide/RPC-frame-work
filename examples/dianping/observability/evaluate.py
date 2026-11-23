"""Run local monitoring drills with real Netty RPC, Prometheus and Alertmanager."""

import argparse
from collections import Counter
from contextlib import ExitStack
from datetime import datetime, timezone
import json
from math import ceil
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen


HERE = Path(__file__).resolve().parent
PROBE_BUILD = HERE.parent / "probe" / "target"
PROVIDER_WARNING = "DianpingProviderScrapeCoverageLow"
PROBE_CRITICAL = "RivetRpcProbeConsecutiveFailures"
RPC_CRITICAL = "RivetRpcClientHighAvailabilityFailureRate"
PROBE_WARNING = "RivetRpcProbeScrapeFailed"


def utc_now():
    return datetime.now(timezone.utc)


def local_ports(names):
    with ExitStack() as stack:
        sockets = {name: stack.enter_context(socket.socket()) for name in names}
        for sock in sockets.values():
            sock.bind(("127.0.0.1", 0))
        return {name: sock.getsockname()[1] for name, sock in sockets.items()}


def fetch(url):
    try:
        with urlopen(url, timeout=4) as response:
            return response.status, response.read()
    except HTTPError as failure:
        return failure.code, failure.read()
    except (OSError, URLError):
        return 0, b""


def eventually(description, condition, timeout, tick=0.5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(tick)
    raise AssertionError(f"Timed out waiting for {description}")


def stop_process(process):
    if process.poll() is None:
        process.kill()
    process.wait(timeout=10)


def traffic_summary(samples):
    counts = Counter(code for code, _ in samples)
    latencies = sorted(elapsed_ms for _, elapsed_ms in samples)
    return {"response_counts": dict(counts), "success_rate": counts[200] / len(samples),
            "p99_client_ms": round(latencies[ceil(0.99 * len(samples)) - 1], 2)}


class Evaluation:
    def __init__(self, args, directory, stack):
        self.args = args
        self.directory = directory
        self.stack = stack
        self.ports = local_ports(["zk", "consumer", "provider_a", "provider_b", "rpc_a", "rpc_b",
                                  "probe", "prometheus", "alertmanager", "receiver"])
        self.cases = []
        self.suffix = 0

    def address(self, name):
        return f"127.0.0.1:{self.ports[name]}"

    def http(self, name, path):
        return fetch(f"http://{self.address(name)}{path}")

    def process(self, name, *command):
        self.suffix += 1
        log_path = self.directory / f"{self.suffix:02d}-{name}.log"
        with log_path.open("wb") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, cwd=self.directory,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.stack.callback(stop_process, process)
        return process, log_path

    def ready(self, name, path, process, log):
        def verify():
            if process.poll() is not None:
                raise RuntimeError(f"{name} exited with code {process.returncode}. See {log}.")
            return self.http(name, path)[0] == 200

        eventually(f"{name} at {path}", verify, timeout=45)

    def java_role(self, name, profile=None, rpc_port=None):
        options = [f"--server.port={self.ports[name]}", f"--rpc.zk.address={self.address('zk')}"]
        if profile:
            options.append(f"--spring.profiles.active={profile}")
        if rpc_port:
            options.append(f"--rpc.server.port={self.ports[rpc_port]}")
        if name == "probe" and self.args.fast:
            options.append("--dianping.probe.interval-ms=1000")
        process, log = self.process(name, self.args.java, "-XX:ActiveProcessorCount=2", "-jar",
                                     str(self.args.jar), *options)
        self.ready(name, "/actuator/prometheus", process, log)
        return process

    def config(self, name, replacements):
        source = (HERE / name).read_text(encoding="utf-8")
        for original, replacement in replacements.items():
            if original not in source:
                raise ValueError(f"Missing evaluation setting {original!r} in {name}")
            source = source.replace(original, replacement)
        destination = self.directory / name
        destination.write_text(source, encoding="utf-8")
        return destination

    def start_stack(self):
        classpath = self.args.zk_classpath_file.read_text(encoding="utf-8").strip()
        zk, log = self.process("zk", self.args.java, "-XX:ActiveProcessorCount=2", "-cp",
                               str(PROBE_BUILD / "test-classes") + os.pathsep + classpath,
                               "io.github.backoutside.rivet.dianping.probe.EvaluationZooKeeper",
                               str(self.ports["zk"]))

        def zk_ready():
            if zk.poll() is not None:
                raise RuntimeError(f"ZooKeeper exited with code {zk.returncode}. See {log}.")
            return "RIVET_EVALUATION_ZK_READY" in log.read_text(encoding="utf-8", errors="replace")

        eventually("ZooKeeper startup", zk_ready, timeout=30)
        receiver, log = self.process("receiver", sys.executable, str(HERE / "notification_receiver.py"),
                                     "--port", str(self.ports["receiver"]))
        self.ready("receiver", "/healthz", receiver, log)

        self.provider_a = self.java_role("provider_a", "fixture-provider", "rpc_a")
        self.provider_b = self.java_role("provider_b", "fixture-provider", "rpc_b")
        self.java_role("consumer", "fixture-consumer")
        self.probe = self.java_role("probe")
        # The fast profile changes timing in a scratch copy only. promtool tests the repository rules.
        rules = self.config("alerts.yml", {"for: 1m": "for: 6s", "for: 2m": "for: 6s",
                                           "for: 5m": "for: 10s", "[5m]": "[30s]"} if self.args.fast else {})
        am_options = {"notification-receiver:8080": self.address("receiver")}
        if self.args.fast:
            am_options.update({"group_wait: 10s": "group_wait: 1s", "group_wait: 5s": "group_wait: 1s",
                               "group_interval: 1m": "group_interval: 5s"})
        am_config = self.config("alertmanager.yml", am_options)
        am, log = self.process("alertmanager", self.args.alertmanager, f"--config.file={am_config}",
                               f"--storage.path={self.directory / 'am-data'}",
                               f"--web.listen-address={self.address('alertmanager')}", "--cluster.listen-address=")
        self.ready("alertmanager", "/-/ready", am, log)
        prom_options = {"/etc/prometheus/alerts.yml": json.dumps(rules.as_posix()),
                        "alertmanager:9093": self.address("alertmanager"), "localhost:9090": self.address("prometheus"),
                        "host.docker.internal:18080": self.address("consumer"),
                        "host.docker.internal:18081": self.address("provider_a"),
                        "host.docker.internal:18082": self.address("provider_b"),
                        "host.docker.internal:18083": self.address("probe"), "environment: local": "environment: evaluation"}
        if self.args.fast:
            prom_options.update({"scrape_interval: 15s": "scrape_interval: 2s",
                                 "evaluation_interval: 15s": "evaluation_interval: 2s",
                                 "scrape_timeout: 5s": "scrape_timeout: 1s"})
        prom_config = self.config("prometheus.yml", prom_options)
        prom, log = self.process("prometheus", self.args.prometheus, f"--config.file={prom_config}",
                                 f"--storage.tsdb.path={self.directory / 'prom-data'}",
                                 f"--web.listen-address={self.address('prometheus')}")
        self.ready("prometheus", "/-/ready", prom, log)

    def metric(self, expression):
        status, body = self.http("prometheus", "/api/v1/query?" + urlencode({"query": expression}))
        if status == 200:
            return [float(item["value"][1]) for item in json.loads(body)["data"]["result"]]
        return []

    def events(self):
        status, body = self.http("receiver", "/events")
        return json.loads(body) if status == 200 else []

    def notification(self, name, status, since):
        for event in self.events():
            if datetime.fromisoformat(event["received_at"]) < since:
                continue
            for alert in event["payload"]["alerts"]:
                if alert["labels"].get("alertname") == name and alert["status"] == status:
                    return {"alertname": name, "status": status, "labels": alert["labels"],
                            "started_at": alert["startsAt"], "ended_at": alert["endsAt"],
                            "received_at": event["received_at"],
                            "delivery_seconds": (datetime.fromisoformat(event["received_at"]) - since).total_seconds()}
        return None

    def await_notification(self, name, status, since, timeout, traffic=None):
        def delivered():
            if traffic is not None:
                start = time.monotonic()
                code, _ = self.ping()
                traffic.append((code, (time.monotonic() - start) * 1000))
            return self.notification(name, status, since)

        result = eventually(f"{name} {status} notification", delivered, timeout, tick=0.25)
        assert result["labels"].get("environment") == "evaluation", result
        assert result["labels"].get("severity") == ("critical" if name in (PROBE_CRITICAL, RPC_CRITICAL)
                                                     else "warning"), result
        return result

    def retries(self):
        return sum(self.metric('rivet_rpc_client_retries_total{job="dianping-web"}'))

    def ping(self):
        return self.http("consumer", "/rpc-evaluation/ping")

    def passed(self, name, **evidence):
        self.cases.append({"case": name, "result": "passed", **evidence})
        print(f"PASS {name}", flush=True)

    def run(self):
        self.start_stack()
        short = 35 if self.args.fast else 145
        long = 70 if self.args.fast else 405
        eventually("two Provider scrapes", lambda: self.metric('sum(up{job="dianping-provider"})') == [2.0], short)
        eventually("fresh successful RPC probe", lambda: self.metric(
            'rivet_rpc_probe_consecutive_failures{job="rivet-rpc-probe"}') == [0.0] and any(self.metric(
                'rivet_rpc_probe_last_completed_timestamp_seconds{job="rivet-rpc-probe"}')), short)
        for _ in range(4):
            assert self.ping()[0] == 200
        self.passed("healthy RPC, two Provider scrapes and a completed probe")

        retries_before = self.retries()
        injected = utc_now()
        stop_process(self.provider_a)
        traffic = []
        warning = self.await_notification(PROVIDER_WARNING, "firing", injected, short, traffic)
        summary = traffic_summary(traffic)
        assert summary["success_rate"] == 1.0, summary
        assert not any(self.notification(name, "firing", injected) for name in (PROBE_CRITICAL, RPC_CRITICAL))
        self.passed("one Provider down, surviving Provider serves traffic, Warning delivered",
                    notification=warning, traffic=summary, observed_retries=self.retries() - retries_before)

        injected = utc_now()
        stop_process(self.provider_b)
        critical = self.await_notification(PROBE_CRITICAL, "firing", injected, short)
        assert not self.notification(RPC_CRITICAL, "firing", injected)
        self.passed("two Providers down with no traffic, probe Critical delivered", notification=critical)

        traffic_started = utc_now()
        traffic = []
        critical = self.await_notification(RPC_CRITICAL, "firing", traffic_started, long, traffic)
        summary = traffic_summary(traffic)
        assert summary["response_counts"] == {503: len(traffic)} and len(traffic) >= 20, summary
        self.passed("failed real Consumer calls trigger error rate Critical", notification=critical,
                    traffic=summary)

        restored = utc_now()
        self.provider_a = self.java_role("provider_a", "fixture-provider", "rpc_a")
        self.provider_b = self.java_role("provider_b", "fixture-provider", "rpc_b")
        eventually("real RPC recovery", lambda: self.ping()[0] == 200, short)
        traffic = []
        deliveries = [self.await_notification(name, "resolved", restored, long, traffic)
                      for name in (PROBE_CRITICAL, PROVIDER_WARNING, RPC_CRITICAL)]
        summary = traffic_summary(traffic)
        assert summary["success_rate"] == 1.0, summary
        self.passed("RPC and redundancy recover, three Resolved notifications delivered",
                    notifications=deliveries, traffic=summary)

        injected = utc_now()
        stop_process(self.probe)
        warning = self.await_notification(PROBE_WARNING, "firing", injected, short)
        assert self.ping()[0] == 200
        assert not self.notification(PROBE_CRITICAL, "firing", injected)
        restored = utc_now()
        self.probe = self.java_role("probe")
        resolution = self.await_notification(PROBE_WARNING, "resolved", restored, short)
        self.passed("probe process outage isolated as monitoring Warning", notification=warning,
                    resolution=resolution)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus", required=True, help="Path to the Prometheus executable")
    parser.add_argument("--alertmanager", required=True, help="Path to the Alertmanager executable")
    parser.add_argument("--java", default="java")
    parser.add_argument("--jar", type=Path, default=PROBE_BUILD / "rivet-rpc-example-dianping-probe-1.0-SNAPSHOT.jar")
    parser.add_argument("--zk-classpath-file", type=Path, default=PROBE_BUILD / "evaluation-classpath.txt")
    parser.add_argument("--fast", action="store_true", help="Shorten wait and observation windows in a test copy")
    args = parser.parse_args()
    for file in (args.jar, args.zk_classpath_file):
        if not file.is_file():
            parser.error(f"Missing {file}. Build the probe and its evaluation classpath first.")
    args.jar = args.jar.resolve()
    args.zk_classpath_file = args.zk_classpath_file.resolve()
    for option in ("prometheus", "alertmanager", "java"):
        value = getattr(args, option)
        if Path(value).is_file():
            setattr(args, option, str(Path(value).resolve()))
    PROBE_BUILD.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="monitoring-evaluation-", dir=PROBE_BUILD))
    print(f"Evaluation output: {directory}", flush=True)
    report = {"profile": "fast" if args.fast else "standard", "started_at": utc_now().isoformat()}
    with ExitStack() as stack:
        evaluation = Evaluation(args, directory, stack)
        try:
            evaluation.run()
            report["result"] = "passed"
        except Exception as failure:
            report.update({"result": "failed", "failure": str(failure)})
            raise
        finally:
            report.update({"ended_at": utc_now().isoformat(), "cases": evaluation.cases})
            (directory / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            events = evaluation.events()
            (directory / "notifications.json").write_text(json.dumps(events, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
