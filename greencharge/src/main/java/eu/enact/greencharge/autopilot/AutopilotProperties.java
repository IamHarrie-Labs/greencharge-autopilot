package eu.enact.greencharge.autopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the autopilot, bound from {@code autopilot.*} (or the
 * matching {@code AUTOPILOT_*} environment variables).
 *
 * @param enabled             run the control loop (only the autopilot Deployment sets this)
 * @param namespace           namespace of the managed workload
 * @param deployment          name of the managed Deployment
 * @param container           container whose resources are adapted
 * @param policyName          RuntimePolicy whose decision drives placement
 * @param healthUrl           URL that must answer 200 for the workload to count as recovered
 * @param intervalSeconds     time between control-loop passes
 * @param cooldownSeconds     minimum time between two adaptations
 * @param verifyTimeoutSeconds how long an adaptation has to become healthy before it is rolled back
 * @param dryRun              decide and record, but never change the workload
 * @param declaredBandwidthMbps link bandwidth reported to the policy check; declared, not measured
 */
@ConfigurationProperties(prefix = "autopilot")
public record AutopilotProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("enact") String namespace,
        @DefaultValue("greencharge") String deployment,
        @DefaultValue("greencharge") String container,
        @DefaultValue("greencharge-policy") String policyName,
        @DefaultValue("http://greencharge.enact.svc.cluster.local:8080/chargers") String healthUrl,
        @DefaultValue("20") int intervalSeconds,
        @DefaultValue("60") int cooldownSeconds,
        @DefaultValue("180") int verifyTimeoutSeconds,
        @DefaultValue("false") boolean dryRun,
        @DefaultValue("1000") long declaredBandwidthMbps) {
}
