package app.morphe.extension.twitter.patches;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class HideVerifiedTimelineTest {
    private void tab(int value) throws Exception {
        Field selected = HomeTabTracker.class.getDeclaredField("selectedTab");
        selected.setAccessible(true);
        selected.setInt(null, value);
        Field visible = HomeTabTracker.class.getDeclaredField("homeTabsVisible");
        visible.setAccessible(true);
        visible.setBoolean(null, value != 0);
    }

    private JSONObject entry(String id, JSONObject legacy) throws Exception {
        JSONObject result = new JSONObject().put("rest_id", id).put("legacy", legacy);
        JSONObject content = new JSONObject().put("itemContent",
                new JSONObject().put("tweet_results", new JSONObject().put("result", result)));
        return new JSONObject().put("entryId", "tweet-" + id).put("content", content);
    }

    private String timeline(String field, JSONObject... entries) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) array.put(entry);
        JSONObject obj = new JSONObject().put("timeline",
                new JSONObject().put("instructions", new JSONArray().put(
                        new JSONObject().put("entries", array))));
        return new JSONObject().put("data", new JSONObject().put("home",
                new JSONObject().put(field, obj))).toString();
    }

    @org.junit.Before public void setup() { HideVerified.overrideEnabledForTest(true); }
    @After public void reset() throws Exception {
        tab(0);
        HideVerified.overrideEnabledForTest(null);
    }

    @Test public void forYouRemovesQuotesButNotOriginals() throws Exception {
        tab(1);
        String json = timeline("home_timeline_urt",
                entry("111", new JSONObject().put("is_quote_status", true)),
                entry("222", new JSONObject().put("full_text", "Original post")));
        String result = HideVerified.filterJson(json);
        assertFalse("Quote tweet must be removed: " + result, result.contains("tweet-111"));
        assertTrue(result.contains("tweet-222"));
    }

    @Test public void followingRemovesIdOnlyRepostsButKeepsOtherTweets() throws Exception {
        tab(2);
        String json = timeline("home_timeline_urt",
                entry("333", new JSONObject().put("retweeted_status_id_str", "123456")),
                entry("444", new JSONObject().put("full_text", "Original post")));
        String result = HideVerified.filterJson(json);
        assertFalse("Repost must be removed: " + result, result.contains("tweet-333"));
        assertTrue(result.contains("tweet-444"));
    }

    @Test public void unwrappedHomeTimelineRemovesQuoteOnlyWhenHomeVisible() throws Exception {
        tab(1);
        JSONObject wrappedTimeline = new JSONObject()
                .put("instructions", new JSONArray().put(
                        new JSONObject().put("entries", new JSONArray()
                                .put(entry("111", new JSONObject().put("is_quote_status", true)))
                                .put(entry("222", new JSONObject().put("full_text", "normal"))))));
        String json = new JSONObject().put("timeline", wrappedTimeline).toString();
        String home = HideVerified.filterJson(json);
        assertFalse("Unwrapped home quote should be filtered: " + home,
                home.contains("tweet-111"));
        assertTrue(home.contains("tweet-222"));
        tab(0);
        assertEquals("Non-home view must preserve originals", json,
                HideVerified.filterJson(json));
    }

    @Test public void profileTimelineIsNeverFiltered() throws Exception {
        tab(1);
        JSONObject verifiedUser = new JSONObject().put("rest_id", "75")
                .put("is_blue_verified", true);
        JSONObject tweet = new JSONObject().put("rest_id", "555")
                .put("core", new JSONObject().put("user_results",
                        new JSONObject().put("result", verifiedUser)));
        JSONObject item = new JSONObject().put("tweet_results",
                new JSONObject().put("result", tweet));
        JSONObject entry = new JSONObject().put("content",
                new JSONObject().put("itemContent", item));
        JSONArray entries = new JSONArray().put(entry);
        JSONObject user = new JSONObject().put("timeline",
                new JSONObject().put("instructions",
                        new JSONArray().put(new JSONObject().put("entries", entries))));
        String json = new JSONObject().put("data",
                new JSONObject().put("user", user)).toString();
        assertEquals(json, HideVerified.filterJson(json));
    }

}
