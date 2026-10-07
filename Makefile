.ONESHELL:
setup:
	echo "Creating kind cluster..."
	kind create cluster --config=cluster-config/kind-config.yaml

	echo "Setting context to kind cluster..."
	kubectl config use-context kind-enact-dev

	kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

	echo "Installing Cilium CNI"
	helm repo add cilium https://helm.cilium.io/ --force-update
	helm repo add prometheus-community https://prometheus-community.github.io/helm-charts   --force-update
	helm repo add enact-tdcme https://gitlab.eclipse.org/api/v4/projects/8265/packages/helm/stable --force-update
	helm repo add enact-applpm https://gitlab.eclipse.org/api/v4/projects/8268/packages/helm/stable --force-update
	helm repo add kepler https://sustainable-computing-io.github.io/kepler-helm-chart --force-update

	helm repo update
	helm install cilium cilium/cilium --namespace kube-system --version 1.20.1 \
	--set cluster.name="cloud1" \
	--set cluster.id=1 \
	--set hubble.enabled=true \
	--set hubble.tls.enabled=false \
	--set hubble.relay.enabled=true \
	--set hubble.ui.enabled=true \
	--set hubble.metrics.enableOpenMetrics=true \
	--set hubble.metrics.enabled="{dns,drop,tcp,flow,port-distribution,icmp,httpV2:exemplars=true;labelsContext=source_ip\,source_namespace\,source_workload\,destination_ip\,destination_namespace\,destination_workload\,traffic_direction}" \
	--set global.hubble.enabled=true \
	--set global.hubble.listenAddress=":4244" \
	--set global.hubble.ui.enabled=true \
	--set envoy.enabled=true \
	--set prometheus.enabled=true \
	--set operator.prometheus.enabled=true \
	--set cni.chainingMode="none"

	echo "Installing ENACT Core components under enact namespace"
	kubectl create namespace enact
	## workaround for EDC
	kubectl create secret generic edc-api-key-secret -n enact --from-literal=EDC_API_KEY="" 

	helm install infra -n enact oci://ghcr.io/prometheus-community/charts/kube-prometheus-stack
	helm install kepler kepler/kepler --namespace enact --create-namespace \
    --set serviceMonitor.enabled=true \
    --set serviceMonitor.labels.release=infra

	helm install tdcme-api enact-tdcme/monitor-api --namespace enact \
	--set existingSecret="" \
	--set secretKey=""

	TOKEN=$$(kubectl get secret join-token-secret -n enact -o jsonpath="{.data.token}" | base64 -d)
	echo "Token: $$TOKEN"
	helm install tdcme-agent enact-tdcme/monitor-api-agent --namespace enact \
	--set api.host="http://monitor-api-service.enact.svc.cluster.local" \
	--set api.joinToken="$$TOKEN" \
	--set cluster.name="dev" \
	--set agent.prometheusHost="http://infra-kube-prometheus-stac-prometheus.enact.svc.cluster.local:9090" \

	helm install applpm enact-applpm/appl --namespace enact
    
	echo "Waiting for all Pods to start"
	kubectl wait --for=condition=ready pod -n enact --all --timeout=120s
	echo "Labeling nodes"
 
	curl -X POST http://0.0.0.0:35580/api/v1/nodes/enact-dev-worker/labels \
	  -H "Content-Type: application/json" \
	  -d '{
	    "add": {
	      "enact.eu/green-ratio": "0.85",
	      "enact.eu/role": "edge",
	      "enact.eu/region": "eu-west",
	      "enact.eu/zone": "eu-west-1a"
	    }
	  }'

	curl -X POST http://0.0.0.0:35580/api/v1/nodes/enact-dev-worker2/labels \
	  -H "Content-Type: application/json" \
	  -d '{
	    "add": {
	      "enact.eu/green-ratio": "0.9",
	      "enact.eu/role": "cloud",
	      "enact.eu/region": "eu-west",
	      "enact.eu/zone": "eu-west-2a"
	    }
	  }'


	echo "All ENACT components have been installed. Refer to ENACT Eclipse Gitlab space for component documentation: https://gitlab.eclipse.org/eclipse-research-labs/enact-project"

clean:
	echo "Cleaning up..."
	kind delete cluster --name enact-dev

	echo "Cleaning Helm repositories..."
	helm repo remove prometheus-community
	helm repo remove enact-tdcme
	helm repo remove enact-applpm
	helm repo remove cilium
	docker system prune -f
# ---------------------------------------------------------------------------
# GreenCharge Autopilot (Veles Hack 2026 entry). Run after `make setup`.
# ---------------------------------------------------------------------------
APPLPM_UPSTREAM := https://gitlab.eclipse.org/eclipse-research-labs/enact-project/application-policy-model.git
APPLPM_COMMIT   := $(shell cat operator-fix/UPSTREAM_COMMIT 2>/dev/null)
NS              := enact

.PHONY: image operator-fix deploy autopilot-ui app-ui evidence test labels load

# Re-apply the node labels that `make setup` sets through the APPLPM API, using
# kubectl, in case APPLPM was not ready when setup reached that step.
labels:
	set -e
	kubectl label node enact-dev-worker  enact.eu/green-ratio=0.85 enact.eu/role=edge  enact.eu/region=eu-west enact.eu/zone=eu-west-1a --overwrite
	kubectl label node enact-dev-worker2 enact.eu/green-ratio=0.9  enact.eu/role=cloud enact.eu/region=eu-west enact.eu/zone=eu-west-2a --overwrite

test:
	cd greencharge && mvn -B test

image:
	set -e
	docker build -t greencharge:1.7 greencharge
	kind load docker-image greencharge:1.7 --name enact-dev

# Build the ENACT policy operator from upstream plus our fix, deploy it, and
# give it the TDCME token it needs to read metrics.
operator-fix:
	set -e
	rm -rf .build/applpm && git clone -q $(APPLPM_UPSTREAM) .build/applpm
	cd .build/applpm && git checkout -q $(APPLPM_COMMIT) && git apply ../../operator-fix/applpm-metrics-auth-and-placement.patch
	docker build -t applpm:auth-fix .build/applpm
	kind load docker-image applpm:auth-fix --name enact-dev
	kubectl -n $(NS) set image deploy/applpm-controller-manager manager=applpm:auth-fix
	kubectl -n $(NS) patch deploy applpm-controller-manager --type=json -p='[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"IfNotPresent"}]'
	kubectl -n $(NS) set env deploy/applpm-controller-manager METRICS_API_URL=http://monitor-api-service.$(NS).svc.cluster.local:80 CLUSTER_NAME=dev
	kubectl -n $(NS) set env deploy/applpm-controller-manager --from=secret/admin-token-secret --prefix=METRICS_API_ --keys=token
	kubectl -n $(NS) rollout status deploy/applpm-controller-manager --timeout=180s

deploy:
	set -e
	helm upgrade --install greencharge greencharge/chart -n $(NS)
	kubectl -n $(NS) rollout status deploy/greencharge-autopilot --timeout=300s

# The evidence timeline: http://localhost:8090/autopilot.html
autopilot-ui:
	kubectl -n $(NS) port-forward svc/greencharge-autopilot 8090:8080

# GreenCharge itself: http://localhost:8080
app-ui:
	kubectl -n $(NS) port-forward svc/greencharge 8080:8080

# Every evidence record the autopilot has written, as JSON lines.
evidence:
	kubectl -n $(NS) logs deploy/greencharge-autopilot | grep -o 'EVIDENCE .*' | cut -c10-

# A 3 minute traffic burst for the monitoring step (watch Grafana meanwhile).
load:
	NS=$(NS) bash scripts/load.sh 180

# ENACT SDK workflow without the Eclipse UI: the same ENACT APM libraries the
# SDK's Application Packaging, Application Policies and Dataspaces modules use.
.PHONY: sdk-package sdk-validate-policy sdk-dataspace sdk-deploy carbon-feed
sdk-package:
	cd sdk-workflow && mvn -B -q exec:java -Dexec.args="package ../greencharge/enact-sdk-generated"

sdk-validate-policy:
	set -e
	mkdir -p greencharge/enact-sdk-generated/policy
	helm template greencharge greencharge/chart -n $(NS) --show-only templates/runtimepolicy.yaml > greencharge/enact-sdk-generated/policy/greencharge-policy.yaml
	cd sdk-workflow && mvn -B -q exec:java -Dexec.args="validate-policy ../greencharge/enact-sdk-generated/policy/greencharge-policy.yaml"

# Fails when any step fails, including the download (the log is kept either way).
sdk-dataspace:
	cd sdk-workflow && mvn -B -q exec:java -Dexec.args="dataspace ../docs/evidence/dataspace" > ../docs/evidence/dataspace/5-sdk-edc-client-run.txt 2>&1; status=$$?; cat ../docs/evidence/dataspace/5-sdk-edc-client-run.txt; exit $$status

# Give the downloaded dataspace file to the running app (mounted at
# /app/carbon; GreenCharge re-reads it per request, no restart needed).
carbon-feed:
	set -e
	test -s docs/evidence/dataspace/grid-carbon-intensity.json
	kubectl -n $(NS) create configmap greencharge-carbon-feed --from-file=grid-carbon-intensity.json=docs/evidence/dataspace/grid-carbon-intensity.json --dry-run=client -o yaml | kubectl apply -f -
	echo "Within about a minute, GET /carbon reports source 'live (dataspace file)'."

# Deploy GreenCharge from the SDK-generated chart (the autopilot chart still
# provides the RuntimePolicy and the autopilot: helm install with autopilot only).
sdk-deploy:
	helm template greencharge greencharge/enact-sdk-generated/helm/greencharge -n $(NS) | kubectl apply -n $(NS) -f -
