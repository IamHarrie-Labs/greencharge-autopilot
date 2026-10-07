package eu.enact.greencharge.autopilot;

import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.DTO.AdaptationRecommendation;
import eu.enact.greencharge.autopilot.EvidenceLog.Phase;
import eu.enact.greencharge.compliance.PolicyCompliance;
import eu.enact.greencharge.compliance.Quantities;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Closes the loop the ENACT components leave open.
 *
 * <p>The Application Controller recommends {@code scale_up}/{@code scale_down}
 * and the policy operator records a {@code chosenNode}, but neither changes the
 * running workload. Each pass of this loop:
 * <ol>
 *   <li><b>detect</b> - reads the workload's live limits, usage, node and health;</li>
 *   <li><b>decide</b> - asks the Application Controller for a recommendation and
 *       compares the pods' node with the operator's decision;</li>
 *   <li><b>guard</b> - refuses changes that break a Hard rule or come too soon;</li>
 *   <li><b>apply</b> - patches the Deployment;</li>
 *   <li><b>verify</b> - waits for the rollout and a 200 from the health URL;</li>
 *   <li><b>roll back</b> - restores the previous pod template if verification fails.</li>
 * </ol>
 * Every step is written to the {@link EvidenceLog}.
 */
public class Autopilot {

    /** A single change to the pod template, with what it was before. */
    public record Change(String kind, String from, String to, String reason) {
    }

    public record Outcome(String adaptation, String result, String detail) {
    }

    private static final Logger log = LoggerFactory.getLogger(Autopilot.class);
    static final String HOSTNAME_LABEL = "kubernetes.io/hostname";
    static final String ADAPTATION_ANNOTATION = "autopilot.enact.eu/adaptation";

    private final KubernetesClient client;
    private final AutopilotProperties props;
    private final WorkloadObserver observer;
    private final PolicyCompliance compliance;
    private final EvidenceLog evidence;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Instant lastAdaptation = Instant.EPOCH;
    private volatile Map<String, Object> lastStatus = Map.of("state", "starting");
    private int counter;

    public Autopilot(KubernetesClient client, AutopilotProperties props, WorkloadObserver observer,
                     PolicyCompliance compliance, EvidenceLog evidence) {
        this.client = client;
        this.props = props;
        this.observer = observer;
        this.compliance = compliance;
        this.evidence = evidence;
    }

    public Map<String, Object> status() {
        return lastStatus;
    }

    @Scheduled(initialDelayString = "10000", fixedDelayString = "${autopilot.interval-seconds:20}000")
    public void scheduledPass() {
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("autopilot pass failed: {}", e.toString());
            lastStatus = Map.of("state", "error", "error", e.toString(), "at", Instant.now().toString());
        }
    }

    /** One pass of the control loop. Returns the outcome, or null when nothing needed doing. */
    public Outcome runOnce() {
        if (!lock.tryLock()) {
            return new Outcome("-", "busy", "another adaptation is in progress");
        }
        try {
            WorkloadObserver.Observation obs = observer.observe();
            RuntimePolicyView policy = RuntimePolicyView.read(client, props.namespace(), props.policyName());
            AdaptationRecommendation rec = compliance.evaluate(PolicyCompliance.metrics(
                    obs.primaryNode(), obs.cpuLimitCores(), obs.cpuUsagePct(), obs.memoryLimitBytes(),
                    obs.memoryUsagePct(), Math.max(obs.healthLatencyMs(), 0), props.declaredBandwidthMbps()));

            List<Change> plan = plan(obs, rec, policy);
            lastStatus = statusOf(obs, rec, policy, plan);
            if (plan.isEmpty()) {
                return null;
            }

            String id = nextId();
            evidence.record(id, Phase.DETECT, "workload deviates from policy", observationData(obs, rec, policy));
            evidence.record(id, Phase.DECIDE, describe(plan), Map.of("changes", plan));

            Duration sinceLast = Duration.between(lastAdaptation, Instant.now());
            if (sinceLast.getSeconds() < props.cooldownSeconds()) {
                long wait = props.cooldownSeconds() - sinceLast.getSeconds();
                evidence.record(id, Phase.GUARD, "deferred: cooldown has " + wait + "s left",
                        Map.of("cooldownSeconds", props.cooldownSeconds()));
                return new Outcome(id, "deferred", "cooldown");
            }
            return execute(id, plan, policy, Duration.ofSeconds(props.verifyTimeoutSeconds()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Requests a move to a specific node, going through the same guard as the
     * loop. Used to show that a move breaking a Hard rule is refused.
     */
    public Outcome requestMove(String node, String reason) {
        lock.lock();
        try {
            String id = nextId();
            WorkloadObserver.Observation obs = observer.observe();
            RuntimePolicyView policy = RuntimePolicyView.read(client, props.namespace(), props.policyName());
            List<Change> plan = List.of(new Change("node", obs.primaryNode(), node, reason));
            evidence.record(id, Phase.DECIDE, describe(plan), Map.of("changes", plan, "requestedBy", "operator request"));
            return execute(id, plan, policy, Duration.ofSeconds(props.verifyTimeoutSeconds()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a change that cannot become healthy (a memory request no node can
     * satisfy) to show that a failed adaptation is detected and rolled back.
     */
    public Outcome failureDrill(int verifySeconds) {
        lock.lock();
        try {
            String id = nextId();
            Container c = WorkloadObserver.container(observer.deployment(), props.container());
            // A valid spec (request <= limit) that no node in the cluster can schedule.
            List<Change> plan = List.of(
                    new Change("memory-limit", limit(c, "memory"), "64Gi", "drill: more memory than any node has"),
                    new Change("memory-request", request(c, "memory"), "64Gi", "drill: more memory than any node has"));
            evidence.record(id, Phase.DECIDE, describe(plan), Map.of("changes", plan, "drill", true));
            return execute(id, plan, null, Duration.ofSeconds(verifySeconds));
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ plan

    List<Change> plan(WorkloadObserver.Observation obs, AdaptationRecommendation rec, RuntimePolicyView policy) {
        List<Change> plan = new ArrayList<>();
        if (rec != null && rec.getCpu() != null && isScale(rec.getCpu().getAction())
                && rec.getCpu().getRecommendedCores() > 0) {
            double target = rec.getCpu().getRecommendedCores();
            if (Double.isNaN(obs.cpuLimitCores()) || Math.abs(target - obs.cpuLimitCores()) > 1e-6) {
                plan.add(new Change("cpu-limit",
                        Double.isNaN(obs.cpuLimitCores()) ? "unset" : Quantities.cpuString(obs.cpuLimitCores()),
                        Quantities.cpuString(target), rec.getCpu().getMessage()));
            }
        }
        if (rec != null && rec.getMemory() != null && isScale(rec.getMemory().getAction())) {
            String target = rec.getMemory().getRecommendedMemory();
            long targetBytes = safeBytes(target);
            if (targetBytes > 0 && targetBytes != obs.memoryLimitBytes()) {
                plan.add(new Change("memory-limit",
                        obs.memoryLimitBytes() > 0 ? Quantities.toBinaryString(obs.memoryLimitBytes()) : "unset",
                        target, rec.getMemory().getMessage()));
            }
        }
        if (policy.found() && !policy.chosenNode().isBlank() && !"null".equals(policy.chosenNode())
                && !obs.nodes().isEmpty() && !obs.nodes().equals(List.of(policy.chosenNode()))) {
            plan.add(new Change("node", String.join(",", obs.nodes()), policy.chosenNode(),
                    "ENACT operator chose " + policy.chosenNode() + ": " + policy.reason()));
        }
        return plan;
    }

    private static boolean isScale(String action) {
        return PolicyCompliance.SCALE_UP.equals(action) || PolicyCompliance.SCALE_DOWN.equals(action);
    }

    // --------------------------------------------------------------- execute

    private Outcome execute(String id, List<Change> plan, RuntimePolicyView policy, Duration verifyTimeout) {
        // Guard: every node move must satisfy the policy's Hard rules.
        for (Change ch : plan) {
            if ("node".equals(ch.kind()) && policy != null) {
                Node target = client.nodes().withName(ch.to()).get();
                String violation = policy.violation(target);
                if (violation != null) {
                    evidence.record(id, Phase.GUARD, "rejected move to " + ch.to() + ": " + violation,
                            Map.of("policy", policy.name(), "node", ch.to()));
                    evidence.record(id, Phase.OUTCOME, "rejected; workload unchanged", Map.of("result", "rejected"));
                    return new Outcome(id, "rejected", violation);
                }
            }
        }
        evidence.record(id, Phase.GUARD, "passed", Map.of());

        if (props.dryRun()) {
            evidence.record(id, Phase.OUTCOME, "dry run; workload unchanged", Map.of("result", "dry_run"));
            return new Outcome(id, "dry_run", describe(plan));
        }

        Deployment before = observer.deployment();
        PodTemplateSpec snapshot = Serialization.clone(before.getSpec().getTemplate());
        long healthBefore = observer.healthLatencyMs();

        try {
            client.apps().deployments().inNamespace(props.namespace()).withName(props.deployment()).edit(d -> {
                PodTemplateSpec t = d.getSpec().getTemplate();
                Container c = WorkloadObserver.container(d, props.container());
                for (Change ch : plan) {
                    applyChange(t, c, ch);
                }
                if (t.getMetadata().getAnnotations() == null) {
                    t.getMetadata().setAnnotations(new HashMap<>());
                }
                t.getMetadata().getAnnotations().put(ADAPTATION_ANNOTATION, id);
                return d;
            });
        } catch (KubernetesClientException e) {
            // The API server validates the patch; a rejected patch changes nothing.
            String why = e.getStatus() != null && e.getStatus().getMessage() != null
                    ? e.getStatus().getMessage() : e.getMessage();
            evidence.record(id, Phase.APPLY, "Kubernetes rejected the patch: " + why, Map.of("changes", plan));
            evidence.record(id, Phase.OUTCOME, "not applied; workload unchanged", Map.of("result", "not_applied"));
            return new Outcome(id, "not_applied", why);
        }
        lastAdaptation = Instant.now();
        long appliedGeneration = observer.deployment().getMetadata().getGeneration();
        evidence.record(id, Phase.APPLY, "patched deployment " + props.deployment(),
                Map.of("changes", plan, "healthLatencyMsBefore", healthBefore, "generation", appliedGeneration));

        String failure = verify(id, plan, verifyTimeout);
        if (failure == null) {
            Map<String, Object> after = observationData(observer.observe(), null, null);
            evidence.record(id, Phase.OUTCOME, "adapted and verified", Map.of("result", "adapted", "after", after));
            return new Outcome(id, "adapted", describe(plan));
        }

        // Someone else (Helm, a person, another controller) changed the pod
        // template since our patch: restoring ours would overwrite their change.
        long currentGeneration = observer.deployment().getMetadata().getGeneration();
        if (currentGeneration != appliedGeneration) {
            evidence.record(id, Phase.ROLLBACK, "skipped: the deployment changed after this adaptation (generation "
                    + appliedGeneration + " -> " + currentGeneration + "); not overwriting another actor's change",
                    Map.of("cause", failure));
            evidence.record(id, Phase.OUTCOME, "verification failed; left for the newer change", Map.of("result", "superseded"));
            return new Outcome(id, "superseded", failure);
        }

        evidence.record(id, Phase.ROLLBACK, "verification failed (" + failure + "); reverting only the fields this adaptation changed",
                Map.of("changes", plan));
        client.apps().deployments().inNamespace(props.namespace()).withName(props.deployment()).edit(d -> {
            revert(d.getSpec().getTemplate(), WorkloadObserver.container(d, props.container()),
                    snapshot, WorkloadObserver.containerOf(snapshot, props.container()), plan);
            if (d.getSpec().getTemplate().getMetadata().getAnnotations() == null) {
                d.getSpec().getTemplate().getMetadata().setAnnotations(new HashMap<>());
            }
            d.getSpec().getTemplate().getMetadata().getAnnotations().put(ADAPTATION_ANNOTATION, id + "-rollback");
            return d;
        });
        String rollbackFailure = verify(id, List.of(), Duration.ofSeconds(props.verifyTimeoutSeconds()));
        if (rollbackFailure == null) {
            evidence.record(id, Phase.OUTCOME, "rolled back; workload healthy on its previous configuration",
                    Map.of("result", "rolled_back", "cause", failure));
            return new Outcome(id, "rolled_back", failure);
        }
        evidence.record(id, Phase.OUTCOME, "rollback did not become healthy: " + rollbackFailure,
                Map.of("result", "failed", "cause", failure));
        return new Outcome(id, "failed", rollbackFailure);
    }

    static void applyChange(PodTemplateSpec t, Container c, Change ch) {
        if (c.getResources() == null) {
            c.setResources(new ResourceRequirements());
        }
        Map<String, Quantity> limits = c.getResources().getLimits() == null
                ? new HashMap<>() : new HashMap<>(c.getResources().getLimits());
        Map<String, Quantity> requests = c.getResources().getRequests() == null
                ? new HashMap<>() : new HashMap<>(c.getResources().getRequests());
        switch (ch.kind()) {
            case "cpu-limit" -> {
                limits.put("cpu", new Quantity(ch.to()));
                capRequest(requests, "cpu", Quantity.getAmountInBytes(new Quantity(ch.to())).doubleValue());
            }
            case "memory-limit" -> {
                limits.put("memory", new Quantity(ch.to()));
                capRequest(requests, "memory", Quantity.getAmountInBytes(new Quantity(ch.to())).doubleValue());
            }
            case "memory-request" -> requests.put("memory", new Quantity(ch.to()));
            case "node" -> {
                Map<String, String> sel = t.getSpec().getNodeSelector() == null
                        ? new HashMap<>() : new HashMap<>(t.getSpec().getNodeSelector());
                sel.put(HOSTNAME_LABEL, ch.to());
                t.getSpec().setNodeSelector(sel);
            }
            default -> throw new IllegalArgumentException("unknown change " + ch.kind());
        }
        c.getResources().setLimits(limits);
        c.getResources().setRequests(requests);
    }

    /**
     * Restores, from the snapshot, only the fields the plan touched, leaving any
     * other change to the pod template (image, env, labels...) alone.
     */
    static void revert(PodTemplateSpec live, Container liveC, PodTemplateSpec snapshot, Container snapC,
                       List<Change> plan) {
        for (Change ch : plan) {
            switch (ch.kind()) {
                case "cpu-limit" -> {
                    restoreQuantity(liveC, snapC, true, "cpu");
                    restoreQuantity(liveC, snapC, false, "cpu");
                }
                case "memory-limit", "memory-request" -> {
                    restoreQuantity(liveC, snapC, true, "memory");
                    restoreQuantity(liveC, snapC, false, "memory");
                }
                case "node" -> {
                    String before = snapshot.getSpec().getNodeSelector() == null
                            ? null : snapshot.getSpec().getNodeSelector().get(HOSTNAME_LABEL);
                    Map<String, String> sel = live.getSpec().getNodeSelector() == null
                            ? new HashMap<>() : new HashMap<>(live.getSpec().getNodeSelector());
                    if (before == null) {
                        sel.remove(HOSTNAME_LABEL);
                    } else {
                        sel.put(HOSTNAME_LABEL, before);
                    }
                    live.getSpec().setNodeSelector(sel.isEmpty() ? null : sel);
                }
                default -> throw new IllegalArgumentException("unknown change " + ch.kind());
            }
        }
    }

    private static void restoreQuantity(Container live, Container snap, boolean limit, String resource) {
        if (live.getResources() == null) {
            live.setResources(new ResourceRequirements());
        }
        Map<String, Quantity> before = snap.getResources() == null ? null
                : (limit ? snap.getResources().getLimits() : snap.getResources().getRequests());
        Map<String, Quantity> now = limit ? live.getResources().getLimits() : live.getResources().getRequests();
        Map<String, Quantity> out = now == null ? new HashMap<>() : new HashMap<>(now);
        if (before == null || before.get(resource) == null) {
            out.remove(resource);
        } else {
            out.put(resource, before.get(resource));
        }
        if (limit) {
            live.getResources().setLimits(out);
        } else {
            live.getResources().setRequests(out);
        }
    }

    /** A request may not exceed its limit; lower it when a limit is reduced. */
    private static void capRequest(Map<String, Quantity> requests, String resource, double limit) {
        Quantity r = requests.get(resource);
        if (r != null && Quantity.getAmountInBytes(r).doubleValue() > limit) {
            requests.put(resource, "cpu".equals(resource)
                    ? new Quantity(Quantities.cpuString(limit)) : new Quantity(Quantities.toBinaryString((long) limit)));
        }
    }

    /** Waits for a complete rollout, then a healthy response. Returns null on success, else why it failed. */
    private String verify(String id, List<Change> plan, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        String last = "rollout did not start";
        while (Instant.now().isBefore(deadline)) {
            Deployment d = observer.deployment();
            DeploymentStatus s = d.getStatus();
            int want = d.getSpec().getReplicas() == null ? 1 : d.getSpec().getReplicas();
            boolean observed = s != null && s.getObservedGeneration() != null
                    && s.getObservedGeneration() >= d.getMetadata().getGeneration();
            int updated = s == null || s.getUpdatedReplicas() == null ? 0 : s.getUpdatedReplicas();
            int available = s == null || s.getAvailableReplicas() == null ? 0 : s.getAvailableReplicas();
            int total = s == null || s.getReplicas() == null ? 0 : s.getReplicas();
            // availableReplicas also counts old pods, so the new ReplicaSet's own
            // availability comes from the Progressing condition. Old pods that are
            // still terminating (minutes, on a loaded node) do not gate success.
            boolean newPodsAvailable = s != null && s.getConditions() != null && s.getConditions().stream()
                    .anyMatch(c -> "Progressing".equals(c.getType()) && "NewReplicaSetAvailable".equals(c.getReason()));
            if (observed && updated >= want && available >= want && newPodsAvailable) {
                long latency = observer.healthLatencyMs();
                if (latency >= 0) {
                    String mismatch = readBack(plan);
                    if (mismatch == null) {
                        evidence.record(id, Phase.VERIFY, "rollout complete and health check answered in " + latency + " ms",
                                Map.of("healthLatencyMs", latency, "replicas", want));
                        return null;
                    }
                    last = mismatch;
                } else {
                    last = "rollout complete but the health URL did not answer 200";
                }
            } else {
                last = "rollout in progress: updated " + updated + "/" + want + ", available " + available + "/" + want
                        + ", pods " + total + (observed ? "" : ", spec not yet observed");
            }
            sleep(3000);
        }
        evidence.record(id, Phase.VERIFY, "timed out after " + timeout.getSeconds() + "s: " + last, Map.of());
        return last;
    }

    /** Confirms the running pods actually carry the change (e.g. landed on the target node). */
    private String readBack(List<Change> plan) {
        for (Change ch : plan) {
            if ("node".equals(ch.kind())) {
                WorkloadObserver.Observation obs = observer.observe();
                if (!obs.nodes().equals(List.of(ch.to()))) {
                    return "pods run on " + obs.nodes() + ", expected " + ch.to();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private synchronized String nextId() {
        return "A" + (++counter) + "-" + Instant.now().getEpochSecond();
    }

    private static String request(Container c, String resource) {
        if (c.getResources() == null || c.getResources().getRequests() == null
                || c.getResources().getRequests().get(resource) == null) {
            return "unset";
        }
        return c.getResources().getRequests().get(resource).toString();
    }

    private static String limit(Container c, String resource) {
        if (c.getResources() == null || c.getResources().getLimits() == null
                || c.getResources().getLimits().get(resource) == null) {
            return "unset";
        }
        return c.getResources().getLimits().get(resource).toString();
    }

    private static long safeBytes(String q) {
        try {
            return Quantities.bytes(q);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String describe(List<Change> plan) {
        List<String> parts = new ArrayList<>();
        for (Change c : plan) {
            parts.add(c.kind() + " " + c.from() + " -> " + c.to());
        }
        return String.join("; ", parts);
    }

    private static Map<String, Object> observationData(WorkloadObserver.Observation obs, AdaptationRecommendation rec,
                                                       RuntimePolicyView policy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", obs.nodes());
        m.put("readyReplicas", obs.readyReplicas() + "/" + obs.replicas());
        m.put("cpuLimit", Double.isNaN(obs.cpuLimitCores()) ? "unset" : Quantities.cpuString(obs.cpuLimitCores()));
        m.put("memoryLimit", obs.memoryLimitBytes() > 0 ? Quantities.toBinaryString(obs.memoryLimitBytes()) : "unset");
        m.put("cpuUsagePct", round(obs.cpuUsagePct()));
        m.put("memoryUsagePct", round(obs.memoryUsagePct()));
        m.put("healthLatencyMs", obs.healthLatencyMs());
        if (rec != null) {
            m.put("recommendation", rec);
        }
        if (policy != null) {
            m.put("policy", Map.of("name", policy.name(), "chosenNode", policy.chosenNode(), "reason", policy.reason()));
        }
        return m;
    }

    private Map<String, Object> statusOf(WorkloadObserver.Observation obs, AdaptationRecommendation rec,
                                         RuntimePolicyView policy, List<Change> plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", Instant.now().toString());
        m.put("state", plan.isEmpty() ? "compliant" : "adapting");
        m.put("dryRun", props.dryRun());
        m.put("observation", observationData(obs, rec, null));
        m.put("policy", policy);
        m.put("pendingChanges", plan);
        m.put("lastAdaptation", lastAdaptation.equals(Instant.EPOCH) ? null : lastAdaptation.toString());
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
