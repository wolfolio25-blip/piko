package app.morphe.extension.twitter.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PatchDiagnosticsTest {
    @Test public void reportNeverStoresIdentifiersOrArbitraryText() {
        PatchDiagnostics.reset();
        PatchDiagnostics.hit("feed_classified_FOR_YOU");
        PatchDiagnostics.hit("user_name=private");
        PatchDiagnostics.hit("https://x.com/secret/status/123");
        PatchDiagnostics.value("bookmark_ui_tabs_top_px", 123);
        String report = PatchDiagnostics.snapshot();
        assertTrue(report.contains("feed_classified_FOR_YOU = 1"));
        assertTrue(report.contains("bookmark_ui_tabs_top_px = 123"));
        assertFalse(report.contains("private"));
        assertFalse(report.contains("x.com"));
        PatchDiagnostics.reset();
    }
}
