 /*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

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
            if (!json.contains("is_blue_verified")
                    && !json.contains("ext_is_blue_verified")
                    && !json.contains("\"verified_type\"")
                    && !json.contains("\"verification\"")
                    && !json.contains("\"verified\"")) {
                return new ByteArrayInputStream(raw);
            }
            String filtered = filterJson(json);
            if (filtered == json) return new ByteArrayInputStream(raw);
            return new ByteArrayInputStream(filtered.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new ByteArrayInputStream(raw);
        }
    }

    /** Return the original text when nothing was filtered or parsing fails. */
    static String filterJson(String input) {
        try {
            Object root = new JSONTokener(input).nextValue();

            // Read follow relationships BEFORE deleting timeline entries.
            Set<String> positivelyFollowed = new HashSet<>();
            Set<String> explicitlyNotFollowed = new HashSet<>();
            collectRelationships(root, positivelyFollowed, explicitlyNotFollowed);
            synchronized (followedVerifiedIds) {
                for (String id : explicitlyNotFollowed) {
                    if (!positivelyFollowed.contains(id)) followedVerifiedIds.remove(id);
                }
                followedVerifiedIds.addAll(positivelyFollowed);
            }

            int removed = walk(root);
            if (removed == 0) return input;
            String output = root.toString();
            return output != null ? output : input;
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

    private static int walk(Object node) {
        int removed = 0;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());

            for (String key : keys) {
                Object value = obj.opt(key);
                if ("instructions".equals(key) && value instanceof JSONArray) {
                    removed += filterInstructions((JSONArray) value);
                } else if ("entries".equals(key) && value instanceof JSONArray) {
                    removed += filterEntries((JSONArray) value);
                }
                removed += walk(value);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) removed += walk(arr.opt(i));
        }
        return removed;
    }

    private static int filterInstructions(JSONArray instructions) {
        int removed = 0;
        for (int i = instructions.length() - 1; i >= 0; i--) {
            JSONObject instr = instructions.optJSONObject(i);
            if (instr == null) continue;

            JSONObject entry = instr.optJSONObject("entry");
            if (entry != null && entryHidden(entry)) {
                instructions.remove(i);
                removed++;
                continue;
            }
            JSONArray moduleItems = instr.optJSONArray("moduleItems");
            if (moduleItems != null) removed += filterItems(moduleItems);
        }
        return removed;
    }

    private static int filterEntries(JSONArray entries) {
        int removed = 0;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;

            if (entryHidden(entry)) {
                entries.remove(i);
                removed++;
                continue;
            }

            JSONObject content = entry.optJSONObject("content");
            JSONArray items = content == null ? null : content.optJSONArray("items");
            if (items != null) {
                int count = filterItems(items);
                removed += count;
                if (count > 0 && items.length() == 0) entries.remove(i);
            }
        }
        return removed;
    }

    private static int filterItems(JSONArray items) {
        int removed = 0;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject module = items.optJSONObject(i);
            JSONObject item = module == null ? null : module.optJSONObject("item");
            JSONObject content = item == null ? null : firstObject(item, "content", "itemContent");
            if (content != null && itemHidden(content)) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static boolean entryHidden(JSONObject entry) {
        JSONObject content = entry.optJSONObject("content");
        if (content == null) return false;
        JSONObject inner = firstObject(content, "content", "itemContent");
        if (inner == null) inner = content;
        return itemHidden(inner);
    }

    private static boolean itemHidden(JSONObject itemContent) {
        JSONObject tweetResult = firstObject(itemContent, "tweetResult", "tweet_results");
        return tweetResult != null && shouldHide(tweetResult.optJSONObject("result"), 0);
    }

    /** Only the tweet author (and the original author of a repost) matters. */
    private static boolean shouldHide(JSONObject result, int depth) {
        if (result == null || depth > 4) return false;
        JSONObject innerTweet = result.optJSONObject("tweet");
        if (innerTweet != null) result = innerTweet;

        JSONObject author = authorOf(result);
        if (userVerified(author)) {
            // Keep every post from verified accounts confirmed as followed.
            if (userFollowed(author)) return false;
            return true;
        }

        // Hide reposts of non-followed verified accounts, but do NOT hide
        // unrelated replies or quotes authored by unverified accounts.
        JSONObject legacy = result.optJSONObject("legacy");
        JSONObject repost = legacy == null ? null : legacy.optJSONObject("retweeted_status_result");
        if (repost == null) repost = result.optJSONObject("retweeted_status_result");
        return repost != null && shouldHide(repost.optJSONObject("result"), depth + 1);
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
