package app.morphe.extension.twitter.patches.bookmarks;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class BookmarkButtonObserverTest {
    @Test public void understandsXBookmarkStateLabels() {
        assertEquals(0, BookmarkButtonObserver.bookmarkState("Bookmark"));
        assertEquals(0, BookmarkButtonObserver.bookmarkState("Add to Bookmarks"));
        assertEquals(1, BookmarkButtonObserver.bookmarkState("Remove Bookmark"));
        assertEquals(1, BookmarkButtonObserver.bookmarkState("Bookmarked"));
        assertEquals(1, BookmarkButtonObserver.bookmarkState("Undo bookmark"));
        assertEquals(-1, BookmarkButtonObserver.bookmarkState("Share"));
    }
}
