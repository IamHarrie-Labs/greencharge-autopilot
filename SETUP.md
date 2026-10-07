# ENACT Hackathon Environment Setup & Student Guide

Welcome to the **ENACT Hackathon**! 🚀

This repository provides everything you need to bootstrap a local, production-grade **ENACT multi-node Kubernetes cluster** on your machine using [Kind (Kubernetes in Docker)](https://kind.sigs.k8s.io/). The cluster comes pre-installed with all core ENACT platform components, monitoring stacks, network observability tools, and application policy managers.

---

## Table of Contents

1. [Architecture & Stack Overview](#architecture--stack-overview)
2. [Prerequisites](#prerequisites)
3. [Cluster Setup](#cluster-setup)
4. [Verifying the Installation](#verifying-the-installation)
5. [Accessing Services & Dashboards](#accessing-services--dashboards)
   - [TDCME Monitor API (Swagger & Endpoints)](#1-tdcme-monitor-api)
   - [APPLPM API (Application Policy Manager)](#2-applpm-api)
   - [Grafana Dashboards](#3-grafana-dashboards)
   - [Prometheus Query UI](#4-prometheus)
   - [Cilium Hubble (Network Observability UI)](#5-cilium-hubble-ui)
6. [Authentication & API Tokens](#authentication--api-tokens)
7. [Working with Policies (APPLPM & RuntimePolicy)](#working-with-policies-applpm--runtimepolicy)
8. [Eclipse IDE & ENACT SDK Installation](#eclipse-ide--enact-sdk-installation)
9. [Cluster Cleanup & Teardown](#cluster-cleanup--teardown)
10. [Troubleshooting & Common Issues](#troubleshooting--common-issues)

---

## Architecture & Stack Overview

Your local Kind cluster (`enact-dev`) is configured as a **3-node cluster** (1 control-plane and 2 worker nodes).

```text
Host System (Your Machine)
 ├── Port 35554 ─────────► TDCME Monitor API (Telemetry & Metrics)
 ├── Port 35580 ─────────► APPLPM API (Application Policy Model)
 ├── Port-Forward 3000 ──► Grafana (Metrics & Energy Visualizations)
 ├── Port-Forward 9090 ──► Prometheus (PromQL Engine)
 └── Port-Forward 12000 ─► Hubble UI (Network & Service-to-Service Observability)
```

### Core Components Installed

| Component | Purpose | Access Method |
| :--- | :--- | :--- |
| **Cilium & Hubble** | eBPF-based high-performance CNI & network flow observability | Hubble UI via port-forward (`:12000`) |
| **Kube-Prometheus-Stack** | Metrics scraping, storage, alerting, and Grafana visualization | Grafana (`:3000`), Prometheus (`:9090`) |
| **Kepler** | Kubernetes Efficient Power Level Exporter (energy consumption metrics) | Scraped by Prometheus, visual in Grafana |
| **TDCME (Monitor API & Agent)** | Telemetry Data Collector & Monitoring Engine | Host Port `http://localhost:35554` |
| **APPLPM** | Application Policy Model & Runtime Policy Controller | Host Port `http://localhost:35580` |

---

## Prerequisites

Before starting, install the following tools on your host OS.

> [!IMPORTANT]
> Install tools from their **official documentation**. Avoid outdated package managers (like Ubuntu snap/older apt repositories) as they may lack required features or impose restrictive sandbox confinements.

1. **Docker Engine / Desktop**
   - Ensure Docker is installed and currently running:

     ```bash
     docker version
     ```

   - [Docker Installation Guide](https://docs.docker.com/engine/install/)
   - *Linux users:* Make sure your user is in the `docker` group (`sudo usermod -aG docker $USER`), or you can run docker without root.

2. **Kind (Kubernetes in Docker)**
   - Kind version `v0.20.0` or newer is recommended:

     ```bash
     kind --version
     ```

   - [Kind Installation Guide](https://kind.sigs.k8s.io/docs/user/quick-start/#installation)

3. **Kubectl**
   - Official Kubernetes CLI matching cluster version `v1.30+`:

     ```bash
     kubectl version --client
     ```

   - [Kubectl Installation Guide](https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/)

4. **Helm**
   - Helm version `v3.12+`:

     ```bash
     helm version
     ```

   - [Helm Installation Guide](https://helm.sh/docs/intro/install/)

5. **Make**
   - Standard build automation tool (`make` command). Pre-installed on macOS/Linux. On Windows, use WSL2 (Ubuntu recommended).

---

## Cluster Setup

To create the Kind cluster, install Cilium CNI, configure monitoring, and deploy all ENACT components, run a single command in the repository root:

```bash
make setup
```

### What `make setup` does

1. Creates a 3-node Kind cluster named `enact-dev` using `cluster-config/kind-config.yaml` with host port mappings (`35554` and `35580`).
2. Disables the default Kind CNI and installs **Cilium CNI 1.20.1** with Hubble UI and Prometheus metrics.
3. Sets up the `enact` namespace.
4. Deploys **kube-prometheus-stack** (Prometheus Operator, Alertmanager, Grafana, Node Exporters).
5. Deploys **Kepler** with Prometheus `ServiceMonitor` integration.
6. Installs **TDCME Monitor API** and the **TDCME Monitor Agent**.
7. Deploys the **APPLPM Controller Manager** and Custom Resource Definitions (CRDs).

---

## Verifying the Installation

After `make setup` finishes, verify that all nodes and pods are running:

### 1. Check Cluster Nodes

```bash
kubectl get nodes -o wide
```

You should see 3 nodes in `Ready` state:

- `enact-dev-control-plane`
- `enact-dev-worker`
- `enact-dev-worker2`

### 2. Check All ENACT Pods

```bash
kubectl get pods -n enact
```

All pods in the `enact` namespace should transition to `Running` (Ready: `1/1`, `2/2`, or `3/3` for Grafana):

```text
NAME                                                     READY   STATUS    RESTARTS   AGE
alertmanager-infra-kube-prometheus-stac-alertmanager-0   2/2     Running   0          5m
applpm-controller-manager-xxxxxxxxxx-xxxxx               1/1     Running   0          5m
infra-grafana-xxxxxxxxxx-xxxxx                           3/3     Running   0          5m
infra-kube-prometheus-stac-operator-xxxxxxxxxx-xxxxx     1/1     Running   0          5m
infra-kube-state-metrics-xxxxxxxxxx-xxxxx                1/1     Running   0          5m
infra-prometheus-node-exporter-xxxxx                     1/1     Running   0          5m
kepler-xxxxx                                             1/1     Running   0          5m
monitor-api-xxxxxxxxxx-xxxxx                             1/1     Running   0          5m
prometheus-infra-kube-prometheus-stac-prometheus-0       2/2     Running   0          5m
tdcme-agent-xxxxxxxxxx-xxxxx                             1/1     Running   0          5m
```

---

## Accessing Services & Dashboards

### 1. TDCME Monitor API

The Telemetry Data Collector & Monitoring Engine API is accessible directly on your host machine without needing port-forwarding (Depending on how docker is configured on your system the IP network may differ):

- **Swagger / OpenAPI Documentation:** [http://localhost:35554/docs](http://localhost:35554/docs)
- **Alternative Redoc:** [http://localhost:35554/redoc](http://localhost:35554/redoc)
- **Health Check:**

  ```bash
  curl http://localhost:35554/healthz
  ```

- **List Registered Clusters:**

  ```bash
  curl http://localhost:35554/clusters
  ```

---

### 2. APPLPM API

The Application Policy Model Controller HTTP server provides policy query and label assignment routes directly on host port `35580`:

- **Check Current Policies:**

  ```bash
  curl http://localhost:35580/api/v1/namespaces/enact/policies
  ```

- **Node Labels API:**

  ```bash
  # Check policy or labels for a node
  curl -X POST http://localhost:35580/api/v1/nodes/enact-dev-worker/labels
  ```

---

### 3. Grafana Dashboards

Grafana comes with pre-configured dashboards for cluster performance, nodes, and Kepler energy tracking:

1. **Start Port-Forwarding:**

   ```bash
   kubectl port-forward -n enact svc/infra-grafana 3000:80
   ```

2. **Retrieve the `admin` Password:**

   ```bash
   kubectl get secret --namespace enact infra-grafana -o jsonpath="{.data.admin-password}" | base64 -d && echo
   ```

3. **Open in Browser:** [http://localhost:3000](http://localhost:3000)
   - **Username:** `admin`
   - **Password:** *(the string printed by the command above)*

---

### 4. Prometheus

To run ad-hoc PromQL queries or inspect scraped metrics and targets:

1. **Start Port-Forwarding:**

   ```bash
   kubectl port-forward -n enact svc/infra-kube-prometheus-stac-prometheus 9090:9090
   ```

2. **Open in Browser:** [http://localhost:9090](http://localhost:9090)
   - Try PromQL queries such as:
     - `kepler_node_platform_joules_total` (Kepler energy metrics)
     - `container_cpu_usage_seconds_total` (Container CPU)
     - `kube_pod_status_phase` (Pod lifecycle)

---

### 5. Cilium Hubble UI

Hubble gives you a visual real-time service map and deep network flow tracking:

1. **Start Port-Forwarding:**

   ```bash
   kubectl port-forward -n kube-system svc/hubble-ui 12000:80
   ```

2. **Open in Browser:** [http://localhost:12000](http://localhost:12000)
   - Select the `enact` namespace to visualize network traffic between pods.

---

## Authentication & API Tokens

Secured endpoints on the TDCME Monitor API require Bearer Token authorization. These tokens are generated as Kubernetes secrets during deployment:

### 1. Retrieve the Admin Token

Use this token in Swagger UI (`Authorize` button) or in curl HTTP headers:

```bash
ADMIN_TOKEN=$(kubectl get secret admin-token-secret -n enact -o jsonpath="{.data.token}" | base64 -d)
echo "Admin Token: $ADMIN_TOKEN"
```

**Example authenticated API request:**

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:35554/infra-state
```

### 2. Retrieve the Agent Join Token

Used by external or edge clusters to join the central Monitor API:

```bash
JOIN_TOKEN=$(kubectl get secret join-token-secret -n enact -o jsonpath="{.data.token}" | base64 -d)
echo "Join Token: $JOIN_TOKEN"
```

---

## Working with Policies (APPLPM & RuntimePolicy)

The ENACT APPLPM controller monitors custom `RuntimePolicy` resources to enforce compute, memory, latency, and green energy constraints on workloads.

### Example `RuntimePolicy`

Create a file named `hackathon-policy.yaml`:

```yaml
apiVersion: enact.eu/v1alpha1
kind: RuntimePolicy
metadata:
  name: hackathon-app-policy
  namespace: enact
spec:
  appLabels:
    name: "hackathon"
  cpu:
    cores:
      min: 1
      max: 2
  memory:
    min: 100
    max: 150
    greenEnergy:
      minRatio: "0.2"
      mode: "Soft"
```

Apply the policy to the cluster:

```bash
kubectl apply -f hackathon-policy.yaml
```

Verify the policy is recognized by APPLPM:

```bash
kubectl get runtimepolicies.enact.eu -n enact
curl http://localhost:35580/api/v1/namespaces/enact/policies
```

---

## Eclipse IDE & ENACT SDK Installation

If you are developing or modeling using the **ENACT SDK**:

1. **Read the Full Installation Guide:**
   - Open the included HTML guide in your browser: [install-guide.html](docs/organisers/install-guide.html)
   - Or open the PDF version: `docs/organisers/ENACT SDK Installation Guide.pdf`
2. **Quick Summary:**
   - Install **Java 21+** and **Eclipse IDE for Java Developers (2025-12 / 4.38+)**.
   - Open Eclipse and navigate to **Help → Eclipse Marketplace…**.
   - Search for `ENACT` and install the **ENACT Software Development Kit** (or add update site: `https://pages.eclipse.dev/eclipse-research-labs/enact-project/software-development-kit/releases/latest/`).
   - Open **Window → Show View → Other… → ENACT SDK → SDK Control Panel**.

---

## Cluster Cleanup & Teardown

When you are finished or want to start fresh from scratch:

```bash
make clean
```

This will:

- Delete the Kind cluster and all running containers.
- Clean up the Helm repositories.
- Reclaim local Docker disk space.

---

## Troubleshooting & Common Issues

### 1. `docker: command not found` or Permission Denied

- Ensure Docker Desktop or Docker Engine is started.
- On Linux, if you see `permission denied while trying to connect to the Docker daemon socket`, run:

  ```bash
  sudo chmod 666 /var/run/docker.sock
  ```

  or log out and back in after running `sudo usermod -aG docker $USER`.

### 2. Port Conflict (`bind: address already in use` on 35554 or 35580)

- Kind maps `35554` (TDCME) and `35580` (APPLPM) on `0.0.0.0`.
- If another process is using these ports, identify it with:

  ```bash
  lsof -i :35554
  lsof -i :35580
  ```

- Terminate the conflicting process or modify the hostPort mappings in [cluster-config/kind-config.yaml](cluster-config/kind-config.yaml).

### 3. Architecture / `exec format error` on Linux x86_64

- If you see `exec format error` in pod logs for TDCME or APPLPM, run:

  ```bash
  docker run --privileged --rm tonistiigi/binfmt --install arm64
  ```

  This enables multi-architecture QEMU binary emulation for ARM64 containers.

### 4. Pods stuck in `Pending` or slow startup

- Kind downloads several Docker images on first run (~1-2 GB total). On slower connections, this may take a few minutes.
- Inspect the pod events to see download progress:

  ```bash
  kubectl describe pod <pod-name> -n enact
  ```

---

Good luck with your Hackathon project! If you have questions, refer to the [ENACT Eclipse Project](https://gitlab.eclipse.org/eclipse-research-labs/enact-project) documentation.
