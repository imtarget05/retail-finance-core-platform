# Local transaction and reconciliation demo — 2026-11-19

Tested source commit: `5c61e728cc907c6f897645385d7d991b08c85578` (the Apple Silicon Compose override is part of this commit). This is a disposable **synthetic** demo with MySQL 8, the Java core, and the Python reconciliation service. It did not connect to a bank, payment rail, or partner system. Docker Compose volumes and containers were removed after the run.

The Apple Silicon host ran the Java core as `linux/amd64` under Docker emulation because `eclipse-temurin:17-jre-alpine` had no `arm64` manifest at build time. MySQL and Python ran as `linux/arm64`. The first plain Compose attempt failed before application startup on that manifest mismatch; `docker-compose.apple-silicon.yml` makes the architecture choice explicit. CI on Ubuntu uses the regular Compose file.

## Reproduce

From the repository root with Docker Desktop running:

```sh
docker compose -f docker-compose.yml -f docker-compose.apple-silicon.yml up -d --build mysql core
python3 - <<'PY'
import os
import subprocess
from scripts.backend_application_demo import CORE, login, wait_for
wait_for(f'{CORE}/actuator/health', timeout=180)
env = os.environ.copy()
env['CORE_SERVICE_TOKEN'] = login('ops', env.get('DEMO_SEED_PASSWORD', 'ChangeMe123!'))
subprocess.run(['docker', 'compose', '-f', 'docker-compose.yml',
                '-f', 'docker-compose.apple-silicon.yml', 'up', '-d',
                '--build', '--no-deps', 'reconciliation'], env=env, check=True)
PY
python3 scripts/backend_application_demo.py
docker compose -f docker-compose.yml -f docker-compose.apple-silicon.yml down -v
```

The OPS JWT is passed only to the reconciliation container environment; it is not printed or saved in the report. On a regular `linux/amd64` host, use the same commands without the Apple Silicon override file. The demo script writes `demo-results/backend-application.json` before cleanup.

## Observed result

The [raw application report](local-backend-application-2026-11-19.json) recorded `status: passed`, source SHA, timestamps, synthetic account and transaction references, and elapsed time **3.701 seconds**. Replaying the same key preserved the reference and moved 100,000 VND once. Reusing that key with a changed amount returned HTTP 409. Two concurrent requests with another shared key both returned HTTP 200 and moved another 20,000 VND only once, leaving a 120,000 VND balance. An overdraw returned HTTP 422 without changing the balance. The audit API returned two rows for each settled transaction. The reconciliation API matched both references (2/2), then identified one 1 VND amount mismatch. The [Compose snapshot](local-backend-compose-2026-11-19.json) shows MySQL and core healthy; reconciliation was still in Docker's health-check start period, while its `/health` endpoint and demo API calls had already succeeded.

The same source was checked locally with `cd backend && mvn -B verify`: **28 unit tests and 33 MySQL 8 Testcontainers integration tests passed**, including `auditFailureRollsBackMoneyMovement`, concurrent debit, idempotency, settlement feed and authorization scenarios. `cd reconciliation-service && python3 -m pytest tests/ -q` passed **17 tests**. The failure-between-writes assertion comes from the integration test, not from deliberate fault injection during the interactive Compose demo. The earlier [main CI run](https://github.com/imtarget05/retail-finance-core-platform/actions/runs/44107115712) is tied to the parent commit; this evidence change requires a fresh PR/main CI check before its new SHA is used in a CV.
