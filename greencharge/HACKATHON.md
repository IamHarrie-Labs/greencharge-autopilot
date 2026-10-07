# GreenCharge, the ENACT Hackathon Challenge

A smart city routes EV drivers to the **greenest available charger**. Grid carbon intensity swings hour by hour, so the greenest charger changes, but the live carbon feed belongs to the energy **utility** and is shared under a sovereign **dataspace contract**. Furthermore, the service must run **in-region** on **green-powered compute nodes** governed by declarative policies.

Your mission is to take GreenCharge from a local mock into a policy-aware, self-adapting, cloud-native application using the **ENACT SDK** and the **Application Controller (AC)** library.

---

## 🚀 Quickstart

Run GreenCharge locally:

```bash
cd greencharge
mvn clean test
mvn spring-boot:run
```

The app starts on `http://localhost:8080` and **opens the frontend in your
browser automatically**. You'll see per-district carbon bars and a recommended
charger. Until you complete Step 1, it uses a built-in **mock** carbon feed
(the badge says so).

## The API

| Method | Path        | Returns                                                |
|--------|-------------|--------------------------------------------------------|
| GET    | `/chargers` | The charger fleet with availability                    |
| GET    | `/carbon`   | Per-district carbon intensity + green score + source   |
| POST   | `/route`    | The greenest available charger + the full ranking      |

## The six ENACT steps

| # | Capability      | What you do                                                                                     |
|---|-----------------|-------------------------------------------------------------------------------------------------|
| 1 | Dataspaces      | Consume the utility's `grid-carbon-intensity` asset; the transfer drops a carbon file on your machine, so set `carbon.feed.file` to its path. |
| 2 | Packaging       | Generate a Helm chart: image `greencharge:1.0`, port 8080, service, ingress `greencharge.local`. |
| 3 | App Controller  | Inject **Energy efficiency + Elasticity + Load balancing** modules into the pom.                |
| 4 | Policies        | Author a RuntimePolicy: **Soft** green ≥ 0.6, **Hard** region `eu-west`, availability 0.9.      |
| 5 | Deploy          | Deploy the chart + policy; the operator places the pod.                                          |
| 6 | Monitor         | Trigger a load burst; watch **Energy** + **LoadBalancer** dashboards react.                     |

Every step has a **UI wizard** path and an equivalent **AI assistant** path
(`generate_deployment`, `configure_app_controller`, `generate_runtime_policy`).
Bonus for completing at least one step via the AI assistant.

## The one line you edit

In `src/main/resources/application.yml`:

```yaml
carbon:
  feed:
    file: REPLACE_ME   # <- path to the carbon file transferred in Step 1
```

The transferred file is JSON, a map of district to carbon intensity (gCO₂/kWh):

```json
{ "Riverside": 95, "Uptown": 180, "OldTown": 300, "Harbor": 150 }
```

The instant that file is wired in, the page's picks change (here Harbor becomes
greener than Uptown). That "aha" is the payoff of the dataspace step, and it
costs you zero code.

## 📋 The Challenge Tasks

### Task 1: Connect the Live Dataspace Feed (ENACT SDK)

1. In the **ENACT SDK Control Panel** (Eclipse IDE), navigate to the **Dataspaces** wizard.
2. Discover and consume the utility's `grid-carbon-intensity` data asset.
3. The transfer outputs a JSON file on your machine mapping municipal districts to real-time carbon intensity (in $\text{gCO}_2/\text{kWh}$):

   ```json
   { "Riverside": 95, "Uptown": 180, "OldTown": 300, "Harbor": 150 }
   ```

4. Configure the file path in `src/main/resources/application.yml`:

   ```yaml
   carbon:
     feed:
       file: /path/to/transferred/grid-carbon-intensity.json
   ```

5. Refresh the web UI: the badge turns **Live** and the greenest charger selection updates dynamically.

---

### Task 2: Specify the Application Policy Model (APM)

In the ENACT framework, **runtime policies are fed directly through the Application Policy Model (APM)**. Developers define high-level performance, infrastructure, and green energy targets in the policy model, from which the platform derives scheduling constraints, compliance checks, and adaptation rules.

Configure the Application Policy Model (`src/main/resources/reconciliation/policymodel.yml`) to define the operational requirements for GreenCharge:

- **Compute & Performance**:
  - CPU architecture: `x86_64`, with core boundaries set to `min: 1` and `max: 4`
  - Memory capacity: `2Gi` (DDR4)
- **Energy & Sustainability**:
  - Maximum power consumption: `100W`
  - **Green Energy Mix**: Set to a minimum ratio of `0.60` (at least 60% renewable energy)
- **Placement**:
  - Target Region: `eu-west`

---

### Task 3: Extend GreenCharge with the Application Controller (AC)

Enhance GreenCharge to make it self-aware of its host infrastructure by integrating the **ENACT Application Controller (`application-controller.jar`)**.

Rather than relying purely on static configurations, the application should dynamically evaluate host node metrics against the Application Policy Model and provide runtime adaptation recommendations.

1. **Verify Dependency**:
   Ensure `eu.enact-horizon:application-controller:1.0.0` is present in `pom.xml`.
2. **Develop the Adaptation / Recommendation Logic**:
   Extend the application with a service or endpoint that:
   - Loads the Application Policy Model using the AC configuration classes.
   - Evaluates incoming node telemetry (`ResourceMetrics`) against the loaded policy rules using the AC compliance service.
   - Produces an `AdaptationRecommendation` detailing whether the evaluated node is compliant and what action should be triggered (e.g. `scale_up`, `scale_down`, or `no_action`).
3. **Key AC Classes to Leverage**:
   - `PolicyModelConfig`: Loads and initializes the Application Policy Model.
   - `ComplianceAndAdaptationService`: Executes compliance checks and generates adaptation actions.
   - `ResourceMetrics`: Data transfer object representing the node's CPU, Memory, and Network state.
   - `AdaptationRecommendation`: Contains the compliance evaluation and recommended adaptation actions.
4. **Validation via Unit Tests**:
   Implement or run unit tests verifying that:
   - A properly provisioned node (e.g. 2 cores, 2Gi RAM, $\le$ 50ms latency) is identified as compliant with `no_action`.
   - An under-provisioned node (e.g. CPU < 1 core) is identified as non-compliant and recommends `scale_up`.

---

### Task 4: Package and Deploy via ENACT SDK

1. In the **ENACT SDK Control Panel**, use the **Packaging** tool to generate a Helm chart:
   - Image: `greencharge:1.0`
   - Container Port: `8080`
   - Ingress: `greencharge.local`
2. Deploy the application to your local Kind cluster using the Helm chart and the runtime policies fed from your Application Policy Model.
3. Observe scheduling: The ENACT operator places the pod on `enact-dev-worker` (green-compliant node) and enforces policy rules.
4. Monitor the app in the TDCME and Grafana dashboards (`http://localhost:3000`) under simulated traffic.

---

## 🏆 Definition of Done

- [ ] **Dataspace Connected:** Live utility carbon intensity flows into GreenCharge, updating the UI badge and charger rankings.
- [ ] **Policy Specified:** `policymodel.yml` defines CPU, RAM, Latency, and Green Energy Mix requirements.
- [ ] **AC Extension Developed:** The application integrates the AC library to evaluate node metrics and return adaptation recommendations.
- [ ] **Tests Pass:** All unit tests pass with `mvn clean test`.
- [ ] **Cluster Deployment:** Application packaged and deployed to the cluster with policies enforced.
