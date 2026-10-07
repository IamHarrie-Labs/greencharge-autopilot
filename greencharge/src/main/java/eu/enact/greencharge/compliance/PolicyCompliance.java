package eu.enact.greencharge.compliance;

import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.DTO.AdaptationRecommendation;
import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.DTO.ResourceMetrics;
import com.informationcatalyst.enact.application_controller.policymodel.Reconciler.services.ComplianceAndAdaptationService;
import org.springframework.stereotype.Service;

/**
 * Evaluates resource metrics against the Application Policy Model using the
 * ENACT Application Controller, and turns the result into a decision the
 * autopilot can act on.
 */
@Service
public class PolicyCompliance {

    public static final String SCALE_UP = "scale_up";
    public static final String SCALE_DOWN = "scale_down";
    public static final String NO_ACTION = "no_action";

    private final ComplianceAndAdaptationService reconciler;

    public PolicyCompliance(ComplianceAndAdaptationService reconciler) {
        this.reconciler = reconciler;
    }

    public AdaptationRecommendation evaluate(ResourceMetrics metrics) {
        return reconciler.checkAndAdapt(metrics);
    }

    /**
     * Builds the Application Controller's metrics DTO from plain values.
     *
     * @param bandwidthMbps declared link bandwidth; kind's virtual links cannot be measured meaningfully
     */
    public static ResourceMetrics metrics(String node, double cores, double cpuUtilisationPct,
                                          long memoryBytes, double memoryUtilisationPct,
                                          long latencyMs, long bandwidthMbps) {
        ResourceMetrics m = new ResourceMetrics();
        m.setNodeName(node);

        ResourceMetrics.CpuMetrics cpu = new ResourceMetrics.CpuMetrics();
        cpu.setCores(cores);
        cpu.setUtilizationPercentage(cpuUtilisationPct);
        m.setCpu(cpu);

        ResourceMetrics.MemoryMetrics mem = new ResourceMetrics.MemoryMetrics();
        mem.setBytes(memoryBytes);
        mem.setCapacity(Quantities.toBinaryString(memoryBytes));
        mem.setUtilizationPercentage(memoryUtilisationPct);
        m.setMemory(mem);

        ResourceMetrics.NetworkMetrics net = new ResourceMetrics.NetworkMetrics();
        net.setLatencyMs(latencyMs);
        net.setBandwidthMbps(bandwidthMbps);
        m.setNetwork(net);
        return m;
    }
}
