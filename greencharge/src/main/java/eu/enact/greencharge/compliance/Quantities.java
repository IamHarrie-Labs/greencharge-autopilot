package eu.enact.greencharge.compliance;

import java.math.BigDecimal;
import java.util.Locale;

/** Conversions between Kubernetes resource quantities and plain numbers. */
public final class Quantities {

    private static final long KI = 1024L;
    private static final long MI = KI * 1024;
    private static final long GI = MI * 1024;

    private Quantities() {
    }

    /** "500m" -> 0.5, "2" -> 2.0. Returns NaN for null or blank. */
    public static double cpuCores(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return Double.NaN;
        }
        String q = quantity.trim();
        if (q.endsWith("m")) {
            return Double.parseDouble(q.substring(0, q.length() - 1)) / 1000.0;
        }
        return Double.parseDouble(q);
    }

    /** 0.5 -> "500m", 2.0 -> "2". */
    public static String cpuString(double cores) {
        if (cores == Math.rint(cores)) {
            return String.valueOf((long) cores);
        }
        return Math.round(cores * 1000) + "m";
    }

    /** "512Mi" -> 536870912, "2Gi" -> 2147483648, "1G" -> 1000000000. Returns -1 for null or blank. */
    public static long bytes(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return -1;
        }
        String q = quantity.trim();
        String[][] units = {
                {"Ki", "1024"}, {"Mi", String.valueOf(MI)}, {"Gi", String.valueOf(GI)},
                {"Ti", String.valueOf(GI * 1024)},
                {"k", "1000"}, {"K", "1000"}, {"M", "1000000"}, {"G", "1000000000"}};
        for (String[] u : units) {
            if (q.endsWith(u[0])) {
                BigDecimal n = new BigDecimal(q.substring(0, q.length() - u[0].length()));
                return n.multiply(new BigDecimal(u[1])).longValue();
            }
        }
        return new BigDecimal(q).longValue();
    }

    /** 2147483648 -> "2Gi", 536870912 -> "512Mi"; uses the largest exact binary unit. */
    public static String toBinaryString(long bytes) {
        if (bytes >= GI && bytes % GI == 0) {
            return (bytes / GI) + "Gi";
        }
        if (bytes >= MI && bytes % MI == 0) {
            return (bytes / MI) + "Mi";
        }
        if (bytes >= GI) {
            return String.format(Locale.ROOT, "%.1fGi", bytes / (double) GI);
        }
        return (bytes / MI) + "Mi";
    }
}
