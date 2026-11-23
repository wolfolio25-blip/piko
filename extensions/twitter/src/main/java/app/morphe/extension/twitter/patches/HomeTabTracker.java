/*
 * Detect X's currently visible home tab without altering its native layout.
 * HomeTimeline and HomeLatestTimeline may share data.home.home_timeline_urt,
 * so the response JSON alone cannot distinguish For You from Following.
 *
 * This is a best-effort UI signal: ambiguous tab appearances retain last state,
 * and no other screens are changed.
 */
package app.morphe.extension.twitter.patches;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.Locale;

public final class HomeTabTracker {
    public static final int UNKNOWN = 0;
    public static final int FOR_YOU = 1;
    public static final int FOLLOWING = 2;
    private static volatile int selectedTab = UNKNOWN;

    private HomeTabTracker() {}

    public static int current() { return selectedTab; }

    public static void scan(View root) {
        if (root == null) return;
        try {
            TextView[] tabs = new TextView[2];
            findTabs(root, tabs, 0, new int[]{500});
            if (tabs[0] == null || tabs[1] == null) {
                PatchDiagnostics.hit("home_tab_labels_not_found");
                return;
            }
            PatchDiagnostics.hit("home_tab_labels_found");
            boolean forYou = selected(tabs[0]);
            boolean following = selected(tabs[1]);
            if (forYou != following) {
                selectedTab = following ? FOLLOWING : FOR_YOU;
                PatchDiagnostics.hit("home_tab_selection_explicit");
                PatchDiagnostics.value("home_tab_current", selectedTab);
                return;
            }
            // X themes mark the selected tab with its stronger text contrast.
            int bg = app.morphe.extension.twitter.Utils.resolveColor(
                    root.getContext(), "coreColorAppBackground");
            double a = contrast(tabs[0].getCurrentTextColor(), bg);
            double b = contrast(tabs[1].getCurrentTextColor(), bg);
            if (Math.abs(a - b) >= 25) {
                selectedTab = a > b ? FOR_YOU : FOLLOWING;
                PatchDiagnostics.hit("home_tab_selection_contrast");
                PatchDiagnostics.value("home_tab_current", selectedTab);
            } else PatchDiagnostics.hit("home_tab_selection_unknown");
        } catch (Throwable ignored) {}
    }

    private static void findTabs(View view, TextView[] tabs, int depth, int[] remaining) {
        if (view == null || depth > 18 || remaining[0]-- <= 0) return;
        if (view instanceof TextView && view.isShown()) {
            String value = String.valueOf(((TextView) view).getText()).trim()
                    .toLowerCase(Locale.ROOT);
            if ("for you".equals(value)) tabs[0] = (TextView) view;
            else if ("following".equals(value)) tabs[1] = (TextView) view;
        }
        if (tabs[0] != null && tabs[1] != null) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && remaining[0] > 0; i++) {
                findTabs(group.getChildAt(i), tabs, depth + 1, remaining);
                if (tabs[0] != null && tabs[1] != null) break;
            }
        }
    }

    private static boolean selected(View view) {
        View current = view;
        for (int i = 0; current != null && i < 3; i++) {
            if (current.isSelected() || current.isActivated()) return true;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static double contrast(int foreground, int background) {
        return Math.abs(Color.red(foreground) - Color.red(background))
                + Math.abs(Color.green(foreground) - Color.green(background))
                + Math.abs(Color.blue(foreground) - Color.blue(background));
    }
}
