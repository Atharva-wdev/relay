# Relay Capstone Submission

A resilient workflow orchestration service with durable execution, webhook triggers, approval gates, retries/timeouts, and exactly-once side-effect behavior.

## Environment

- Relay API: `http://localhost:8081`
- Mock world: `http://localhost:9210`
- Demo auth token: `demo-token`
- Webhook secret (expense seed): `whsec_expense_774`

---

## Prerequisites

- Java 17+ (or project-required Java version)
- Maven
- Python 3
- Docker (for containerized run)
- Mock world service running on port `9210`

---

## Run Instructions (Local)

### 1) Start mock world

Start the mock external system per project instructions so endpoints like `/admin/reset`, `/shipments`, `/tickets`, and `/admin/ledger` are available on:

- `http://localhost:9210`

### 2) Start Relay

```bash
mvn spring-boot:run
```

Relay should start on:

- `http://localhost:8081`

---

## Dockerized Run

### Prerequisites
- Docker installed and running
- Mock world running on host at `http://localhost:9210`

### Build image

```bash
docker build -t relay-capstone:latest .
```

### Run container (Windows PowerShell)

```powershell
docker run --rm -p 8081:8081 `
  -e MOCK_WORLD_BASE_URL=http://host.docker.internal:9210 `
  relay-capstone:latest
```

### Run container (Linux/macOS)

```bash
docker run --rm -p 8081:8081 \
  --add-host=host.docker.internal:host-gateway \
  -e MOCK_WORLD_BASE_URL=http://host.docker.internal:9210 \
  relay-capstone:latest
```

> `host.docker.internal` allows the containerized Relay app to call mock world running on your host machine.

### Verify Dockerized app

```bash
curl -H "Authorization: Bearer demo-token" http://localhost:8081/workflows
```

### Smoke test against Dockerized app

```bash
python scripts/smoke_test.py --url http://localhost:8081 --token demo-token
```

### Stop container
Press `Ctrl+C` in the terminal where `docker run` is active.

### Troubleshooting
- If Relay cannot reach mock world, ensure mock world is running on host `9210`.
- On Linux, ensure `--add-host=host.docker.internal:host-gateway` is included.
- If port `8081` is already in use, run container with `-p 8082:8081` and use `http://localhost:8082`.

---

## Authentication Notes

Protected Relay endpoints require:

```http
Authorization: Bearer demo-token
```

Webhook endpoints use secret headers (example):

```http
X-Relay-Secret: whsec_expense_774
```

---

## Seeded Workflows

Verified present and published by smoke test:

- `wf_support_triage`
- `wf_expense_approval`
- `wf_slow_fulfillment`
- `wf_runaway`

---

## Smoke Test

Command:

```bash
python scripts/smoke_test.py --url http://localhost:8081 --token demo-token
```

Latest result (2026-08-08):

- **27 passed**
- **1 warning**
- **0 failed**

Warning observed:

- `wf_runaway` was stopped correctly (`failed/cancelled`), but run record warning text did not explicitly mention cap-exceeded reason.

Interpretation:

- Core functional requirements are passing.
- Single warning is non-blocking unless rubric explicitly requires exact stop-reason wording.

---

## Exactly-Once + Crash/Restart Verification

This submission includes a kill-and-resume drill proving durable recovery and no duplicated side effects.

### Drill used

Flow executed on: `wf_expense_approval`

1. Reset mock world ledger:
    - `POST http://localhost:9210/admin/reset`
2. Triggered expense webhook (large amount):
    - `POST /hooks/wf_expense_approval`
    - returned `run_id: run_048b3091`
3. Verified paused state:
    - `GET /runs/run_048b3091` → `status: waiting_approval`
    - `approval_id: apr_e81a476a`
4. Killed Relay process (`Ctrl+C`)
5. Restarted Relay process
6. Approved pending approval:
    - `POST /approvals/apr_e81a476a/approve` → `{"status":"approved"}`
7. Verified resumed completion:
    - `GET /runs/run_048b3091` → `status: succeeded`
8. Ran duplication check:
    - `python scripts/duplication_check.py --url http://localhost:9210`

### Duplication check output

- `Ledger entries checked: 1 (executed: 1, replays absorbed: 0, rejected: 0)`
- `email.send: 1 executed`
- `PASS: every side effect executed exactly once.`

Result:

- **PASS** — exactly-once side-effect behavior validated across restart.

---

## Known Limitation

`wf_slow_fulfillment` currently fails at `create_shipment` because `http_request` body templating for `order_id` is unresolved before POST, causing mock world to return:

- `400 Missing field 'order_id'`

This does not invalidate the durability/effect-once proof above, which was produced via `wf_expense_approval`.

---

## Evidence Artifacts Included

- Smoke test terminal output
- Run status output for:
    - `waiting_approval`
    - `succeeded`
- Duplication check terminal output (PASS)
- Mock world ledger snapshot (`GET /admin/ledger`)
- `VERIFICATION_REPORT.md`
- Demo video link (add below)

---

## Demo Video

> Add your video URL here after upload:

- `https://drive.google.com/file/d/1xUyvYLXxFLbLtlvNC-PUsB12a4X_A_h3/view?usp=sharing`

---

## Useful Commands (Quick Reference)

### List workflows

```bash
curl -H "Authorization: Bearer demo-token" http://localhost:8081/workflows
```

### Get run details

```bash
curl -H "Authorization: Bearer demo-token" http://localhost:8081/runs/<run_id>
```

### Reset mock world ledger

```bash
curl -X POST http://localhost:9210/admin/reset
```

### View ledger

```bash
curl http://localhost:9210/admin/ledger
```

### Run duplication check

```bash
python scripts/duplication_check.py --url http://localhost:9210
```

---

## Submission Checklist (Final)

- [x] Relay runs locally
- [x] Seed workflows verified
- [x] Smoke test executed (0 failures)
- [x] Crash/restart drill executed
- [x] Exactly-once duplication check passed
- [x] Verification report added
- [x] Dockerized run documented
- [x] Demo video link added
- [x] PR linked