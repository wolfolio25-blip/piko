package app.morphe.extension.twitter.patches.bookmarks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Protect against selected folders exposing every X bookmark on unknown entry IDs. */
public final class BookmarkNativeTimelineTest {
    @Before public void setup() throws Exception {
        Class<?> selection = Class.forName(
                "app.morphe.extension.twitter.patches.bookmarks.BookmarkNativeTimeline$Selection");
        Constructor<?> constructor = selection.getDeclaredConstructor(String.class, Set.class);
        constructor.setAccessible(true);
        Object active = constructor.newInstance("Test", new HashSet<>(Collections.singletonList("12345")));
        Field selected = BookmarkNativeTimeline.class.getDeclaredField("selected");
        selected.setAccessible(true);
        selected.set(null, active);
        BookmarkNativeTimeline.setBookmarkScreenVisible(true);
    }

    @After public void cleanup() {
        BookmarkNativeTimeline.clearFolder();
        BookmarkNativeTimeline.setBookmarkScreenVisible(false);
    }

    @Test public void onlyFolderTweetIdPasses() {
        assertTrue(BookmarkNativeTimeline.keepNativeEntryId("tweet-12345"));
        assertFalse(BookmarkNativeTimeline.keepNativeEntryId("tweet-99999"));
        assertFalse(BookmarkNativeTimeline.keepNativeEntryId("bookmark-99999"));
        assertFalse(BookmarkNativeTimeline.keepNativeEntryId("tweet-unrecognised"));
        assertFalse(BookmarkNativeTimeline.keepNativeEntryId("unrecognised"));
        assertTrue(BookmarkNativeTimeline.keepNativeEntryId("cursor-bottom-0"));
    }

    @Test public void removingBookmarkImmediatelyHidesNativeEntry() {
        BookmarkNativeTimeline.removeTweetId("12345");
        assertFalse(BookmarkNativeTimeline.keepNativeEntryId("tweet-12345"));
    }

    @Test public void generalBookmarkPageIsNeverFiltered() {
        BookmarkNativeTimeline.clearFolder();
        assertTrue(BookmarkNativeTimeline.keepNativeEntryId("tweet-99999"));
    }
}
