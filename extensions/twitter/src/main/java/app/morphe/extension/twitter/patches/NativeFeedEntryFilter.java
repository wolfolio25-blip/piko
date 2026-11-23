package app.morphe.extension.twitter.patches;

import java.lang.reflect.Field;

/**
 * Filters X 12.19.1's actual URT model entries. The Jackson InputStream hook
 * does not consistently see home timeline JSON on this version.
 *
 * Confirmed stock class chain:
 *   JsonTimelineEntry.c -> JsonTimelineItem.a -> JsonTimelineTweet.a
 *   -> com.twitter.model.core.b$a
 * Stock b$a.c holds the retweetedStatusId, not a tweet's display text.
 *
 * Entries are removed only from home feeds, never profiles or tweet detail.
 */
public final class NativeFeedEntryFilter {
    private NativeFeedEntryFilter() {}

    private static Object field(Object object, String name) {
        if (object == null) return null;
        try {
            Field fld = object.getClass().getField(name);
            return fld.get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isRepostPayload(Object payload) {
        if (payload == null) return false;
        // The type and c field were confirmed against X 12.19.1's actual
        // API Tweet builder, not similarly named fields on other classes.
        if (!"com.twitter.model.core.b$a".equals(payload.getClass().getName()))
            return false;
        Object id = field(payload, "c");
        return id instanceof String && !((String) id).isEmpty();
    }

    static boolean isQuotedPayload(Object payload) {
        if (payload == null ||
                !"com.twitter.model.core.b$a".equals(payload.getClass().getName()))
            return false;
        Object status = field(payload, "g");
        Object quotedStatus = field(status, "Z");
        // X's status model contains a nested status for quote posts.
        // Do not classify ordinary reposts from the retweetedStatusId field.
        return quotedStatus != null &&
                "com.twitter.model.core.d".equals(quotedStatus.getClass().getName());
    }

    private static boolean forYouHideQuotes() {
        try {
            return app.morphe.extension.twitter.Utils.getBooleanPref(
                    app.morphe.extension.twitter.settings.Settings.TIMELINE_HIDE_FOR_YOU_QUOTES);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean followingHideReposts() {
        try {
            return app.morphe.extension.twitter.Utils.getBooleanPref(
                    app.morphe.extension.twitter.settings.Settings.TIMELINE_HIDE_FOLLOWING_REPOSTS);
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean shouldHideTweetContent(Object content) {
        if (content == null) return false;
        if (!"com.twitter.model.json.timeline.urt.JsonTimelineTweet".equals(
                content.getClass().getName())) return false;
        Object payload = field(content, "a");
        if (payload == null) return false;
        if (HomeTabTracker.current() == HomeTabTracker.FOLLOWING)
            return followingHideReposts() && isRepostPayload(payload);
        if (HomeTabTracker.current() == HomeTabTracker.FOR_YOU)
            return forYouHideQuotes() && isQuotedPayload(payload);
        return false;
    }

    public static boolean hideEntry(Object entry) {
        if (!HomeTabTracker.isHomeVisible()) return false;
        Object entryContent = field(entry, "c");
        Object tweetContent = field(entryContent, "a");
        return shouldHideTweetContent(tweetContent);
    }

    public static boolean hideModuleItem(Object module) {
        if (!HomeTabTracker.isHomeVisible()) return false;
        Object item = field(module, "b");
        Object tweetContent = field(item, "a");
        return shouldHideTweetContent(tweetContent);
    }
}
