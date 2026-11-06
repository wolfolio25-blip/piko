/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Filter X's own Bookmarks timeline response rather than drawing fake tweet
 * cards. X's normal feed adapter still constructs every post, with its real
 * author/media/interaction views. Cursors must be retained for pagination.
 *
 * This does not manufacture private GraphQL requests, authenticate separately
 * or use Premium's server-side folder endpoints.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class BookmarkNativeTimeline {
    private static final String TAG = "PikoNativeBookmarks";
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;

    private static volatile Selection selected;

    private static final class Selection {
        final String folder;
        final Set<String> ids;
        Selection(String folder, Set<String> ids) {
            this.folder = folder;
            this.ids = Collections.unmodifiableSet(new HashSet<>(ids));
        }
    }

    private BookmarkNativeTimeline() {}

    public static void selectFolder(Context context, String folder) {
        Set<String> ids = new HashSet<>();
        JSONArray folders = BookmarkFolders.read(context);
        for (int i = 0; i < folders.length(); i++) {
            JSONObject group = folders.optJSONObject(i);
            if (group == null || !folder.equals(group.optString("name"))) continue;
            JSONArray posts = group.optJSONArray("posts");
            if (posts != null) {
                for (int n = 0; n < posts.length(); n++) {
                    JSONObject post = posts.optJSONObject(n);
                    if (post == null) continue;
                    String id = post.optString("id", "");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            break;
        }
        selected = new Selection(folder, ids);
        Log.i(TAG, "Selected folder for native timeline: " + folder + " (" + ids.size() + " ids)");
    }

    public static void clearFolder() { selected = null; }

    public static String selectedFolder() {
        Selection s = selected;
        return s == null ? null : s.folder;
    }

    public static boolean isFiltering() { return selected != null; }

    /**
     * Called at the existing Jackson InputStream parser hook.
     * Only touches a recognisable Bookmarks response, never home/search/replies.
     * On any unexpected schema or exception it returns the original response.
     */
    public static InputStream filter(InputStream in) {
        Selection active = selected;
        if (in == null || active == null) return in;

        byte[] bytes;
        try {
            bytes = readBytes(in);
            in.close();
        } catch (Exception exception) {
            Log.w(TAG, "Could not inspect Bookmarks response", exception);
            return in;
        }

        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (!isBookmarkResponse(json)) return new ByteArrayInputStream(bytes);
            String rewritten = filterBookmarksJson(json, active.ids);
            if (rewritten == null || rewritten.equals(json)) return new ByteArrayInputStream(bytes);
            return new ByteArrayInputStream(rewritten.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable exception) {
            Log.w(TAG, "Timeline filter failed; retaining original", exception);
            return new ByteArrayInputStream(bytes);
        }
    }

    private static byte[] readBytes(InputStream source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int length = 0;
        while ((read = source.read(buffer)) != -1) {
            length += read;
            if (length > MAX_RESPONSE_BYTES) {
                // Still read the rest to return a valid equivalent stream.
                // Large JSON responses should not be modified.
                output.write(buffer, 0, read);
                while ((read = source.read(buffer)) != -1) output.write(buffer, 0, read);
                return output.toByteArray();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isBookmarkResponse(String text) {
        return text.contains("\"bookmark_timeline_v2\"") ||
                text.contains("\"bookmark_timeline\"") ||
                text.contains("\"bookmarks_timeline\"");
    }

    static String filterBookmarksJson(String json, Set<String> allowed) {
        try {
            JSONObject root = new JSONObject(new JSONTokener(json));
            JSONObject data = root.optJSONObject("data");
            if (data == null) return json;
            JSONObject bookmarks = first(data,
                    "bookmark_timeline_v2", "bookmark_timeline", "bookmarks_timeline");
            if (bookmarks == null) return json;
            JSONObject timeline = bookmarks.optJSONObject("timeline");
            if (timeline == null) timeline = bookmarks;
            JSONArray instructions = timeline.optJSONArray("instructions");
            if (instructions == null) return json;

            int removed = 0;
            for (int i = 0; i < instructions.length(); i++) {
                JSONObject instruction = instructions.optJSONObject(i);
                if (instruction == null) continue;
                JSONArray entries = instruction.optJSONArray("entries");
                if (entries != null) removed += filterEntries(entries, allowed);
                JSONObject entry = instruction.optJSONObject("entry");
                if (entry != null && !keepEntry(entry, allowed)) {
                    // Retain instruction structure instead of returning null.
                    instruction.remove("entry");
                    removed++;
                }
                JSONArray addModuleItems = instruction.optJSONArray("moduleItems");
                if (addModuleItems != null) removed += filterModuleItems(addModuleItems, allowed);
            }
            if (removed == 0) return json;
            Log.i(TAG, "Filtered " + removed + " native bookmark timeline items");
            return root.toString();
        } catch (Throwable e) {
            Log.w(TAG, "Unrecognised bookmark response, keeping original", e);
            return json;
        }
    }

    private static int filterEntries(JSONArray entries, Set<String> ids) {
        int removed = 0;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            if (!keepEntry(entry, ids)) {
                entries.remove(i);
                removed++;
                continue;
            }
            JSONObject content = entry.optJSONObject("content");
            if (content != null) {
                JSONArray modules = content.optJSONArray("items");
                if (modules != null) {
                    removed += filterModuleItems(modules, ids);
                    if (modules.length() == 0) {
                        entries.remove(i);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private static int filterModuleItems(JSONArray items, Set<String> ids) {
        int removed = 0;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject module = items.optJSONObject(i);
            if (module == null) continue;
            JSONObject nested = first(module, "item", "content", "itemContent");
            if (nested == null) nested = module;
            String tweetId = tweetId(nested);
            if (!tweetId.isEmpty() && !ids.contains(tweetId)) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static boolean keepEntry(JSONObject entry, Set<String> ids) {
        JSONObject content = entry.optJSONObject("content");
        String type = content == null ? "" :
                content.optString("entryType", content.optString("__typename", ""));
        String entryId = entry.optString("entryId", "");

        // Keep X's cursor items intact so the native list can load older pages.
        if ("TimelineTimelineCursor".equals(type) || entryId.startsWith("cursor-")) return true;

        String id = tweetId(entry);
        if (!id.isEmpty()) return ids.contains(id);

        // A tweet with an unrecognised ID must not be shown in the wrong folder.
        // Other timeline items such as footers and load indicators are preserved.
        if ("TimelineTimelineItem".equals(type) ||
                "TimelineTweet".equals(type) ||
                entryId.startsWith("tweet-")) return false;
        return true;
    }

    private static String tweetId(JSONObject node) {
        if (node == null) return "";
        JSONObject content = first(node, "content", "itemContent", "item");
        if (content == null) content = node;
        JSONObject actual = first(content, "itemContent", "content");
        if (actual != null) content = actual;
        JSONObject tweetResults = first(content, "tweet_results", "tweetResult");
        JSONObject result = tweetResults == null ? null : tweetResults.optJSONObject("result");
        if (result != null) {
            JSONObject tweet = result.optJSONObject("tweet");
            if (tweet != null) result = tweet;
            String id = result.optString("rest_id", "");
            if (!id.isEmpty()) return id;
            JSONObject legacy = result.optJSONObject("legacy");
            if (legacy != null) {
                id = legacy.optString("id_str", "");
                if (!id.isEmpty()) return id;
            }
        }
        String entryId = node.optString("entryId", "");
        int pos = entryId.lastIndexOf("tweet-");
        if (pos >= 0) {
            String digits = entryId.substring(pos + 6);
            int end = 0;
            while (end < digits.length() && Character.isDigit(digits.charAt(end))) end++;
            if (end > 0) return digits.substring(0, end);
        }
        return "";
    }

    private static JSONObject first(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            JSONObject child = obj.optJSONObject(key);
            if (child != null) return child;
        }
        return null;
    }
}
