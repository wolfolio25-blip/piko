package app.morphe.extension.twitter.patches;

import java.util.Map;
import java.util.TreeMap;

/**
 * Local, metadata-only X patch diagnostics. No tweet IDs, tweet text, account
 * names, tokens, URLs or response bodies are retained.
 */
public final class PatchDiagnostics {
    private static final Map<String, Long> COUNTS = new TreeMap<>();
    private static final Map<String, Integer> LAST_VALUES = new TreeMap<>();
    private static final int MAX_KEYS = 100;

    private PatchDiagnostics() {}

    private static String key(String input) {
        if (input == null || input.length() > 65 ||
                !input.matches("[A-Za-z0-9_.-]+")) return null;
        return input;
    }

    public static synchronized void hit(String category) {
        String name = key(category);
        if (name == null || (!COUNTS.containsKey(name) && COUNTS.size() >= MAX_KEYS)) return;
        long value = COUNTS.containsKey(name) ? COUNTS.get(name) : 0L;
        COUNTS.put(name, Math.min(value + 1, 100000000L));
    }

    public static synchronized void value(String category, int value) {
        String name = key(category);
        if (name == null || (!LAST_VALUES.containsKey(name) && LAST_VALUES.size() >= MAX_KEYS)) return;
        LAST_VALUES.put(name, Math.max(-10000000, Math.min(value, 10000000)));
    }

    public static synchronized String snapshot() {
        StringBuilder out = new StringBuilder("Vantage native diagnostics (no account/tweet data)\n");
        out.append("X target: 12.19.1\n");
        out.append("Counts:\n");
        for (Map.Entry<String, Long> e : COUNTS.entrySet())
            out.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        out.append("Values:\n");
        for (Map.Entry<String, Integer> e : LAST_VALUES.entrySet())
            out.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        return out.toString();
    }

    public static synchronized void reset() {
        COUNTS.clear();
        LAST_VALUES.clear();
    }
}
