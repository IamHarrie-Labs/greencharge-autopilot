package eu.enact.greencharge.compliance;

import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.DTO.AdaptationRecommendation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENACT Task 3 validation: the Application Controller, loaded with
 * GreenCharge's policy model, judges node metrics correctly.
 */
@SpringBootTest(classes = {ApplicationControllerConfig.class, PolicyCompliance.class})
class PolicyComplianceTest {

    private static final long GI = 1024L * 1024 * 1024;

    @Autowired
    PolicyCompliance compliance;

    @Test
    void wellProvisionedNodeIsCompliantWithNoAction() {
        AdaptationRecommendation r = compliance.evaluate(
                PolicyCompliance.metrics("enact-dev-worker", 2, 40, 2 * GI, 50, 20, 1000));

        assertThat(r.getCpu().getAction()).isEqualTo(PolicyCompliance.NO_ACTION);
        assertThat(r.getMemory().getAction()).isEqualTo(PolicyCompliance.NO_ACTION);
        assertThat(r.isCompliant()).isTrue();
    }

    @Test
    void underProvisionedCpuRecommendsScaleUpToThePolicyMinimum() {
        AdaptationRecommendation r = compliance.evaluate(
                PolicyCompliance.metrics("enact-dev-worker", 0.5, 40, 2 * GI, 50, 20, 1000));

        assertThat(r.isCompliant()).isFalse();
        assertThat(r.getCpu().getAction()).isEqualTo(PolicyCompliance.SCALE_UP);
        assertThat(r.getCpu().getRecommendedCores()).isEqualTo(1);
    }

    @Test
    void overProvisionedCpuRecommendsScaleDownToThePolicyMaximum() {
        AdaptationRecommendation r = compliance.evaluate(
                PolicyCompliance.metrics("enact-dev-worker", 6, 10, 2 * GI, 50, 20, 1000));

        assertThat(r.getCpu().getAction()).isEqualTo(PolicyCompliance.SCALE_DOWN);
        assertThat(r.getCpu().getRecommendedCores()).isEqualTo(4);
    }
}
