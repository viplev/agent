# Plan: Multi-node metric collection via cadvisor og node_exporter (#26)

## Context

Agenten læser i dag `/proc` direkte og bruger Docker stats API for metrics. Det virker for single-node Docker, men ikke for multi-node (Swarm/K8s). Issue #26 introducerer cadvisor og node_exporter som standard datakilde — selv for single-node.

**Afgrænsning mod #10:** Issue #10 ejer hele collection/buffer/flush-flowet (MetricCollectorAdapter, ScheduledExecutorService, SQLite buffering, flush til VIPLEV). #26 leverer kun den infrastruktur og de porte som #10 kalder ind i.

## Dependency graph

```
Task 1: Exporter Lifecycle (network + container mgmt)
   ├──► Task 2: Node Exporter Scraper    (kan paralleliseres med Task 3)
   ├──► Task 3: Cadvisor Scraper         (kan paralleliseres med Task 2)
   │
   └──► Task 4: Node Discovery (afhænger af Task 2 for machineId)
              │
              ▼
         Task 5: OpenAPI Schema Changes (multi-host DTOs)
              │
              ▼
         Task 6: Fjern Legacy Code (ProcFileReader, HostInfoProviderImpl)
```

## Open questions — svar

**Prometheus parser library:** `io.prometheus:prometheus-metrics-exposition-formats` (officiel Prometheus Java-klient).

**Cadvisor API:** JSON REST — `GET /api/v2.0/stats/?type=docker&count=60`. `RestTemplate` + Jackson er nok.

---

## Task 1: Docker Network og Exporter Lifecycle Management

**Formål:** Agent opretter `viplev_agent` Docker-netværk og starter cadvisor + node_exporter ved startup. Cleanup ved shutdown.

### Ny fil: `ExporterLifecycleManager.java`

**Placering:** `adapter/outbound/container/docker/ExporterLifecycleManager.java`
**Annotationer:** `@Component @Profile("docker") @Slf4j`
**Dependencies:** `DockerClient` (injected — allerede defineret i `DockerConfig.java`)

**Konfiguration via constructor injection:**
- `@Value("${agent.cadvisor-image}")` → cadvisor image name
- `@Value("${agent.node-exporter-image}")` → node_exporter image name

**Startup (`@EventListener(ApplicationReadyEvent.class)`):**
1. Opret `viplev_agent` bridge-netværk via `dockerClient.createNetworkCmd().withName("viplev_agent").withDriver("bridge").exec()` — catch "already exists" og ignorer
2. Start cadvisor container:
   - Image: konfigureret via property
   - Container name: `viplev-cadvisor`
   - Volume binds (read-only): `/:/rootfs:ro`, `/var/run:/var/run:ro`, `/sys:/sys:ro`, `/var/lib/docker:/var/lib/docker:ro`
   - Network: `viplev_agent`
   - Privileged: true (cadvisor kræver det for fuld metrics-adgang)
3. Start node_exporter container:
   - Image: konfigureret via property
   - Container name: `viplev-node-exporter`
   - Volume binds (read-only): `/proc:/host/proc:ro`, `/sys:/host/sys:ro`, `/:/rootfs:ro`
   - Network: `viplev_agent`
   - Command args: `--path.procfs=/host/proc`, `--path.sysfs=/host/sys`, `--path.rootfs=/rootfs`
4. For begge containere: tjek først om en container med samme navn allerede kører (idempotent). Hvis ja, skip.

**Shutdown (`@PreDestroy`):**
1. Stop og fjern `viplev-cadvisor` container (ignorer fejl hvis allerede stoppet)
2. Stop og fjern `viplev-node-exporter` container (ignorer fejl)
3. Fjern `viplev_agent` netværk (ignorer fejl hvis stadig i brug)

**Vigtige docker-java API calls:**
- `dockerClient.createNetworkCmd()` — opret netværk
- `dockerClient.createContainerCmd(image).withName(name).withHostConfig(...)` — opret container med navn
- `dockerClient.removeContainerCmd(id).withForce(true)` — fjern container
- `dockerClient.removeNetworkCmd(networkId)` — fjern netværk
- `dockerClient.listContainersCmd().withNameFilter(List.of("viplev-cadvisor")).withShowAll(true)` — tjek om container eksisterer

**Bemærk:** Vi bruger IKKE `ContainerPort.startContainer()` her. `ContainerPort` er en domæne-port beregnet til at starte bruger-containere (K6 etc.). Exporter-lifecycle er ren infrastruktur og bør bruge `DockerClient` direkte — det holder domænet rent.

**Container navngivning:** Alle containere agenten spinner op prefixes med `viplev-` (fx `viplev-cadvisor`, `viplev-node-exporter`) så de er nemme at identificere og evt. manuelt rydde op hvis agenten ikke lukkede ned pænt. Der er ingen automatisk cleanup-on-startup — brugeren forventes at stoppe agenten pænt via `docker stop`. `@PreDestroy` er best-effort cleanup.

### Fil der ændres: `application.properties`

Tilføj:
```properties
# Exporter images
agent.cadvisor-image=${VIPLEV_CADVISOR_IMAGE:gcr.io/cadvisor/cadvisor:v0.51.0}
agent.node-exporter-image=${VIPLEV_NODE_EXPORTER_IMAGE:prom/node-exporter:v1.9.0}
```

### Test: `ExporterLifecycleManagerTest.java`

**Placering:** `test/.../adapter/outbound/container/docker/ExporterLifecycleManagerTest.java`
**Pattern:** Mockito + JUnit5 (samme stil som `DockerContainerAdapterTest`)

Test cases:
- `startup_createsNetworkAndStartsContainers` — verificer at createNetworkCmd, createContainerCmd (×2), startContainerCmd (×2) kaldes med korrekte parametre
- `startup_skipsContainerIfAlreadyRunning` — mock listContainersCmd til at returnere eksisterende container → verificer at createContainerCmd IKKE kaldes
- `startup_handlesNetworkAlreadyExists` — mock createNetworkCmd til at kaste "already exists" → verificer at det ignoreres
- `shutdown_stopsAndRemovesContainersAndNetwork` — verificer at removeContainerCmd (×2) og removeNetworkCmd kaldes
- `shutdown_handlesAlreadyRemovedContainers` — mock removeContainerCmd til at kaste exception → verificer at det ignoreres og cleanup fortsætter

### Verifikation

```bash
./gradlew test --tests '*ExporterLifecycleManagerTest'
./gradlew compileJava
```

---

## Task 2: Node Exporter Scraper

**Formål:** Port + adapter til at scrape node_exporter `/metrics` og parse Prometheus text format.

**Filer der oprettes:**
- `port/outbound/metrics/NodeExporterPort.java` — interface: `HostStats scrapeHostStats(String baseUrl)`, `String scrapeMachineId(String baseUrl)`, `HostDTO scrapeHostInfo(String baseUrl)`
- `adapter/outbound/metrics/nodeexporter/NodeExporterAdapter.java` — `@Component @Profile("docker")`
- `adapter/outbound/metrics/nodeexporter/PrometheusTextParser.java` — wrapper

**Metrics der parses:**
- `node_cpu_seconds_total` (per mode) → CPU usage %
- `node_memory_MemTotal_bytes`, `node_memory_MemAvailable_bytes` → memory
- `node_network_receive_bytes_total`, `node_network_transmit_bytes_total` → network
- `node_disk_read_bytes_total`, `node_disk_written_bytes_total` → disk
- `node_os_info{machine_id}` → machineId
- `node_uname_info` → hostname, OS info

**Dependency i build.gradle:** `implementation 'io.prometheus:prometheus-metrics-exposition-formats:1.3.5'`

**Test:** Unit tests med fixture fil (`test/resources/fixtures/node_exporter_sample.txt`).

---

## Task 3: Cadvisor Scraper

**Formål:** Port + adapter til at scrape cadvisor JSON REST API for container-metrics.

**Filer der oprettes:**
- `port/outbound/metrics/CadvisorPort.java` — interface: `Map<String, List<TimestampedContainerStats>> scrapeContainerStats(String baseUrl)`
- `adapter/outbound/metrics/cadvisor/CadvisorAdapter.java` — `@Component @Profile("docker")`
- `adapter/outbound/metrics/cadvisor/CadvisorResponse.java` — Jackson POJOs
- `domain/model/TimestampedContainerStats.java` — record med timestamp

**Test:** Unit tests med JSON fixture fil (`test/resources/fixtures/cadvisor_stats_sample.json`).

---

## Task 4: Node Discovery

**Formål:** Discover noder via Docker API. Resolve machineId per node via node_exporter.

**Filer der oprettes:**
- `port/outbound/discovery/NodeDiscoveryPort.java` — interface: `List<NodeInfo> discoverNodes()`
- `domain/model/NodeInfo.java` — record: `nodeId, hostname, machineId, ipAddress, role`
- `adapter/outbound/discovery/docker/DockerNodeDiscoveryAdapter.java` — `@Component @Profile("docker")`, Swarm + standalone fallback

**Afhængighed:** Task 2 (bruger `NodeExporterPort.scrapeMachineId()`).

**Test:** Unit tests med mocked DockerClient.

---

## Task 5: OpenAPI Schema Changes (breaking)

**Formål:** DTOs fra single-host til multi-host `hosts[]` array.

**Ændringer i `openapi.yaml`:**

`ServiceRegistrationDTO` → `hosts: ServiceRegistrationHostDTO[]`

```yaml
ServiceRegistrationDTO:
  type: object
  required: [hosts]
  properties:
    hosts:
      type: array
      items:
        $ref: '#/components/schemas/ServiceRegistrationHostDTO'

ServiceRegistrationHostDTO:  # NY
  type: object
  required: [host, services]
  properties:
    host:
      $ref: '#/components/schemas/HostDTO'
    services:
      type: array
      items:
        $ref: '#/components/schemas/ServiceDTO'
```

`MetricResourceDTO` → `hosts: MetricResourceNodeDTO[]`

```yaml
MetricResourceDTO:
  type: object
  required: [hosts]
  properties:
    hosts:
      type: array
      items:
        $ref: '#/components/schemas/MetricResourceNodeDTO'

MetricResourceNodeDTO:  # NY
  type: object
  required: [host, services]
  properties:
    host:
      $ref: '#/components/schemas/MetricResourceHostDTO'
    services:
      type: array
      items:
        $ref: '#/components/schemas/MetricResourceServiceDTO'
```

**Andre filer der ændres:**
- `ServiceDiscoveryServiceImpl.java` — byg `hosts[]` via `NodeDiscoveryPort`
- Tests opdateres

**Test:** `./gradlew openApiGenerate compileJava` + opdaterede unit tests.

---

## Task 6: Fjern Legacy Code

**Formål:** Slet kode erstattet af cadvisor/node_exporter.

**Filer der slettes:**
- `ProcFileReader.java` + tests
- `HostInfoProviderImpl.java` + tests

**Filer der ændres:**
- `ContainerPort.java` — fjern `getHostStats()` (host stats kommer fra `NodeExporterPort`)
- `DockerContainerAdapter.java` — fjern `ProcFileReader` dep, `/proc`-parsing, `getHostStats()` impl
- `DockerConfig.java` — fjern `ProcFileReader` bean
- `HostInfoProvider.java` — ny adapter backed af `NodeExporterPort`, eller fjern helt

**Test:** `./gradlew test` — alt skal passere.

---

## Verifikation (end-to-end)

1. `./gradlew build` kompilerer og tests passer
2. Start agent → cadvisor + node_exporter containere kører, `viplev_agent` netværk oprettet
3. Node exporter scraper returnerer korrekte host-metrics
4. Cadvisor scraper returnerer korrekte container-metrics
5. Service registration sender multi-host `hosts[]` struktur
6. Stop agent → eksporter-containere og netværk fjernes
