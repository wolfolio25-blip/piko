package app.morphe.extension.twitter.patches;

import com.twitter.model.core.b;
import com.twitter.model.json.timeline.urt.JsonTimelineItem;
import com.twitter.model.json.timeline.urt.JsonTimelineTweet;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeFeedEntryFilterTest {
    public static final class NativeEntry {
        public Object c;
    }
    public static final class NativeModuleItem {
        public Object b;
    }
    private void home(int tab, boolean visible) throws Exception {
        Field current = HomeTabTracker.class.getDeclaredField("selectedTab");
        current.setAccessible(true);
        current.setInt(null, tab);
        Field visibleField = HomeTabTracker.class.getDeclaredField("homeTabsVisible");
        visibleField.setAccessible(true);
        visibleField.setBoolean(null, visible);
    }
    @After public void clean() throws Exception { home(0, false); }
    private JsonTimelineItem item(boolean repost) {
        b.a payload = new b.a();
        payload.c = repost ? "retweetedStatusId" : null;
        JsonTimelineTweet tweet = new JsonTimelineTweet();
        tweet.a = payload;
        JsonTimelineItem item = new JsonTimelineItem();
        item.a = tweet;
        return item;
    }
    @Test public void followingRemovesOnlyRepostedNativeTweet() throws Exception {
        home(2, true);
        NativeEntry retweet = new NativeEntry(); retweet.c = item(true);
        NativeEntry original = new NativeEntry(); original.c = item(false);
        assertTrue(NativeFeedEntryFilter.hideEntry(retweet));
        assertFalse(NativeFeedEntryFilter.hideEntry(original));
    }
    @Test public void homeFiltersNeverHideProfileOrOpenedTweet() throws Exception {
        NativeEntry retweet = new NativeEntry(); retweet.c = item(true);
        home(2, false);
        assertFalse(NativeFeedEntryFilter.hideEntry(retweet));
        home(1, true);
        assertFalse(NativeFeedEntryFilter.hideEntry(retweet));
    }
    @Test public void forYouRemovesQuotedNativeTweetOnly() throws Exception {
        home(1, true);
        NativeEntry quote = new NativeEntry();
        JsonTimelineItem quoteItem = item(false);
        JsonTimelineTweet content = (JsonTimelineTweet) quoteItem.a;
        b.a payload = (b.a) content.a;
        payload.g = new com.twitter.model.core.d.b();
        payload.g.Z = new com.twitter.model.core.d();
        quote.c = quoteItem;
        NativeEntry original = new NativeEntry();
        original.c = item(false);
        assertTrue("Native quote should disappear in For You",
                NativeFeedEntryFilter.hideEntry(quote));
        assertFalse(NativeFeedEntryFilter.hideEntry(original));
        home(2, true);
        assertFalse("Following should keep quote posts if not a repost",
                NativeFeedEntryFilter.hideEntry(quote));
    }

    @Test public void followingFiltersNativeModuleItems() throws Exception {
        home(2, true);
        NativeModuleItem item = new NativeModuleItem(); item.b = item(true);
        assertTrue(NativeFeedEntryFilter.hideModuleItem(item));
    }
}
