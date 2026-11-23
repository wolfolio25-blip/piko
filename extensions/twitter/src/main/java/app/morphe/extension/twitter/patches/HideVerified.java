 /*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Hides posts authored by verified accounts UNLESS the viewer follows them.
 *
 * Keeps ordinary replies and quote-posts from unverified authors visible.
 * Reposts of hidden verified posts are hidden as well.
 *
 * X does not include the viewer's follow relationship in every response.
 * We therefore remember verified accounts confirmed as followed when their
 * relationship data does appear. If a relationship is never supplied, the
 * account cannot reliably be recognized as followed and may still be hidden.
 *
 * Processes raw JSON server responses before X parses them.
 */
public final class HideVerified {

    private static final Set<String> knownVerifiedIds =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> knownVerifiedHandles =
            Collections.synchronizedSet(new HashSet<String>());

    private enum Feed { FOR_YOU, FOLLOWING, SEARCH }

    private static boolean enabled(app.morphe.extension.crimera.settings.BooleanSetting setting) {
        try { return Utils.getBooleanPref(setting); }
        catch (Throwable ignored) { return true; }
    }

    private static final Set<String> followedVerifiedIds =
            Collections.synchronizedSet(new HashSet<String>());

    /** Wrap the JSON response stream without changing responses on failure. */
    public static InputStream filter(InputStream in) {
        if (in == null) return null;
        byte[] raw;
        try {
            raw = readAll(in);
            in.close();
        } catch (Throwable t) {
            return in;
        }

        try {
            String json = new String(raw, StandardCharsets.UTF_8);
            if (!json.contains("\"home\"") && !json.contains("search_by_raw_query")
                    && !json.contains("search_by_keyword")
                    && !json.contains("\"is_blue_verified\"")
                    && !json.contains("\"verified_type\"")) {
                return new ByteArrayInputStream(raw);
            }
            String filtered = filterJson(json);
            if (filtered == json) return new ByteArrayInputStream(raw);
            return new ByteArrayInputStream(filtered.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new ByteArrayInputStream(raw);
        }
    }

    /** Never filter user-profile timelines, tweet detail, or open conversations. */
    static String filterJson(String input) {
        try {
            Object rootValue = new JSONTokener(input).nextValue();
            if (!(rootValue instanceof JSONObject)) return input;
            JSONObject root = (JSONObject) rootValue;
            JSONObject data = root.optJSONObject("data");
            if (data == null) return input;

            JSONObject home = data.optJSONObject("home");
            JSONObject target = null;
            Feed feed = null;
            if (home != null) {
                // X exposes separate GraphQL timeline fields for For You and Following.
                target = firstObject(home, "home_latest_timeline_urt", "home_latest_timeline");
                if (target != null) feed = Feed.FOLLOWING;
                else {
                    target = firstObject(home, "home_timeline_urt", "home_timeline_v2",
                            "home_timeline");
                    if (target != null) {
                        feed = HomeTabTracker.current() == HomeTabTracker.FOLLOWING
                                ? Feed.FOLLOWING : Feed.FOR_YOU;
                    }
                }
            }
            if (feed == null) {
                target = firstObject(data, "search_by_raw_query", "search_by_keyword");
                if (target != null) feed = Feed.SEARCH;
            }
            // Learn verified IDs/handles on profile and tweet-detail views too,
            // but NEVER filter their responses. Thus a user may click through
            // and read the original post even if it was filtered from the home feed.
            if (feed == null || target == null) {
                collectRelationships(data, new HashSet<>(), new HashSet<>());
                return input;
            }

            Set<String> positivelyFollowed = new HashSet<>();
            Set<String> explicitlyNotFollowed = new HashSet<>();
            collectRelationships(target, positivelyFollowed, explicitlyNotFollowed);
            synchronized (followedVerifiedIds) {
                for (String id : explicitlyNotFollowed)
                    if (!positivelyFollowed.contains(id)) followedVerifiedIds.remove(id);
                followedVerifiedIds.addAll(positivelyFollowed);
            }

            int removed = walk(target, feed);
            return removed == 0 ? input : root.toString();
        } catch (Throwable t) {
            return input;
        }
    }

    /** Learn which verified users the viewer currently follows. */
    private static void collectRelationships(Object node,
                                             Set<String> followed,
                                             Set<String> notFollowed) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (userVerified(obj)) {
                String id = userId(obj);
                if (!id.isEmpty()) knownVerifiedIds.add(id);
                String handle = userHandle(obj);
                if (!handle.isEmpty()) knownVerifiedHandles.add(handle.toLowerCase(java.util.Locale.ROOT));
                Boolean following = followingStatus(obj);
                if (!id.isEmpty() && following != null) {
                    if (following) followed.add(id);
                    else notFollowed.add(id);
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                collectRelationships(obj.opt(keys.next()), followed, notFollowed);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectRelationships(arr.opt(i), followed, notFollowed);
            }
        }
    }

    private static int walk(Object node, Feed feed) {
        int removed = 0;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());

            for (String key : keys) {
                Object value = obj.opt(key);
                if ("instructions".equals(key) && value instanceof JSONArray) {
                    removed += filterInstructions((JSONArray) value, feed);
                } else if ("entries".equals(key) && value instanceof JSONArray) {
                    removed += filterEntries((JSONArray) value, feed);
                }
                removed += walk(value, feed);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) removed += walk(arr.opt(i), feed);
        }
        return removed;
    }

    private static int filterInstructions(JSONArray instructions, Feed feed) {
        int removed = 0;
        for (int i = instructions.length() - 1; i >= 0; i--) {
            JSONObject instr = instructions.optJSONObject(i);
            if (instr == null) continue;

            JSONObject entry = instr.optJSONObject("entry");
            if (entry != null && entryHidden(entry, feed)) {
                instructions.remove(i);
                removed++;
                continue;
            }
            JSONArray moduleItems = instr.optJSONArray("moduleItems");
            if (moduleItems != null) removed += filterItems(moduleItems, feed);
        }
        return removed;
    }

    private static int filterEntries(JSONArray entries, Feed feed) {
        int removed = 0;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;

            if (entryHidden(entry, feed)) {
                entries.remove(i);
                removed++;
                continue;
            }

            JSONObject content = entry.optJSONObject("content");
            JSONArray items = content == null ? null : content.optJSONArray("items");
            if (items != null) {
                int count = filterItems(items, feed);
                removed += count;
                if (count > 0 && items.length() == 0) entries.remove(i);
            }
        }
        return removed;
    }

    private static int filterItems(JSONArray items, Feed feed) {
        int removed = 0;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject module = items.optJSONObject(i);
            JSONObject item = module == null ? null : module.optJSONObject("item");
            JSONObject content = item == null ? null : firstObject(item, "content", "itemContent");
            if (content != null && itemHidden(content, feed)) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static boolean entryHidden(JSONObject entry, Feed feed) {
        JSONObject content = entry.optJSONObject("content");
        if (content == null) return false;
        JSONObject inner = firstObject(content, "content", "itemContent");
        if (inner == null) inner = content;
        return itemHidden(inner, feed);
    }

    private static boolean itemHidden(JSONObject itemContent, Feed feed) {
        JSONObject tweetResult = firstObject(itemContent, "tweetResult", "tweet_results");
        return tweetResult != null && shouldHide(tweetResult.optJSONObject("result"), feed, 0);
    }

    /**
     * Filter only the top-level home/search entry, never mutate referenced
     * originals. This keeps tapped replies, quotes and repost targets accessible.
     */
    private static boolean shouldHide(JSONObject result, Feed feed, int depth) {
        if (result == null || depth > 4) return false;
        JSONObject innerTweet = result.optJSONObject("tweet");
        if (innerTweet != null) result = innerTweet;

        JSONObject legacy = result.optJSONObject("legacy");
        JSONObject repost = firstObject(result, "retweeted_status_result");
        if (repost == null && legacy != null)
            repost = legacy.optJSONObject("retweeted_status_result");
        boolean isRepost = repost != null ||
                (legacy != null && !legacy.optString("retweeted_status_id_str", "").isEmpty()) ||
                (legacy != null && !legacy.optString("retweeted_status_id", "").isEmpty()) ||
                !result.optString("retweeted_status_id_str", "").isEmpty();

        if (feed == Feed.FOLLOWING) {
            return isRepost && enabled(Settings.TIMELINE_HIDE_FOLLOWING_REPOSTS);
        }

        if (feed == Feed.FOR_YOU && enabled(Settings.TIMELINE_HIDE_FOR_YOU_QUOTES)) {
            boolean quote = (legacy != null && legacy.optBoolean("is_quote_status", false))
                    || result.optBoolean("is_quote_status", false)
                    || (legacy != null && legacy.optJSONObject("quoted_status_result") != null)
                    || result.optJSONObject("quoted_status_result") != null
                    || (legacy != null && !legacy.optString("quoted_status_id_str", "").isEmpty())
                    || !result.optString("quoted_status_id_str", "").isEmpty();
            if (quote) return true;
        }

        if (feed == Feed.FOR_YOU && enabled(Settings.TIMELINE_HIDE_REPLIES_TO_VERIFIED)
                && legacy != null) {
            String replyUserId = legacy.optString("in_reply_to_user_id_str",
                    legacy.optString("in_reply_to_user_id", ""));
            if (!replyUserId.isEmpty() && knownVerifiedIds.contains(replyUserId))
                return true;
            String replyHandle = legacy.optString("in_reply_to_screen_name", "");
            if (!replyHandle.isEmpty() && knownVerifiedHandles.contains(
                    replyHandle.toLowerCase(java.util.Locale.ROOT))) return true;
            JSONObject parent = firstObject(result, "in_reply_to_status_result");
            if (parent == null) parent = firstObject(legacy, "in_reply_to_status_result");
            if (parent != null && userVerified(authorOf(parent.optJSONObject("result"))))
                return true;
        }

        JSONObject author = authorOf(result);
        if (userVerified(author) && !userFollowed(author)) return true;

        // Reposts of verified accounts not followed: retain original payload
        // for detail views; remove only this home/search timeline entry.
        return repost != null && shouldHide(repost.optJSONObject("result"), feed, depth + 1);
    }

    /** Current and older X GraphQL user-result shapes. */
    private static JSONObject authorOf(JSONObject result) {
        JSONObject core = result == null ? null : result.optJSONObject("core");
        JSONObject userResults = firstObject(core, "user_result", "user_results");
        return userResults == null ? null : userResults.optJSONObject("result");
    }

    private static JSONObject firstObject(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            JSONObject child = obj.optJSONObject(key);
            if (child != null) return child;
        }
        return null;
    }

    /** The API supplies this only in some user/profile responses. */
    private static Boolean followingStatus(JSONObject user) {
        if (user == null) return null;
        JSONObject perspectives = user.optJSONObject("relationship_perspectives");
        if (perspectives != null && perspectives.has("following")
                && !perspectives.isNull("following")) {
            return perspectives.optBoolean("following", false);
        }
        JSONObject legacy = user.optJSONObject("legacy");
        if (legacy != null && legacy.has("following")
                && !legacy.isNull("following")) {
            return legacy.optBoolean("following", false);
        }
        return null;
    }

    private static boolean userFollowed(JSONObject user) {
        Boolean status = followingStatus(user);
        if (status != null) return status;
        String id = userId(user);
        return !id.isEmpty() && followedVerifiedIds.contains(id);
    }

    private static String userHandle(JSONObject user) {
        if (user == null) return "";
        String handle = user.optString("screen_name", "");
        JSONObject legacy = user.optJSONObject("legacy");
        if (handle.isEmpty() && legacy != null) handle = legacy.optString("screen_name", "");
        JSONObject core = user.optJSONObject("core");
        if (handle.isEmpty() && core != null) handle = core.optString("screen_name", "");
        return handle;
    }

    private static String userId(JSONObject user) {
        if (user == null) return "";
        String id = user.optString("rest_id", "");
        if (!id.isEmpty()) return id;
        JSONObject legacy = user.optJSONObject("legacy");
        if (legacy != null) {
            id = legacy.optString("id_str", "");
            if (!id.isEmpty()) return id;
        }
        return user.optString("id_str", "");
    }

    private static boolean userVerified(JSONObject user) {
        if (user == null) return false;
        if (user.optBoolean("is_blue_verified", false)
                || user.optBoolean("ext_is_blue_verified", false)) return true;

        JSONObject verification = user.optJSONObject("verification");
        if (verification != null) {
            if (verification.optBoolean("verified", false)
                    || hasVerifiedType(verification)) return true;
        }
        if (hasVerifiedType(user)) return true;

        JSONObject legacy = user.optJSONObject("legacy");
        return legacy != null && legacy.optBoolean("verified", false);
    }

    private static boolean hasVerifiedType(JSONObject user) {
        if (!user.has("verified_type") || user.isNull("verified_type")) return false;
        String type = user.optString("verified_type", "");
        return !type.isEmpty()
                && !"None".equalsIgnoreCase(type)
                && !"null".equalsIgnoreCase(type);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private HideVerified() {}
}
