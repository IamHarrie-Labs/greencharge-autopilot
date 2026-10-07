package eu.enact.greencharge.autopilot;

import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.DTO.AdaptationRecommendation;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Planning, guarding and patching, without a cluster. */
class AutopilotPlanTest {

    private static final long GI = 1024L * 1024 * 1024;

    private final Autopilot autopilot = new Autopilot(null, props(), null, null, new EvidenceLog());

    private static AutopilotProperties props() {
        return new AutopilotProperties(true, "enact", "greencharge", "greencharge", "greencharge-policy",
                "http://localhost/chargers", 20, 60, 180, false, 1000);
    }

    private static WorkloadObserver.Observation obs(String node, double cpu) {
        return new WorkloadObserver.Observation("greencharge", List.of(node), 1, 1, cpu, 2 * GI, 30, 40, 12);
    }

    private static RuntimePolicyView policy(String chosen, List<String> hardRegions) {
        return new RuntimePolicyView("greencharge-policy", true, chosen, "selected", hardRegions, List.of(), null, List.of());
    }

    private static AdaptationRecommendation cpu(String action, int cores) {
        AdaptationRecommendation r = new AdaptationRecommendation();
        AdaptationRecommendation.CpuRecommendation c = new AdaptationRecommendation.CpuRecommendation();
        c.setAction(action);
        c.setRecommendedCores(cores);
        c.setMessage("test");
        r.setCpu(c);
        return r;
    }

    @Test
    void scaleUpRecommendationBecomesACpuLimitChange() {
        List<Autopilot.Change> plan = autopilot.plan(obs("enact-dev-worker", 0.5), cpu("scale_up", 1),
                policy("enact-dev-worker", List.of("eu-west")));

        assertThat(plan).containsExactly(new Autopilot.Change("cpu-limit", "500m", "1", "test"));
    }

    @Test
    void compliantWorkloadOnTheChosenNodeNeedsNothing() {
        assertThat(autopilot.plan(obs("enact-dev-worker", 1), cpu("no_action", 0),
                policy("enact-dev-worker", List.of()))).isEmpty();
    }

    @Test
    void operatorDecisionOnAnotherNodeBecomesAMove() {
        List<Autopilot.Change> plan = autopilot.plan(obs("enact-dev-worker", 1), cpu("no_action", 0),
                policy("enact-dev-worker2", List.of("eu-west")));

        assertThat(plan).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("node");
            assertThat(c.to()).isEqualTo("enact-dev-worker2");
        });
    }

    @Test
    void guardRejectsANodeOutsideTheHardRegion() {
        Node usEast = node("enact-dev-control-plane", "us-east", true);
        Node euWest = node("enact-dev-worker2", "eu-west", true);
        RuntimePolicyView p = policy("", List.of("eu-west"));

        assertThat(p.violation(usEast)).contains("Hard location rule").contains("us-east");
        assertThat(p.violation(euWest)).isNull();
        assertThat(p.violation(node("enact-dev-worker", "eu-west", false))).contains("not Ready");
        assertThat(p.violation(null)).isEqualTo("node does not exist");
    }

    @Test
    void loweringACpuLimitAlsoLowersARequestAboveIt() {
        Container c = new ContainerBuilder().withName("greencharge")
                .withNewResources()
                .addToLimits("cpu", new Quantity("2")).addToRequests("cpu", new Quantity("1500m"))
                .endResources().build();
        PodTemplateSpec t = new PodTemplateSpecBuilder().withNewSpec().withContainers(c).endSpec().build();

        Autopilot.applyChange(t, c, new Autopilot.Change("cpu-limit", "2", "1", "test"));

        assertThat(c.getResources().getLimits().get("cpu")).isEqualTo(new Quantity("1"));
        assertThat(Quantity.getAmountInBytes(c.getResources().getRequests().get("cpu")).doubleValue()).isEqualTo(1.0);
    }

    @Test
    void moveSetsTheHostnameNodeSelector() {
        Container c = new ContainerBuilder().withName("greencharge").build();
        PodTemplateSpec t = new PodTemplateSpecBuilder().withNewSpec().withContainers(c)
                .withNodeSelector(Map.of("enact.eu/role", "edge")).endSpec().build();

        Autopilot.applyChange(t, c, new Autopilot.Change("node", "enact-dev-worker", "enact-dev-worker2", "test"));

        assertThat(t.getSpec().getNodeSelector())
                .containsEntry(Autopilot.HOSTNAME_LABEL, "enact-dev-worker2")
                .containsEntry("enact.eu/role", "edge");
    }

    @Test
    void rollbackRevertsOnlyTheFieldsTheAdaptationChanged() {
        Container snapC = new ContainerBuilder().withName("greencharge").withImage("greencharge:1.1")
                .withNewResources().addToLimits("cpu", new Quantity("500m")).endResources().build();
        PodTemplateSpec snapshot = new PodTemplateSpecBuilder().withNewSpec().withContainers(snapC).endSpec().build();

        // After our adaptation (cpu 1, pinned to worker2) someone else changed the image.
        Container liveC = new ContainerBuilder().withName("greencharge").withImage("greencharge:1.2")
                .withNewResources().addToLimits("cpu", new Quantity("1")).endResources().build();
        PodTemplateSpec live = new PodTemplateSpecBuilder().withNewSpec().withContainers(liveC)
                .withNodeSelector(Map.of(Autopilot.HOSTNAME_LABEL, "enact-dev-worker2")).endSpec().build();

        Autopilot.revert(live, liveC, snapshot, snapC, List.of(
                new Autopilot.Change("cpu-limit", "500m", "1", "t"),
                new Autopilot.Change("node", "enact-dev-worker", "enact-dev-worker2", "t")));

        assertThat(liveC.getResources().getLimits().get("cpu")).isEqualTo(new Quantity("500m"));
        assertThat(live.getSpec().getNodeSelector()).isNull();
        assertThat(liveC.getImage()).isEqualTo("greencharge:1.2");
    }

    private static Node node(String name, String region, boolean ready) {
        return new NodeBuilder().withNewMetadata().withName(name)
                .addToLabels("enact.eu/region", region).endMetadata()
                .withNewStatus().addNewCondition().withType("Ready").withStatus(ready ? "True" : "False").endCondition()
                .endStatus().build();
    }
}
