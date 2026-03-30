# CLAUDE.md

## Project Description

### VIPLEV Agent

A lightweight component installed in the environment to be benchmarked. The agent communicates with VIPLEV via the REST API and receives instructions on which tasks to execute. To carry out these tasks, the agent requires administrative access to the underlying containerization layer — primarily Docker and Kubernetes.

The agent's responsibilities include:
- Monitoring resource usage for individual containers/pods and the overall system (CPU, memory, network, etc.)
- Starting and stopping load testing tools (e.g. K6) based on user-defined scenarios
- Simulating container failures by controlled shutdown of selected containers/pods

The communication flow is as follows: the user defines a test scenario via the UI or REST API, VIPLEV (another project) stores and coordinates the scenario, and the agent executes it in the associated environment. Test data is continuously collected by the agent and made available to the user through VIPLEV.

## OpenAPI Client Generation

The OpenAPI spec at `src/main/resources/openapi/outbound/openapi.yaml` defines the VIPLEV platform API — the API that the agent **calls as a client**, not an API it serves. The agent has no inbound business REST endpoints (only Spring Actuator endpoints for health checks).

The `openapi-generator` Gradle plugin (v7.11.0) generates a Java REST client using the `resttemplate` library:

- **Generated location:** `build/generated/openapi/src/main/java/`
- **API classes:** `dk.viplev.agent.generated.api` — one class per OpenAPI tag
- **DTOs:** `dk.viplev.agent.generated.model` — request/response objects
- **Invoker:** `dk.viplev.agent.generated.invoker` — REST client configuration and auth

Generation runs automatically before compilation (`compileJava.dependsOn openApiGenerate`). Never hand-edit files in `build/generated/` — update the OpenAPI YAML and regenerate instead.

Agent-relevant endpoints (tagged `Agent` in the spec):
- `registerServices` — register discovered containers with VIPLEV
- `listMessages` — poll for pending benchmark instructions
- `storeResourceMetrics` — send CPU/memory/network metrics
- `storePerformanceMetrics` — send K6 HTTP and VU metrics
- `updateBenchmarkRunStatus` — transition run state (STARTED, FINISHED, FAILED, STOPPED)

## Adding a New Container Runtime

1. Create a new adapter package: `adapter/outbound/container/<runtime>/`
2. Implement the `ContainerPort` interface from `port/outbound/container/`
3. Annotate the implementation with `@Profile("<runtime>")` and `@Component`
4. Set the `VIPLEV_RUNTIME` environment variable to activate the profile (this maps to `spring.profiles.active`)

Use the Docker adapter at `adapter/outbound/container/docker/` as a reference implementation. Only one container runtime adapter is active at a time, selected by the Spring profile.

## Key Architectural Decisions

- **Hexagonal architecture** — Domain logic in `domain/` has zero dependencies on Spring, Docker SDK, or HTTP. All infrastructure access goes through port interfaces, allowing adapters to be swapped independently.
- **Agent-as-client (no inbound business HTTP)** — The agent polls VIPLEV for instructions rather than exposing business REST endpoints. The only inbound adapter is scheduling (timers). Actuator endpoints (`/actuator/health`) are exposed for liveness probes but carry no business logic. This simplifies deployment — no ingress or port-forwarding needed.
- **OpenAPI-generated outbound client** — Code generation from the VIPLEV OpenAPI spec ensures type safety and keeps the agent in sync with the platform API contract. The spec is the single source of truth.
- **Spring profiles for runtime selection** — `@Profile("docker")` / `@Profile("kubernetes")` cleanly separates adapter wiring. Only one container adapter is loaded at a time, selected by `VIPLEV_RUNTIME`.
- **SQLite with Liquibase** — An embedded database provides metric buffering and state persistence without requiring an external DB server. Liquibase manages schema migrations via `src/main/resources/db/changelog/`.
- **Actuator health endpoint** — Exposed at `/actuator/health` for Docker `HEALTHCHECK` and Kubernetes liveness probes. The agent is otherwise headless.
