package eu.enact.greencharge.compliance;

import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.config.PolicyModelConfig;
import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.services.ComplianceAndAdaptationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the ENACT Application Controller's policy-model reconciler into this
 * application.
 *
 * <p>{@link PolicyModelConfig} is a Spring {@code @Configuration} that loads
 * {@code reconciliation/policymodel.yml} from the classpath and exposes the
 * {@code PolicyModel} bean; {@link ComplianceAndAdaptationService} autowires it.
 * Importing both lets Spring do the injection instead of reaching into private
 * fields.
 */
@Configuration
@Import({PolicyModelConfig.class, ComplianceAndAdaptationService.class})
public class ApplicationControllerConfig {
}
