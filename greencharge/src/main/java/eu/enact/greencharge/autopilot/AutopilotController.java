package eu.enact.greencharge.autopilot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Status, evidence timeline and on-demand passes for the autopilot. */
@RestController
@RequestMapping("/autopilot")
@ConditionalOnProperty(prefix = "autopilot", name = "enabled", havingValue = "true")
public class AutopilotController {

    private final Autopilot autopilot;
    private final EvidenceLog evidence;

    public AutopilotController(Autopilot autopilot, EvidenceLog evidence) {
        this.autopilot = autopilot;
        this.evidence = evidence;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return autopilot.status();
    }

    @GetMapping("/timeline")
    public List<EvidenceLog.Entry> timeline(@RequestParam(defaultValue = "200") int limit) {
        return evidence.recent(Math.min(limit, 500));
    }

    /** Runs one control-loop pass now instead of waiting for the schedule. */
    @PostMapping("/run")
    public Object run() {
        Autopilot.Outcome o = autopilot.runOnce();
        return o == null ? Map.of("result", "compliant", "detail", "nothing to adapt") : o;
    }

    /** Asks for a move to a node; the move is still subject to the policy's Hard rules. */
    @PostMapping("/drills/move")
    public Autopilot.Outcome move(@RequestParam String node) {
        return autopilot.requestMove(node, "manual request to move to " + node);
    }

    /** Applies an adaptation that cannot become healthy, to show detection and rollback. */
    @PostMapping("/drills/failed-adaptation")
    public Autopilot.Outcome failedAdaptation(@RequestParam(defaultValue = "45") int verifySeconds) {
        return autopilot.failureDrill(Math.max(15, Math.min(verifySeconds, 300)));
    }
}
