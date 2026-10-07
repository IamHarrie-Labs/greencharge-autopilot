package eu.enact.greencharge.autopilot;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Reads the managed workload's live state from the Kubernetes API. */
public class WorkloadObserver {

    /**
     * One reading of the workload.
     *
     * @param cpuLimitCores   container CPU limit, NaN if unset
     * @param memoryLimitBytes container memory limit, -1 if unset
     * @param cpuUsagePct     measured CPU use as a share of the limit, -1 if metrics are unavailable
     * @param memoryUsagePct  measured memory use as a share of the limit, -1 if unavailable
     * @param healthLatencyMs round trip of the health URL, -1 if it did not answer 200
     */
    public record Observation(String deployment, List<String> nodes, int replicas, int readyReplicas,
                              double cpuLimitCores, long memoryLimitBytes,
                              double cpuUsagePct, double memoryUsagePct, long healthLatencyMs) {

        public String primaryNode() {
            return nodes.isEmpty() ? "" : nodes.get(0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WorkloadObserver.class);

    private final KubernetesClient client;
    private final AutopilotProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WorkloadObserver(KubernetesClient client, AutopilotProperties props) {
        this.client = client;
        this.props = props;
    }

    public Deployment deployment() {
        Deployment d = client.apps().deployments().inNamespace(props.namespace()).withName(props.deployment()).get();
        if (d == null) {
            throw new IllegalStateException("deployment " + props.namespace() + "/" + props.deployment() + " not found");
        }
        return d;
    }

    public Observation observe() {
        Deployment d = deployment();
        Container c = container(d, props.container());

        double cpuLimit = Double.NaN;
        long memLimit = -1;
        if (c.getResources() != null && c.getResources().getLimits() != null) {
            Map<String, Quantity> limits = c.getResources().getLimits();
            if (limits.get("cpu") != null) {
                cpuLimit = Quantity.getAmountInBytes(limits.get("cpu")).doubleValue();
            }
            if (limits.get("memory") != null) {
                memLimit = Quantity.getAmountInBytes(limits.get("memory")).longValue();
            }
        }

        List<Pod> pods = runningPods(d);
        List<String> nodes = pods.stream().map(p -> p.getSpec().getNodeName())
                .filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());

        double cpuPct = -1;
        double memPct = -1;
        if (!pods.isEmpty()) {
            double[] usage = usage(pods.get(0), props.container());
            if (usage != null) {
                cpuPct = Double.isNaN(cpuLimit) || cpuLimit <= 0 ? -1 : 100.0 * usage[0] / cpuLimit;
                memPct = memLimit <= 0 ? -1 : 100.0 * usage[1] / memLimit;
            }
        }

        int replicas = d.getSpec().getReplicas() == null ? 1 : d.getSpec().getReplicas();
        int ready = d.getStatus() == null || d.getStatus().getReadyReplicas() == null ? 0 : d.getStatus().getReadyReplicas();
        return new Observation(d.getMetadata().getName(), nodes, replicas, ready,
                cpuLimit, memLimit, cpuPct, memPct, healthLatencyMs());
    }

    public List<Pod> runningPods(Deployment d) {
        return client.pods().inNamespace(props.namespace())
                .withLabels(d.getSpec().getSelector().getMatchLabels()).list().getItems().stream()
                .filter(p -> p.getMetadata().getDeletionTimestamp() == null)
                .filter(p -> p.getStatus() != null && "Running".equals(p.getStatus().getPhase()))
                .collect(Collectors.toList());
    }

    /** Milliseconds for a 200 from the health URL, or -1. */
    public long healthLatencyMs() {
        long start = System.nanoTime();
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(props.healthUrl())).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200 ? (System.nanoTime() - start) / 1_000_000 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static Container container(Deployment d, String name) {
        return containerOf(d.getSpec().getTemplate(), name);
    }

    static Container containerOf(io.fabric8.kubernetes.api.model.PodTemplateSpec t, String name) {
        List<Container> containers = t.getSpec().getContainers();
        return containers.stream().filter(c -> name.equals(c.getName())).findFirst()
                .orElse(containers.get(0));
    }

    /** {cpuCores, memoryBytes} in use by the container, or null when metrics-server has no reading. */
    private double[] usage(Pod pod, String container) {
        try {
            PodMetrics pm = client.top().pods().inNamespace(pod.getMetadata().getNamespace())
                    .withName(pod.getMetadata().getName()).metric();
            if (pm == null) {
                return null;
            }
            for (ContainerMetrics cm : pm.getContainers()) {
                if (container.equals(cm.getName()) || pm.getContainers().size() == 1) {
                    BigDecimal cpu = Quantity.getAmountInBytes(cm.getUsage().get("cpu"));
                    BigDecimal mem = Quantity.getAmountInBytes(cm.getUsage().get("memory"));
                    return new double[]{cpu.doubleValue(), mem.doubleValue()};
                }
            }
        } catch (Exception e) {
            log.debug("no pod metrics for {}: {}", pod.getMetadata().getName(), e.getMessage());
        }
        return null;
    }
}
