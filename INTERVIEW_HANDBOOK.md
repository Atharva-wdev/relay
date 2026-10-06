# Relay Interview Handbook

This handbook is a project-specific guide for explaining Relay in interviews: what it does, how the code works, why it was designed this way, what trade-offs it makes, and what you would improve. It distinguishes implemented behavior from goals and documented verification so you can discuss the project accurately.

## 1. The project in one minute

### Short introduction

> Relay is a Java and Spring Boot workflow orchestration service. Workflows are JSON graphs made from catalogued node types. A user can save and publish a workflow, then trigger it manually or through a webhook. Relay persists each run and its progress in MySQL, processes queued work, and records step results. Nodes can branch, call external services, use structured AI output, wait for human approval, or perform sensitive actions. For side-effecting calls, Relay sends an idempotency key so a cooperating external service can absorb retries.

### Slightly longer introduction

> The main problem Relay explores is how to execute a multi-step workflow while keeping enough state to inspect and continue a run. Workflow definitions are validated before publication, and each run stores a definition snapshot so edits do not change its in-flight graph. A scheduled worker executes nodes in sequence, persists outputs and the next node, and pauses at approval nodes. The implementation demonstrates the main orchestration path, but I would not describe it as production-ready exactly-once infrastructure: queue recovery, multi-worker claiming, the external-call crash window, limits, and automated test coverage need more work.

The project describes its goals as durable execution, webhooks, approvals, retries/timeouts, and exactly-once side-effect behavior in [`/home/runner/work/relay/relay/README.md`](README.md#L1-L3). The qualification in the longer introduction matters: stated goals are not all implemented guarantees.

## 2. What problem does it solve?

An ordinary HTTP handler is a poor place to run long workflows: it can take too long, may be interrupted, and is difficult to inspect or pause for a human decision. Relay accepts a workflow and trigger input, creates a durable run record, and lets a worker execute the graph separately from the trigger request.

The system therefore needs to:

- Describe work as a graph rather than hard-code each business process.
- Validate workflow structure and node parameters before a workflow is published.
- Store each run's input, definition snapshot, status, current node, and step history.
- Continue after explicit pauses, especially for approvals.
- Retry selected failures and make repeated external requests safe when the receiver supports idempotency.
- Expose run and approval state through APIs and a small web UI.

## 3. Repository tour

| Area | Responsibility |
|---|---|
| `/home/runner/work/relay/relay/src/main/java/com/relay/RelayApplication.java` | Spring Boot entry point. |
| `/home/runner/work/relay/relay/src/main/java/com/relay/controller/` | HTTP endpoints and Thymeleaf-backed UI routes. |
| `/home/runner/work/relay/relay/src/main/java/com/relay/engine/EngineService.java` | Queue polling, run orchestration, node dispatch, retries, and context assembly. |
| `/home/runner/work/relay/relay/src/main/java/com/relay/service/` | Authentication, validation, catalog and seed loading, templates, and AI integration. |
| `/home/runner/work/relay/relay/src/main/java/com/relay/domain/` | JPA entities mapping the main database records. |
| `/home/runner/work/relay/relay/src/main/java/com/relay/repo/` | Spring Data repositories and derived queries. |
| `/home/runner/work/relay/relay/src/main/resources/data/node_catalog.json` | Supported trigger/node definitions and parameter metadata. |
| `/home/runner/work/relay/relay/src/main/resources/data/seed_workflows.json` | Example workflows loaded at startup. |
| `/home/runner/work/relay/relay/src/main/resources/db/migration/V1__init.sql` | Initial MySQL schema managed by Flyway. |
| `/home/runner/work/relay/relay/src/main/resources/templates/` | Simple workflow, run, detail, and approvals pages. |
| `/home/runner/work/relay/relay/src/main/resources/application.yml` | Local service, database, and AI configuration defaults. |
| `/home/runner/work/relay/relay/pom.xml` | Maven dependencies and Java target. |
| `/home/runner/work/relay/relay/Dockerfile` | Multi-stage container build and runtime. |

The Spring Boot application starts in [`RelayApplication.java`](src/main/java/com/relay/RelayApplication.java#L6-L10). Scheduling is enabled by [`WebConfig.java`](src/main/java/com/relay/config/WebConfig.java#L6-L8).

## 4. Main concepts and data model

### Workflow

A workflow is a JSON graph with `id`, `name`, `trigger`, `entry`, `nodes`, and `limits`. A node has an ID, a type, parameters, and either a normal `next` edge or conditional `on_true` / `on_false` edges. The canonical catalog lists `webhook`, `schedule`, and `manual` triggers and the node types `http_request`, `condition`, `delay`, `notify`, `ai`, `approval`, and `order_action` ([`node_catalog.json`](src/main/resources/data/node_catalog.json#L3-L104)).

### Run

A run is one execution of a workflow. It stores the trigger type and input, a snapshot of the workflow JSON, status, current node, counts, errors, and timestamps. The engine advances one node at a time and records each successful node in the step history ([`RunEntity.java`](src/main/java/com/relay/domain/RunEntity.java#L8-L36); [`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L89-L186)).

### Step

A step record stores run ID, node ID/type, sequence, status, attempt, parameters, output, optional AI token counts, idempotency key, duration, and error details ([`RunStepEntity.java`](src/main/java/com/relay/domain/RunStepEntity.java#L8-L37)). This creates a useful execution trace for debugging and inspection.

### Approval

An approval records the run/node, message, pending/approved/rejected status, decider, and decision timestamps ([`ApprovalEntity.java`](src/main/java/com/relay/domain/ApprovalEntity.java#L8-L25)).

### Queue job

A queue job tracks run ID, status, availability time, lease time, attempts, and creation time. The database enforces one job row per run ([`QueueJobEntity.java`](src/main/java/com/relay/domain/QueueJobEntity.java#L8-L22); [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql#L59-L69)).

### Why separate these records?

The separation gives the system distinct lifecycles and query paths: reusable workflow definitions; runtime run state; append-like step history; approval decisions; and worker scheduling state. It also makes it easier to inspect a run without parsing all state from one large JSON document.

## 5. End-to-end execution walkthrough

Use `wf_expense_approval` as a concrete interview example. It checks an expense amount, pauses for approval when the amount is above $100, and sends an email after approval; smaller expenses go to a separate notification node ([`seed_workflows.json`](src/main/resources/data/seed_workflows.json#L73-L116)).

1. **Author a definition.** `POST /workflows` stores its JSON as a draft. The controller currently accepts a generic map and checks for an ID ([`WorkflowController.java`](src/main/java/com/relay/controller/WorkflowController.java#L47-L61)).
2. **Publish it.** `POST /workflows/{id}/publish` calls `PublishValidationService` with the loaded node catalog and sets the status to published only if validation succeeds ([`WorkflowController.java`](src/main/java/com/relay/controller/WorkflowController.java#L64-L79)).
3. **Trigger a run.** A manual trigger uses `POST /workflows/{id}/trigger`; a webhook uses `POST /hooks/{id}` and supplies `X-Relay-Secret`. Both create a run and queue job. The run stores a copy of the definition and its input ([`WorkflowController.java`](src/main/java/com/relay/controller/WorkflowController.java#L82-L109); [`WebhookController.java`](src/main/java/com/relay/controller/WebhookController.java#L26-L60)).
4. **Poll the queue.** `EngineService.poll()` is scheduled with a one-second fixed delay. It scans queue rows, processes queued jobs that are available, and updates their state ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L41-L87)).
5. **Build the graph lookup.** `processRun()` loads the persisted run snapshot, creates a map of node IDs to node definitions, and starts from the entry node or saved `currentNodeId` ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L89-L110)).
6. **Build template context.** Before each node, the engine combines trigger input and persisted outputs from earlier steps under `trigger.body` and `nodes.<nodeId>.output` ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L134-L136,386-L395)).
7. **Execute and record.** The engine dispatches the node, saves its output and sequence, increments counters, determines the next edge, and persists the current node ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L130-L174)).
8. **Pause or finish.** For an approval node, it sets `waiting_approval` and returns. Otherwise it follows edges until no next node remains, then sets `succeeded` ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L172-L185,269-L279)).
9. **Resume or cancel.** Approval endpoints save a decision. Approval requeues the run; rejection marks it cancelled ([`ApprovalController.java`](src/main/java/com/relay/controller/ApprovalController.java#L30-L88)).
10. **Inspect.** `GET /runs/{id}` returns status and ordered step details. The UI routes render workflows, runs, run detail, and pending approvals ([`RunController.java`](src/main/java/com/relay/controller/RunController.java#L19-L39); [`UiController.java`](src/main/java/com/relay/controller/UiController.java#L18-L41)).

### Whiteboard version

Draw these boxes and arrows:

`Client/Webhook -> Controller -> MySQL (run + job) -> Scheduled Poller -> Engine -> MySQL (step + run state)`

Then draw side connections from the engine to `Mock World HTTP` and `AI Provider`, and a pause/resume loop from `Engine -> Approval record -> Approver API -> Queue -> Engine`.

Explain that the database holds the workflow snapshot and progress; the external services perform work outside Relay's database transaction.

## 6. Code walkthrough by file

When asked to explain the implementation, walk through the code in this order:

1. **Application startup:** [`RelayApplication.java`](src/main/java/com/relay/RelayApplication.java#L6-L10) launches Spring; [`WebConfig.java`](src/main/java/com/relay/config/WebConfig.java#L6-L8) enables scheduled work.
2. **Workflow lifecycle:** [`WorkflowController.java`](src/main/java/com/relay/controller/WorkflowController.java#L29-L109) lists, reads, saves, publishes, and triggers workflow records.
3. **Validation:** [`CatalogService.java`](src/main/java/com/relay/service/CatalogService.java#L11-L25) loads the catalog; [`PublishValidationService.java`](src/main/java/com/relay/service/PublishValidationService.java#L18-L112) validates required structure, IDs, references, parameters, and approval presence.
4. **Trigger ingress:** [`WebhookController.java`](src/main/java/com/relay/controller/WebhookController.java#L26-L60) checks the workflow secret and persists a queued run; [`AuthFilter.java`](src/main/java/com/relay/service/AuthFilter.java#L19-L40) protects most non-webhook/non-UI paths with a bearer token.
5. **Queue and interpreter:** [`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L41-L186) is the core path: polling, state checks, node traversal, execution, persistence, and completion.
6. **Node helpers:** `executeNodeOnce` dispatches built-in node types; `executeWithRetry` handles attempt records and backoff ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L188-L224,226-L312)).
7. **Templates and AI:** [`TemplateService.java`](src/main/java/com/relay/service/TemplateService.java#L11-L34) resolves string placeholders; [`AiService.java`](src/main/java/com/relay/service/AiService.java#L43-L109) calls the provider and validates structured output.
8. **Persistence:** [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql#L1-L69) gives the schema; entity classes map tables and repository interfaces provide CRUD/derived queries.
9. **Human-visible output:** [`RunController.java`](src/main/java/com/relay/controller/RunController.java#L19-L44) returns trace data; [`UiController.java`](src/main/java/com/relay/controller/UiController.java#L18-L41) prepares page models.

## 7. API and service interactions

### Relay API

| Method and path | Purpose |
|---|---|
| `GET /workflows` | List workflow IDs, names, and statuses. |
| `GET /workflows/{id}` | Read a stored definition. |
| `POST /workflows` | Create or update a draft. |
| `POST /workflows/{id}/publish` | Validate and publish. |
| `POST /workflows/{id}/trigger` | Start a published workflow manually. |
| `POST /hooks/{id}` | Start a run after checking the workflow's webhook secret. |
| `GET /runs/{id}` | Read run status and ordered step trace. |
| `GET /approvals?status=pending` | List approvals by status. |
| `POST /approvals/{id}/approve` | Approve and requeue a run. |
| `POST /approvals/{id}/reject` | Reject and cancel a run. |
| `GET /ui/workflows`, `/ui/runs`, `/ui/runs/{id}`, `/ui/approvals` | Render the basic HTML views. |

The routes are declared in [`WorkflowController.java`](src/main/java/com/relay/controller/WorkflowController.java#L29-L109), [`WebhookController.java`](src/main/java/com/relay/controller/WebhookController.java#L26-L60), [`RunController.java`](src/main/java/com/relay/controller/RunController.java#L19-L39), [`ApprovalController.java`](src/main/java/com/relay/controller/ApprovalController.java#L23-L88), and [`UiController.java`](src/main/java/com/relay/controller/UiController.java#L18-L41).

### External calls

- Mock-world actions include email/chat notifications and refund/replacement operations. Side-effecting calls carry an `Idempotency-Key` ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L281-L349)).
- `http_request` uses a configured URL and makes an HTTP exchange ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L256-L267)).
- AI nodes POST to `/v1/chat/completions`, parse the first choice as JSON, and validate against JSON Schema ([`AiService.java`](src/main/java/com/relay/service/AiService.java#L80-L109)).

## 8. Algorithms and implementation patterns

### Graph traversal

The engine indexes nodes by ID once per run, starts from the entry or saved current node, and walks until the selected edge is null. A condition chooses one of two edges; other node types follow `next` ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L100-L110,172-L185,375-L384)). The `max_steps` guard bounds loops ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L107-L115)).

### Context and templates

Prior step JSON outputs and trigger body are assembled into a map, and `TemplateService` substitutes `{{path.to.value}}` using nested map lookup ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L386-L395); [`TemplateService.java`](src/main/java/com/relay/service/TemplateService.java#L11-L34)). Missing paths resolve to an empty string when substituted.

### Retry and backoff

Retry is restricted to selected node types. Transient exceptions include resource access/server errors and certain I/O causes; the node retry path records failed attempts and uses short fixed delays. Queue-level processing errors have a separate attempt counter and retry policy ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L188-L224,352-L373,65-L84)).

### Idempotency keys

The engine builds the key as `runId:nodeId:sequence`. It sends this key for side-effect calls, allowing a receiver with a durable deduplication ledger to treat repeated requests as replays ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L130-L136,261-L265,293-L298,341-L349)). The key has meaning only if the receiver stores and enforces it.

### Structured AI output

AI output is treated as untrusted structured data: parse it as JSON, validate it against a provided JSON Schema, and retry once with validation feedback before failing ([`AiService.java`](src/main/java/com/relay/service/AiService.java#L43-L88)).

## 9. Database design and consistency

Flyway's initial migration creates:

- `workflows`: workflow JSON and publication metadata (`V1__init.sql:1-8`).
- `runs`: workflow snapshot, trigger input, status, current node, counts, and errors (`V1__init.sql:10-25`).
- `run_steps`: per-node attempt and output trace (`V1__init.sql:27-44`).
- `approvals`: human decision state (`V1__init.sql:46-57`).
- `queue_jobs`: work scheduling, retry/lease metadata, one job per run (`V1__init.sql:59-69`).

Hibernate is configured to validate the schema rather than generate it, while Flyway owns migrations (`/home/runner/work/relay/relay/src/main/resources/application.yml:9-17`). Repository interfaces are thin Spring Data JPA interfaces, such as [`RunStepRepo.java`](src/main/java/com/relay/repo/RunStepRepo.java#L7-L9).

`processRun()` is annotated `@Transactional` ([`EngineService.java`](src/main/java/com/relay/engine/EngineService.java#L89-L91)). However, an outbound HTTP action cannot be atomically committed with the MySQL transaction. A process can fail after the receiver applies an action but before Relay records success. Idempotency at the receiver reduces duplicate effects, but it is not a distributed transaction.

## 10. Configuration, dependencies, and running locally

### Stack

- Java with Spring Boot 3.3.4; Maven declares Java 21 (`/home/runner/work/relay/relay/pom.xml:6-20`).
- Spring Web, Spring Data JPA, Validation, Thymeleaf (`pom.xml:22-38`).
- Flyway and MySQL connector (`pom.xml:40-52`).
- NetworkNT JSON Schema validator, Lombok, and Spring Boot test starter (`pom.xml:54-70`).
- MySQL is configured at `localhost:3306`; Relay defaults to port `8081`, mock world `localhost:9210`, and AI service `localhost:9001` (`/home/runner/work/relay/relay/src/main/resources/application.yml:1-25`).

### Local startup

The README describes starting the mock-world service separately, then running:

```bash
mvn spring-boot:run
```

The documented local setup and Docker instructions are in [`README.md`](README.md#L14-L73). Seed workflows load during application startup in [`SeedService.java`](src/main/java/com/relay/service/SeedService.java#L17-L40).

### Deployment caveat

The Maven build targets Java 21, but the Docker build and runtime stages use Java 17 (`pom.xml:18-20`; [`Dockerfile`](Dockerfile#L1-L26)). Treat this as a mismatch to resolve or verify; do not imply the images and compiler target are aligned.

### Configuration caveat

`application.yml` contains demo/default credentials. They are suitable only for a local demo: production deployment should inject secrets and environment-specific endpoints rather than use checked-in defaults.

## 11. Testing and evidence

The Maven project includes `spring-boot-starter-test` (`pom.xml:66-70`), but this checkout has no test source files and no `scripts/` directory. The README and verification report describe smoke tests and a manual crash/restart drill, but those scripts/results are not runnable from the files currently present.

The report records a run paused for approval, Relay restart, approval after restart, resumed success, and one email in the external ledger (`/home/runner/work/relay/relay/VERIFICATION_REPORT.md:23-77`). The README reports 27 smoke checks passed, one warning, and no failures, and documents a templating failure in the slow-fulfillment example (`README.md:124-145,186-192`). Present these as documented project evidence, not as automated tests you personally executed in this checkout.

### Tests worth proposing in an interview

1. Unit-test publish validation: missing fields, duplicate IDs, invalid edges, bad template references, and approval requirements.
2. Unit-test the template resolver for nested values, missing/null values, and strings with multiple placeholders.
3. Test each node type, branch behavior, max-step termination, and error/status persistence.
4. Test retries for transient versus permanent errors, including an idempotent external receiver.
5. Integration-test run creation, approval pause/resume, rejection, and ordered step retrieval with a real or containerized database.
6. Add failure-injection tests at the boundary between external side effect and persisted step result.
7. Test concurrent workers to prove that one job cannot be claimed by two instances.
8. Test webhook authentication, duplicate delivery, and secret rotation.

## 12. Trade-offs and honest design critique

### What the current design gets right for a capstone

- **Simple workflow representation:** JSON graphs are easy to store and inspect; the explicit edges make branching visible.
- **Catalog-driven validation:** supported nodes and parameter requirements are discoverable in one resource file.
- **Persistent execution trace:** input, outputs, statuses, attempts, and timing make runs diagnosable.
- **Definition snapshots:** a run's graph is stable even if the reusable workflow definition changes.
- **Human approval as a first-class pause:** the approval state is persisted and can be acted on independently of the worker.
- **External idempotency support:** deterministic keys are included on side-effect requests.
- **Small familiar stack:** Spring Boot, JPA, MySQL, Flyway, and Thymeleaf are accessible and quick to develop with.

### Costs and limitations

- **Polling simplicity versus throughput:** scanning all queue rows every second is straightforward but does not scale well and may repeatedly load irrelevant rows.
- **Single-process assumptions versus concurrency:** the poller marks a job processing but the shown implementation does not atomically claim it. Multiple instances could race. The schema has `leased_until`, but the poll loop only selects queued jobs and does not reclaim expired processing jobs (`EngineService.java:41-50`; `V1__init.sql:59-69`).
- **Blocking execution versus worker utilization:** node execution is serial, `delay` uses `Thread.sleep`, and a run may occupy the polling thread during a delay or slow external call (`EngineService.java:231-235`).
- **At-least-once attempts versus exactly-once effects:** retries can repeat a call; exactly-once effects depend on durable external idempotency. Relay cannot guarantee atomicity across MySQL and another service.
- **Generic maps versus type safety:** workflow JSON is flexible, but maps require runtime casts and make invalid shapes easier to express.
- **Embedded workflow JSON versus queryability:** JSON makes definitions flexible, but versioning, indexing, migrations, and querying individual nodes become more difficult.
- **Demo authentication versus production identity:** one configured bearer token is not a user/role model; `/ui/*` is exempted from auth alongside hooks (`AuthFilter.java:24-40`).
- **Worker orchestration versus advertised triggers/limits:** the catalog lists schedule triggers and seed definitions include time/token limits, but the inspected code has no scheduler-trigger execution path and enforces `max_steps` only (`node_catalog.json:3-18`; `EngineService.java:107-115`).
- **Catalog validation versus runtime consistency:** catalog metadata such as configurable HTTP headers is not fully reflected in the shown HTTP execution path; parameter types/enums are not comprehensively validated there.

### Useful improvements to propose

Prioritize improvements by risk rather than suggesting a rewrite:

1. **Make queue claiming atomic.** Claim a bounded batch with a database lock/conditional update, define lease expiry recovery, and test two workers racing for a job.
2. **Define crash semantics for a node.** Track `started`/`succeeded` attempt states, persist request identity, and make replay semantics explicit. Use an outbox or receiver-side idempotency contract where appropriate.
3. **Resolve templates consistently.** Recursively resolve values in HTTP request bodies and headers, then test nested object/list payloads.
4. **Enforce declared limits.** Add deadline/cancellation handling for run timeouts and AI token budgets, not just a step cap.
5. **Move secrets out of source defaults.** Use environment/secret-manager-backed configuration, hash or encrypt webhook secrets, and apply authentication/authorization to UI routes.
6. **Make definitions versioned and typed.** Add a schema/version field, validate node payloads and enums, and preserve immutable published versions.
7. **Expand tests and observability.** Add integration/failure tests, structured logs, metrics for queue age/retries/run duration, and correlation IDs.
8. **Align build/runtime Java versions.** Make the Docker toolchain match the Maven target and validate the image in CI.

## 13. Common interview questions and answer outlines

### “What was the hardest part?”

> Coordinating durable progress with external side effects. Relay can persist run and step state, but an HTTP action and a database transaction are not atomic together. The idempotency key is the receiver-facing mechanism for absorbing replay; a production design should also define attempt states, atomic queue claims, and crash recovery.

### “How does a run survive a restart?”

> Run input, a workflow snapshot, current node, counters, and step outputs are persisted. On execution, the engine starts from the saved current node and rebuilds context from the trigger input and stored step outputs. The report documents an approval pause/restart/resume drill. I would qualify the guarantee: the current polling loop does not reclaim expired processing jobs, so restart recovery is incomplete for every crash point.

### “Why persist the workflow snapshot?”

> To make an in-progress run deterministic with respect to its graph. Editing a workflow should affect future runs, not silently change the path of an existing run.

### “How do you prevent duplicate side effects?”

> Relay derives a stable key from run, node, and sequence and sends it with side-effect requests. The external service must persist and enforce that key. This is replay protection, not a distributed exactly-once transaction; we need to examine crash windows and receiver behavior.

### “How does the approval flow work?”

> The approval node writes a pending approval, stores the next node on the run, marks it waiting, and returns. The decision endpoint records the decision and either requeues the run or cancels it. The next engine pass resumes from the saved node.

### “How are workflows validated?”

> At publish time, Relay checks required top-level fields, trigger types, webhook secret presence, node IDs, supported node types, required params, edge targets, template references, and whether sensitive nodes have an approval node somewhere in the graph.

**Important nuance:** this is not a full control-flow proof that approval dominates every sensitive action. At runtime, `order_action` checks whether the run has any approved approval record, rather than proving that a specific earlier gate governs that particular node (`PublishValidationService.java:101-107`; `EngineService.java:125-128`).

### “How does AI integration work?”

> The AI node resolves a prompt, sends it to a chat-completions endpoint, parses the response as JSON, validates it against a supplied JSON Schema, and retries once with validation feedback. Prompt/completion token counts are recorded when returned by the provider.

### “What is the current test coverage?”

> The repository has the Spring Boot test dependency but no test source files in this checkout. The README and verification report describe manual/smoke-test evidence, which I would distinguish from a reproducible automated test suite.

### “What would you improve first?”

> First make queue claim/recovery correct under concurrency and restart, because all execution depends on reliable work ownership. Then close the external side-effect crash window with a clearly specified idempotency/outbox approach and add failure-injection integration tests. I would next enforce advertised limits and improve authentication/secrets.

### “Why MySQL and Spring?”

> For a capstone, Spring Boot gives familiar HTTP, dependency injection, scheduling, and persistence conventions; MySQL offers durable relational state and indexing for runs and queue records. The trade-off is that the current simple polling design does not exploit database queue operations safely at scale.

## 14. Behavioral questions: describe your decisions, not just features

Use a concise **context → decision → trade-off → result/next step** structure:

- **Context:** A run can contain long-running steps and human decisions.
- **Decision:** Persist run progress and make the workflow a graph.
- **Trade-off:** The engine and state model are easy to understand, but queue ownership and restart semantics need stronger guarantees.
- **Next step:** Add atomic claims, recovery, and tests around process death.

Be ready to separate:

- What the repository implements now.
- What the README/catalog says it is intended to support.
- What the verification report says was manually demonstrated.
- What you would build next for production.

Avoid claiming distributed exactly-once, horizontal scalability, scheduled triggers, enforced timeouts, or comprehensive tests unless you can point to code and reproducible evidence for them.

## 15. A 30-minute preparation routine

1. Rehearse the one-minute introduction and the expense workflow walkthrough.
2. Trace `WorkflowController -> QueueJob -> EngineService -> RunStepEntity` in source.
3. Explain how an approval pauses and resumes a run.
4. Explain one success path and one failure/retry path.
5. Review the trade-offs above and pick two improvements you can defend.
6. Practice the exactly-once answer without overstating the guarantee.
7. Have file paths ready: engine, validation service, workflow and webhook controllers, approval controller, migration, config, and seed workflows.

## 16. Final interview close

> Relay demonstrates the central workflow-orchestration concepts: graph definitions, validation, durable run/step state, resumable approvals, retries, and receiver-facing idempotency. The key trade-off is that durable local state does not automatically make external side effects exactly once. My next steps would be atomic queue claiming and expired-lease recovery, explicit crash-safe side-effect semantics, limit enforcement, and integration tests that exercise concurrency and failure boundaries.
