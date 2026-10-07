package eu.enact.greencharge.autopilot;

import eu.enact.greencharge.compliance.PolicyCompliance;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The autopilot runs only where {@code autopilot.enabled=true}: in its own
 * Deployment, so that restarting the workload it adapts never interrupts it.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AutopilotProperties.class)
@ConditionalOnProperty(prefix = "autopilot", name = "enabled", havingValue = "true")
public class AutopilotConfig {

    @Bean(destroyMethod = "close")
    KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    WorkloadObserver workloadObserver(KubernetesClient client, AutopilotProperties props) {
        return new WorkloadObserver(client, props);
    }

    @Bean
    Autopilot autopilot(KubernetesClient client, AutopilotProperties props, WorkloadObserver observer,
                        PolicyCompliance compliance, EvidenceLog evidence) {
        return new Autopilot(client, props, observer, compliance, evidence);
    }
}
