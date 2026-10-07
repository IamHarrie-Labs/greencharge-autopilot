package eu.enact.greencharge.autopilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of what the autopilot observed, decided and did.
 *
 * <p>Every entry is also written to the log as one JSON line prefixed with
 * {@code EVIDENCE}, so the full history survives a restart of the autopilot
 * pod as long as its logs are kept ({@code kubectl logs ... | grep EVIDENCE}).
 */
@Component
public class EvidenceLog {

    /** The stages of one adaptation, in the order they normally happen. */
    public enum Phase { DETECT, DECIDE, GUARD, APPLY, VERIFY, ROLLBACK, OUTCOME }

    public record Entry(long seq, Instant at, String adaptation, Phase phase, String message,
                        Map<String, Object> data) {
    }

    private static final Logger log = LoggerFactory.getLogger(EvidenceLog.class);
    private static final int CAPACITY = 500;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private long seq;

    public synchronized Entry record(String adaptation, Phase phase, String message, Map<String, Object> data) {
        Entry e = new Entry(++seq, Instant.now(), adaptation, phase, message,
                data == null ? Map.of() : new LinkedHashMap<>(data));
        entries.addLast(e);
        if (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
        try {
            log.info("EVIDENCE {}", json.writeValueAsString(e));
        } catch (Exception ex) {
            log.info("EVIDENCE {} {} {} {}", e.seq(), adaptation, phase, message);
        }
        return e;
    }

    /** Newest first. */
    public synchronized List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>(Math.min(limit, entries.size()));
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }
}
