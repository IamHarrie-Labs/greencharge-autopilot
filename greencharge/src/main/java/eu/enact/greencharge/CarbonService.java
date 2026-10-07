package eu.enact.greencharge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.enact.greencharge.model.CarbonSnapshot;

/**
 * Supplies per-district grid carbon intensity.
 *
 * <p>The carbon data belongs to the energy utility and is shared only through a
 * dataspace contract. Completing ENACT Step 1 (Dataspaces) negotiates that
 * contract and <strong>transfers a data file onto this machine</strong>; the
 * team then points {@code carbon.feed.file} at that file. Until then this falls
 * back to a built-in mock so the app still runs. The instant a real file is
 * configured, the routing picks change. That is the payoff of the dataspace step.
 *
 * <p>The transferred file is JSON: a map of district to carbon intensity in
 * gCO2/kWh, e.g. {@code {"Riverside":95,"Uptown":180,"OldTown":300,"Harbor":150}}.
 */
@Service
public class CarbonService {

    private static final Logger log = LoggerFactory.getLogger(CarbonService.class);

    /** Built-in mock feed used until the dataspace file is transferred and configured. */
    private static final Map<String, Double> MOCK_INTENSITY = Map.of(
            "Riverside", 120.0,
            "Uptown", 210.0,
            "OldTown", 260.0,
            "Harbor", 340.0);

    private final String feedFile;
    private final ObjectMapper json = new ObjectMapper();

    public CarbonService(@Value("${carbon.feed.file:REPLACE_ME}") String feedFile) {
        this.feedFile = feedFile;
    }

    /** Current carbon intensity per district, from the transferred file if present, else the mock. */
    public CarbonSnapshot snapshot() {
        Map<String, Double> intensity = readFile();
        String source;
        if (intensity == null || intensity.isEmpty()) {
            intensity = MOCK_INTENSITY;
            source = isConfigured() ? "mock (feed file not found)" : "mock (no dataspace feed configured)";
        } else {
            source = "live (dataspace file)";
        }
        return new CarbonSnapshot(source, intensity, greenScores(intensity));
    }

    private boolean isConfigured() {
        return feedFile != null && !feedFile.isBlank() && !"REPLACE_ME".equals(feedFile);
    }

    private Map<String, Double> readFile() {
        if (!isConfigured()) {
            return null;
        }
        try {
            String body = Files.readString(Path.of(feedFile));
            return json.readValue(body, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            log.warn("Carbon feed file {} unreadable ({}); using mock.", feedFile, e.getMessage());
            return null;
        }
    }

    /** Normalise intensity to 0-100 where the greenest (lowest gCO2/kWh) district scores highest. */
    static Map<String, Integer> greenScores(Map<String, Double> intensity) {
        double min = intensity.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = intensity.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double span = Math.max(max - min, 1e-9);
        Map<String, Integer> scores = new LinkedHashMap<>();
        intensity.forEach((district, value) ->
                scores.put(district, (int) Math.round((max - value) / span * 100)));
        return scores;
    }
}
