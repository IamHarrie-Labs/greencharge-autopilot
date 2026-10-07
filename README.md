<p><img src="docs/brand/wordmark.svg" alt="GreenCharge Autopilot" width="420"></p>

# GreenCharge Autopilot

**Veles Hack 2026 · Challenge 3 (ENACT): Kubernetes Dynamic Adaptation**

ENACT can already say what a workload *should* look like: the Application
Controller recommends `scale_up` or `scale_down`, and the policy operator picks
a node. Nothing then changes the running workload, and in the hackathon cluster
the operator could not decide anything at all.

This entry closes that loop for GreenCharge, the challenge's EV-charger routing
app:

> **ENACT detects a policy violation, explains its decision, applies an
> adaptation, verifies the application recovered, and rolls back if it did not.**

Everything below was run on the challenge's own 3-node kind cluster
(`make setup`), and every adaptation leaves an evidence trail you can inspect.

---

## Results on the live cluster

Recorded on 7 October 2026; raw records in [docs/evidence/](docs/evidence/) (`run1`, `run2`, `run3`, JSON lines, plus the RuntimePolicy status and pods at the end of each run).

| # | Scenario | What happened | Result |
|---|---|---|---|
| 0 | **ENACT operator decides** (after [operator-fix](operator-fix/)) | RuntimePolicy reads live TDCME metrics for every node and chooses `enact-dev-worker2`; the control plane is listed as rejected with `node region "" not in allowed regions` | decision made (before: `no available metrics`) |
| 1 | **GreenCharge starts out of policy**: CPU limit 500m (policy minimum 1 core), on a node the operator did not choose | The AC recommends *"Need to increase CPU from 0.50 cores to 1 cores"*; the autopilot raises the limit to 1 core and moves the pod to `enact-dev-worker2`; rollout verified, health check 55 ms | **adapted and verified** in 105 s |
| 2 | **A move outside the Hard region** is requested | Guard: *Hard location rule: region "us-east" is not in [eu-west]* | **rejected**, workload unchanged |
| 3 | **An adaptation that cannot become healthy** (64Gi memory, more than any node has) | The new pod cannot be scheduled; verification times out after 45 s and the autopilot reverts **only** the memory fields it changed. The earlier CPU adaptation (500m → 1) stays in place. Health check 38 ms afterwards | **rolled back** in 100 s |
| 4 | **A node leaves `eu-west`** (worker2 relabelled `us-east`) | The operator re-decides to `enact-dev-worker` and records why worker2 is now rejected; the autopilot moves GreenCharge, rollout verified, health check 17 ms. When worker2 returns to `eu-west`, the operator keeps its choice: no flapping | **adapted and verified** in 105 s |
| 5 | **Policy restricted to the edge role** (`nodeSelector: enact.eu/role: edge`, as the brief places GreenCharge on `enact-dev-worker`) | The operator re-decides to `enact-dev-worker`; the autopilot moves GreenCharge there and restores the 1-core minimum that the Helm upgrade had reset; health check 19 ms | **adapted and verified** in 97 s |

The evidence timeline for one adaptation, as the autopilot recorded it:

```text
08:18:35 A1 DETECT   workload deviates from policy
08:18:35 A1 DECIDE   cpu-limit 500m -> 1; node enact-dev-worker -> enact-dev-worker2
08:18:35 A1 GUARD    passed
08:18:36 A1 APPLY    patched deployment greencharge
08:20:01 A1 VERIFY   rollout complete and health check answered in 55 ms
08:20:20 A1 OUTCOME  adapted and verified
```

Each record also carries its data: the measured limits and usage, the full
Application Controller recommendation, the operator's decision and reason, and
the before/after state.

## The ENACT SDK, scripted

The ENACT SDK's Eclipse modules are a UI over the ENACT APM libraries on Maven
Central (*"the same validated code runs inside and outside Eclipse"*, SDK
README). [`sdk-workflow/`](sdk-workflow/) calls those exact libraries, at the
versions SDK 1.5.0 bundles, so every SDK step of the brief is reproducible from
one command:

| Brief step | SDK module → library | Command | Result |
|---|---|---|---|
| Package the app (Helm chart: image, port 8080, service, ingress `greencharge.local`) | Application Packaging → `eu.enact-horizon:app-packaging:1.0.0` | `make sdk-package` | Chart + manifests in [`greencharge/enact-sdk-generated/`](greencharge/enact-sdk-generated/); passes a server-side dry run against the live cluster |
| Write the RuntimePolicy | Application Policies → `eu.enact-horizon:application-policy-model:0.1.0` | `make sdk-validate-policy` | `CRD enact.eu/v1alpha1 RuntimePolicy: VALID` |
| Connect to the Data & Object Space | Dataspaces → `eu.enact-horizon:edc-client:1.2.0` | `make sdk-dataspace` | detect → catalog → negotiate (**FINALIZED**) → transfer (**COMPLETED**) → download; log in [`docs/evidence/dataspace/5-sdk-edc-client-run.txt`](docs/evidence/dataspace/5-sdk-edc-client-run.txt) |
| Use the Application Controller | `eu.enact-horizon:application-controller:1.0.0` | `make test` | compliance + `scale_up`/`scale_down` driving the autopilot |

One finding from doing this: the packaging library always probes `/health`
on the container port, whatever probe path is requested, and sets no startup
delay. GreenCharge had no `/health`, so a generated chart restarts it in a loop
(the crash loop other teams worked around by editing the chart). GreenCharge
now serves `/health`, so the generated chart works unmodified.

## What was built

| | Challenge task | What it does |
|---|---|---|
| `greencharge/src/main/resources/reconciliation/policymodel.yml` | 2. Policy model | x86_64, 1–4 cores, 2Gi DDR4, max 100 W, green mix ≥ 0.60, region `eu-west`, latency ≤ 50 ms |
| `compliance/` | 3. Application Controller | `PolicyCompliance` runs the ENACT `ComplianceAndAdaptationService` on the policy model. Spring wires the AC's own `PolicyModelConfig` (no reflection into private fields). |
| `autopilot/` | 3 + 5. Adaptation | A control loop in its own Deployment (so restarting GreenCharge never interrupts it): **detect → decide → guard → apply → verify → roll back**, every step recorded. |
| `chart/templates/runtimepolicy.yaml` | 4. RuntimePolicy | Soft green ≥ 0.6, **Hard** region `eu-west`, node availability ≥ 0.9 |
| `chart/templates/autopilot.yaml` | 5. Deploy | Autopilot Deployment with least-privilege RBAC: it may patch only the `greencharge` Deployment |
| `operator-fix/` | 5. Placement | Patch to the ENACT operator so it can read metrics and make (and explain) a decision. See [operator-fix/README.md](operator-fix/README.md). |
| `chart/templates/deployment.yaml` | | Startup probe: a JVM on half a core takes over a minute to start and was being killed by liveness |
| `SecurityConfig.java` | | The AC dependency silently enables Spring Security; every endpoint, including health probes, answered 401 |

### The control loop

```text
                    every 20 s
   ┌──────────────────────────────────────────────────────────────┐
   │ DETECT   live limits, usage, node and health of GreenCharge  │  Kubernetes API, metrics-server
   │ DECIDE   ENACT Application Controller recommendation         │  policymodel.yml
   │          + ENACT operator's chosenNode for the RuntimePolicy │  RuntimePolicy status (patched operator)
   │ GUARD    target node must satisfy the policy's Hard rules;   │
   │          no second adaptation within the cooldown            │
   │ APPLY    patch the Deployment, snapshot the old pod template │
   │ VERIFY   rollout complete + health URL answers 200,          │
   │          pods actually on the target node                    │
   │ ROLLBACK restore the snapshot if VERIFY times out            │
   └──────────────────────────────────────────────────────────────┘
          every step → evidence timeline (/autopilot.html, JSON, logs)
```

What the loop acts on:

- **CPU / memory limits**: the Application Controller compares the container's
  limits with the policy model and returns `scale_up`/`scale_down` with a
  target; the autopilot applies the target and keeps requests ≤ limits.
- **Placement**: when the pods are not on the operator's `chosenNode`, the
  autopilot pins them there (`kubernetes.io/hostname` node selector), but only
  if that node passes the RuntimePolicy's Hard rules.

## Run it

Prerequisites as in [SETUP.md](SETUP.md) (Docker, kind, kubectl, Helm, make),
plus JDK 21 and Maven to build the app.

```bash
make setup          # the organisers' ENACT cluster (see SETUP.md)
make labels         # node labels, in case APPLPM was not ready during setup
make test           # 11 unit tests, including the two required by the challenge
make image          # build GreenCharge and load it into kind
make operator-fix   # build the patched ENACT operator from upstream + patch, give it the TDCME token
make deploy         # GreenCharge + RuntimePolicy + autopilot (Helm)
make autopilot-ui   # http://localhost:8090/autopilot.html
```

The three scenarios from the results above:

```bash
kubectl -n enact port-forward svc/greencharge-autopilot 8090:8080 &

# 1. A move outside eu-west is refused by the guard
kubectl label node enact-dev-control-plane enact.eu/region=us-east --overwrite
curl -X POST "localhost:8090/autopilot/drills/move?node=enact-dev-control-plane"

# 2. An adaptation that cannot become healthy is rolled back
curl -X POST "localhost:8090/autopilot/drills/failed-adaptation?verifySeconds=45"

# 3. A node leaves eu-west: the operator re-decides, the autopilot moves the app
kubectl label node enact-dev-worker2 enact.eu/region=us-east --overwrite
```

`make evidence` prints every evidence record as JSON lines.

## Findings worth reporting upstream

1. **The ENACT operator could not place anything** (401 from TDCME, no token
   setting; empty metrics config) and **re-ranked on every pass** under a Hard
   location rule. Fixed in [operator-fix/](operator-fix/), with tests; ready
   to send as a merge request.
2. **`application-controller` enables Spring Security** for any app that uses
   it, which breaks Kubernetes probes until a security configuration is added.
3. **The operator only decides; it never moves a workload.** Its decision is
   also "sticky": a greener node does not trigger a move while the current one
   still passes the Hard rules. The autopilot applies decisions; it does not
   second-guess them.
4. `SETUP.md` (organisers' guide) listed the APPLPM port as 35080; kind maps
   it to **35580**. Corrected.

## What the cluster taught us (and what changed because of it)

These came out of running the autopilot on the live cluster, not from planning:

- **Rollback must undo only its own change.** The first version restored the
  whole saved pod template. During a `helm upgrade` an older autopilot pod was
  still verifying an earlier adaptation; when that timed out, its rollback
  reverted Helm's new image as well. Rollback now reverts only the fields the
  adaptation touched, and refuses to roll back at all if the Deployment's
  generation moved since the patch (another actor changed it). Covered by
  `rollbackRevertsOnlyTheFieldsTheAdaptationChanged`.
- **"Replicas == 1" is the wrong success test.** On a loaded node an old pod
  can take minutes to finish terminating, so the Deployment reports 2 pods
  long after the new one serves traffic, and verification timed out on
  adaptations that had worked. Success is now: spec observed, new ReplicaSet
  available (`Progressing=NewReplicaSetAvailable`), health URL answers 200,
  and the pods actually carry the change. `availableReplicas` alone is not
  enough because it counts old pods too.
- **The API server is the first verifier.** A drill that asked for a 64Gi
  memory request above a 2Gi limit was rejected outright; the autopilot used to
  surface that as an HTTP 500. A rejected patch is now recorded as
  `not_applied` with the API server's reason, workload unchanged.

## Honest limits

- **Dataspace (Task 1)**: every step runs through the SDK's EDC client
  (`make sdk-dataspace`): the consumer connector is detected (API v2, push
  only), the provider's catalog offers `grid-carbon-intensity`, our contract
  negotiation **FINALIZED**, and the transfer **COMPLETED** with the provider
  pushing to the organisers' transfer relay. Downloading from that relay is the
  one step that fails: on 7 October it answered every request, including its
  own `/health`, with `400 Client sent an HTTP request to an HTTPS server`
  (records in [docs/evidence/dataspace/](docs/evidence/dataspace/)); reported to
  the mentor. Until it is fixed GreenCharge reads its built-in mock carbon data;
  `make sdk-dataspace` saves the file to `docs/evidence/dataspace/` and
  pointing `carbon.feed.file` at it is the only remaining step (the app re-reads
  the file on every request).
- **Measured vs declared**: CPU/memory limits, usage, node placement and health
  latency are measured live. Link bandwidth is *declared* (1000 Mbps) because
  kind's virtual links cannot be measured meaningfully; the policy model's
  100 Mbps floor is therefore not a real test.
- **Kepler energy** on a laptop under WSL2 is estimated by Kepler's model, not
  read from hardware counters; this entry does not claim energy savings.
- **Evidence** is kept in memory (last 500 records) and in the autopilot's
  logs, not in durable storage.
- **One replica** of GreenCharge; the verify step's "rollout complete" check is
  written for N replicas but was only exercised with one.

## Licence

Apache-2.0 (see [LICENSE](LICENSE)), as the ENACT components it builds on.
