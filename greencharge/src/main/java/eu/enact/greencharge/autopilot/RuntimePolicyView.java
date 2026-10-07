package eu.enact.greencharge.autopilot;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The parts of an ENACT {@code RuntimePolicy} the autopilot needs: the
 * operator's placement decision and the Hard rules any move must respect.
 */
public record RuntimePolicyView(String name, boolean found, String chosenNode, String reason,
                                List<String> hardRegions, List<String> hardZones,
                                Double hardGreenMin, List<Map<String, Object>> rejectedNodes) {

    static final String API_VERSION = "enact.eu/v1alpha1";
    static final String KIND = "RuntimePolicy";

    public static RuntimePolicyView read(KubernetesClient client, String namespace, String name) {
        GenericKubernetesResource rp = client.genericKubernetesResources(API_VERSION, KIND)
                .inNamespace(namespace).withName(name).get();
        if (rp == null) {
            return new RuntimePolicyView(name, false, "", "policy not found", List.of(), List.of(), null, List.of());
        }
        Map<String, Object> spec = map(rp.getAdditionalProperties().get("spec"));
        Map<String, Object> status = map(rp.getAdditionalProperties().get("status"));

        Map<String, Object> location = map(spec.get("location"));
        boolean locationHard = "Hard".equals(location.get("mode"));
        Map<String, Object> green = map(spec.get("greenEnergy"));
        Double greenMin = "Hard".equals(green.get("mode")) && green.get("minRatio") != null
                ? Double.valueOf(String.valueOf(green.get("minRatio"))) : null;

        return new RuntimePolicyView(name, true,
                String.valueOf(status.getOrDefault("chosenNode", "")),
                String.valueOf(status.getOrDefault("reason", "")),
                locationHard ? strings(location.get("regions")) : List.of(),
                locationHard ? strings(location.get("zones")) : List.of(),
                greenMin,
                listOfMaps(status.get("rejectedNodes")));
    }

    /**
     * Checks a node against this policy's Hard rules using the same labels the
     * ENACT operator reads. Returns null when the node is acceptable, else why not.
     */
    public String violation(Node node) {
        if (node == null) {
            return "node does not exist";
        }
        boolean ready = node.getStatus() != null && node.getStatus().getConditions().stream()
                .anyMatch((NodeCondition c) -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
        if (!ready) {
            return "node " + node.getMetadata().getName() + " is not Ready";
        }
        Map<String, String> labels = node.getMetadata().getLabels() == null ? Map.of() : node.getMetadata().getLabels();
        String region = labels.getOrDefault("enact.eu/region", labels.getOrDefault("topology.kubernetes.io/region", ""));
        String zone = labels.getOrDefault("enact.eu/zone", labels.getOrDefault("topology.kubernetes.io/zone", ""));
        if (!hardRegions.isEmpty() && !hardRegions.contains(region)) {
            return "Hard location rule: region \"" + region + "\" is not in " + hardRegions;
        }
        if (!hardZones.isEmpty() && !hardZones.contains(zone)) {
            return "Hard location rule: zone \"" + zone + "\" is not in " + hardZones;
        }
        if (hardGreenMin != null) {
            double ratio = 0;
            try {
                ratio = Double.parseDouble(labels.getOrDefault("enact.eu/green-ratio", "0"));
            } catch (NumberFormatException ignored) {
                // an unparsable label counts as 0, as in the operator
            }
            if (ratio < hardGreenMin) {
                return "Hard green-energy rule: ratio " + ratio + " is below " + hardGreenMin;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(v -> {
                if (v instanceof Map) {
                    out.add((Map<String, Object>) v);
                }
            });
        }
        return out;
    }
}
