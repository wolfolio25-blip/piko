/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Local bookmark collections. All dialogs are custom X-style surfaces
 * using BookmarkChrome. X's server bookmarks are not altered or removed.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.widget.EditText;
import android.widget.Toast;
import android.view.View;
import android.widget.LinearLayout;

import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.entity.Media;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public final class BookmarkFolders {
    private static final String STORE = "piko_local_bookmark_folders";
    private static final String KEY = "folders";
    private static final Object LOCK = new Object();

    private BookmarkFolders() {}

    static JSONArray read(Context context) {
        String source = context.getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(KEY, "[]");
        try {
            return new JSONArray(source);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray folders) {
        context.getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit().putString(KEY, folders.toString()).apply();
    }

    private static void notifyUser(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void addSettingsEntry(Context context, PreferenceScreen screen) {
        Preference preference = new Preference(context);
        preference.setTitle("Bookmark folders");
        preference.setSummary("Local folders · organize saved posts");
        preference.setOnPreferenceClickListener(p -> {
            showFolders(context);
            return true;
        });
        screen.addPreference(preference);
    }

    /** Best-effort hook for X's share menu bookmark action. */
    public static void onBookmarkAction(Context context, Object action, Object tweetObject) {
        if (context == null || tweetObject == null || action == null) return;
        try {
            Activity activity = getActivity(context);
            if (activity == null) return;
            String name = String.valueOf(action);
            if ("Bookmark".equalsIgnoreCase(name)
                    || "AddBookmark".equalsIgnoreCase(name)
                    || "AddToBookmarks".equalsIgnoreCase(name)) {
                saveTweet(activity, new Tweet(tweetObject));
            }
        } catch (Exception exception) {
            android.util.Log.e("PikoBookmarkFolders", "Bookmark action failed", exception);
        }
    }

    private static Activity getActivity(Context context) {
        for (int i = 0; context != null && i < 10; i++) {
            if (context instanceof Activity) return (Activity) context;
            context = context instanceof android.content.ContextWrapper
                    ? ((android.content.ContextWrapper) context).getBaseContext() : null;
        }
        return null;
    }

    public static void saveTweet(Context context, Tweet tweet) {
        JSONObject post = makeSnapshot(tweet);
        if (post == null) {
            notifyUser(context, "Could not read this post");
            return;
        }
        chooseFolder(context, post);
    }

    private static JSONObject makeSnapshot(Tweet tweet) {
        try {
            JSONObject post = new JSONObject();
            String id = String.valueOf(tweet.getTweetId());
            if (id.isEmpty() || "null".equals(id)) return null;
            String username = tweet.getTweetUsername();
            String displayName = tweet.getTweetProfileName();
            String text = tweet.getText();

            if (username == null) username = "";
            if (displayName == null || displayName.isEmpty()) displayName = username;
            if (text == null) text = "";
            post.put("id", id);
            post.put("url", "https://x.com/" + username + "/status/" + id);
            post.put("username", username);
            post.put("displayName", displayName);
            post.put("text", text);
            post.put("label", "@" + username + " — " +
                    (text.trim().isEmpty() ? "Post " + id : text.trim()));
            post.put("savedAt", System.currentTimeMillis());
            try {
                ArrayList<ArrayList<Media>> media = tweet.getMediaList();
                if (media != null) {
                    for (ArrayList<Media> variants : media) {
                        if (variants == null) continue;
                        for (Media asset : variants) {
                            if (asset != null && asset.url != null && asset.type == 0) {
                                post.put("mediaUrl", asset.url);
                                post.put("mediaType", "image");
                                return post;
                            }
                        }
                        if (!variants.isEmpty()) post.put("mediaType", "video");
                    }
                }
            } catch (Exception ignored) {}
            return post;
        } catch (Exception exception) {
            android.util.Log.w("PikoBookmarkFolders", "Could not snapshot post", exception);
            return null;
        }
    }

    private static void chooseFolder(Context context, JSONObject post) {
        JSONArray folders = read(context);
        BookmarkChrome ui = new BookmarkChrome(context);
        BookmarkChrome.Sheet sheet = ui.sheet("Add to folder");
        sheet.row(BookmarkChrome.PLUS, "Create new folder", null, false,
                () -> promptNewFolder(context, () -> saveInNewestFolder(context, post)));
        if (folders.length() > 0) sheet.separator();

        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;
            final String folderName = folder.optString("name", "");
            if (folderName.isEmpty()) continue;
            JSONArray posts = folder.optJSONArray("posts");
            String subtitle = (posts == null ? 0 : posts.length()) + " posts";
            sheet.row(BookmarkChrome.FOLDER, folderName, subtitle, false,
                    () -> savePostTo(context, folderName, post));
        }
        sheet.note("Saved on this device. Your regular X bookmark remains unchanged.");
        sheet.show();
    }

    private static void saveInNewestFolder(Context context, JSONObject post) {
        synchronized (LOCK) {
            JSONArray all = read(context);
            JSONObject latest = all.optJSONObject(all.length() - 1);
            if (latest == null) return;
            savePostTo(context, latest.optString("name"), post);
        }
    }

    static boolean savePostTo(Context context, String folderName, JSONObject post) {
        synchronized (LOCK) {
            JSONArray folders = read(context);
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folder = folders.optJSONObject(i);
                if (folder == null || !folderName.equals(folder.optString("name"))) continue;
                JSONArray posts = folder.optJSONArray("posts");
                if (posts == null) posts = new JSONArray();
                String id = post.optString("id");
                for (int p = 0; p < posts.length(); p++) {
                    JSONObject existing = posts.optJSONObject(p);
                    if (existing != null && id.equals(existing.optString("id"))) {
                        notifyUser(context, "Already saved in " + folderName);
                        return false;
                    }
                }
                try {
                    posts.put(new JSONObject(post.toString()));
                    folder.put("posts", posts);
                    save(context, folders);
                    notifyUser(context, "Saved in " + folderName);
                    return true;
                } catch (Exception exception) {
                    notifyUser(context, "Could not save post");
                    return false;
                }
            }
        }
        notifyUser(context, "Folder no longer exists");
        return false;
    }

    static void promptNewFolder(Context context, Runnable onCreated) {
        BookmarkChrome ui = new BookmarkChrome(context);
        BookmarkChrome.Sheet sheet = ui.sheet("Create a folder");
        EditText name = ui.input("Folder name", "");
        name.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(60)
        });
        name.setMinimumHeight(ui.dp(52));
        sheet.custom(name, 20, 12);
        sheet.button("Create", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                notifyUser(context, "Enter a folder name");
                return;
            }
            if (createFolder(context, value) && onCreated != null) onCreated.run();
        }, false);
        sheet.show();
    }

    private static boolean createFolder(Context context, String value) {
        synchronized (LOCK) {
            JSONArray folders = read(context);
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folder = folders.optJSONObject(i);
                if (folder != null && value.equalsIgnoreCase(folder.optString("name"))) {
                    notifyUser(context, "Folder already exists");
                    return false;
                }
            }
            JSONObject folder = new JSONObject();
            try {
                folder.put("name", value);
                folder.put("posts", new JSONArray());
                folders.put(folder);
                save(context, folders);
                return true;
            } catch (Exception exception) {
                notifyUser(context, "Couldn't create folder");
                return false;
            }
        }
    }

    public static void showFolders(Context context) {
        JSONArray folders = read(context);
        BookmarkChrome ui = new BookmarkChrome(context);
        BookmarkChrome.Sheet sheet = ui.sheet("Bookmark folders");
        sheet.row(BookmarkChrome.PLUS, "Create new folder", null, false,
                () -> promptNewFolder(context, () -> showFolders(context)));
        if (folders.length() > 0) sheet.separator();

        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;
            final int index = i;
            JSONArray posts = folder.optJSONArray("posts");
            sheet.row(BookmarkChrome.FOLDER, folder.optString("name"),
                    (posts == null ? 0 : posts.length()) + " posts", false,
                    () -> showFolder(context, index));
        }
        sheet.note("Folders are private to this device and do not sync with X.");
        sheet.show();
    }

    private static void showFolder(Context context, int index) {
        JSONArray folders = read(context);
        JSONObject folder = folders.optJSONObject(index);
        if (folder == null) { showFolders(context); return; }
        JSONArray posts = folder.optJSONArray("posts");
        BookmarkChrome ui = new BookmarkChrome(context);
        BookmarkChrome.Sheet sheet = ui.sheet(folder.optString("name"));
        sheet.row(BookmarkChrome.MORE, "Manage folder", null, false,
                () -> manageFolder(context, index, () -> showFolders(context)));
        if (posts == null || posts.length() == 0) {
            sheet.note("There are no posts in this folder yet.");
        } else {
            sheet.separator();
            for (int i = posts.length() - 1; i >= 0; i--) {
                JSONObject post = posts.optJSONObject(i);
                if (post == null) continue;
                final int postIndex = i;
                sheet.row(BookmarkChrome.BOOKMARK,
                        post.optString("label", "Saved post"), null, false,
                        () -> showPost(context, index, postIndex, () -> showFolder(context, index)));
            }
        }
        sheet.show();
    }

    static void showPost(Context context, int folderIndex, int postIndex, Runnable onChanged) {
        JSONArray folders = read(context);
        JSONObject folder = folders.optJSONObject(folderIndex);
        JSONArray posts = folder == null ? null : folder.optJSONArray("posts");
        JSONObject post = posts == null ? null : posts.optJSONObject(postIndex);
        if (post == null) return;

        BookmarkChrome.Sheet sheet = new BookmarkChrome(context).sheet("Saved post");
        sheet.row(BookmarkChrome.BOOKMARK, "Open post", null, false,
                () -> openPost(context, post));
        sheet.row(BookmarkChrome.FOLDER, "Move to folder", null, false,
                () -> chooseMoveFolder(context, folderIndex, postIndex, onChanged));
        sheet.row(BookmarkChrome.TRASH, "Remove from folder", null, true,
                () -> {
                    if (removeFromFolder(context, folderIndex, postIndex) && onChanged != null)
                        onChanged.run();
                });
        sheet.show();
    }

    static void openPost(Context context, JSONObject post) {
        String url = post.optString("url", "");
        if (url.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(context.getPackageName());
            context.startActivity(intent);
        } catch (Exception first) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception second) {
                notifyUser(context, "Could not open post");
            }
        }
    }

    private static boolean removeFromFolder(Context context, int folderIndex, int postIndex) {
        synchronized (LOCK) {
            JSONArray folders = read(context);
            JSONObject folder = folders.optJSONObject(folderIndex);
            JSONArray posts = folder == null ? null : folder.optJSONArray("posts");
            if (posts == null || postIndex < 0 || postIndex >= posts.length()) return false;
            posts.remove(postIndex);
            save(context, folders);
            notifyUser(context, "Removed from folder");
            return true;
        }
    }

    private static void chooseMoveFolder(Context context, int fromIndex, int postIndex, Runnable onChanged) {
        JSONArray folders = read(context);
        BookmarkChrome.Sheet sheet = new BookmarkChrome(context).sheet("Move to folder");
        sheet.row(BookmarkChrome.PLUS, "Create new folder", null, false,
                () -> promptNewFolder(context, () -> {
                    JSONArray now = read(context);
                    if (now.length() > 0) movePost(context, fromIndex, postIndex,
                            now.length() - 1, onChanged);
                }));
        for (int i = 0; i < folders.length(); i++) {
            if (i == fromIndex) continue;
            JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;
            final int toIndex = i;
            sheet.row(BookmarkChrome.FOLDER, folder.optString("name"),
                    null, false,
                    () -> movePost(context, fromIndex, postIndex, toIndex, onChanged));
        }
        sheet.show();
    }

    private static void movePost(Context context, int fromIndex, int postIndex,
                                 int toIndex, Runnable onChanged) {
        synchronized (LOCK) {
            JSONArray folders = read(context);
            JSONObject source = folders.optJSONObject(fromIndex);
            JSONObject target = folders.optJSONObject(toIndex);
            if (source == null || target == null || source == target) return;
            JSONArray from = source.optJSONArray("posts");
            JSONArray to = target.optJSONArray("posts");
            if (from == null || postIndex >= from.length()) return;
            JSONObject post = from.optJSONObject(postIndex);
            if (post == null) return;
            if (to == null) to = new JSONArray();
            boolean alreadyExists = false;
            for (int i = 0; i < to.length(); i++) {
                JSONObject p = to.optJSONObject(i);
                if (p != null && post.optString("id").equals(p.optString("id"))) {
                    alreadyExists = true;
                    break;
                }
            }
            if (!alreadyExists) to.put(post);
            from.remove(postIndex);
            try { target.put("posts", to); }
            catch (Exception ignored) { return; }
            save(context, folders);
            notifyUser(context, "Moved to " + target.optString("name"));
        }
        if (onChanged != null) onChanged.run();
    }

    static void manageFolder(Context context, int index, Runnable onChanged) {
        JSONArray folders = read(context);
        JSONObject folder = folders.optJSONObject(index);
        if (folder == null) return;
        BookmarkChrome.Sheet sheet = new BookmarkChrome(context)
                .sheet(folder.optString("name"));
        sheet.row(BookmarkChrome.MORE, "Rename folder", null, false, () -> {
            renameFolder(context, index, onChanged);
        });
        sheet.row(BookmarkChrome.TRASH, "Delete folder", null, true, () -> {
            confirmDelete(context, index, onChanged);
        });
        sheet.show();
    }

    private static void renameFolder(Context context, int index, Runnable onChanged) {
        JSONObject folder = read(context).optJSONObject(index);
        if (folder == null) return;
        BookmarkChrome ui = new BookmarkChrome(context);
        BookmarkChrome.Sheet sheet = ui.sheet("Rename folder");
        EditText input = ui.input("Folder name", folder.optString("name"));
        input.setMinimumHeight(ui.dp(52));
        sheet.custom(input, 20, 12);
        sheet.button("Save", () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { notifyUser(context, "Enter a name"); return; }
            synchronized (LOCK) {
                JSONArray all = read(context);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject other = all.optJSONObject(i);
                    if (i != index && other != null &&
                            value.equalsIgnoreCase(other.optString("name"))) {
                        notifyUser(context, "Folder already exists");
                        return;
                    }
                }
                JSONObject target = all.optJSONObject(index);
                if (target == null) return;
                try { target.put("name", value); save(context, all); }
                catch (Exception exception) { notifyUser(context, "Rename failed"); return; }
            }
            if (onChanged != null) onChanged.run();
        }, false);
        sheet.show();
    }

    private static void confirmDelete(Context context, int index, Runnable onChanged) {
        BookmarkChrome.Sheet sheet = new BookmarkChrome(context)
                .sheet("Delete folder?");
        sheet.note("The posts in this local folder will be removed from the folder. Your X bookmarks won't be deleted.");
        sheet.button("Delete folder", () -> {
            synchronized (LOCK) {
                JSONArray folders = read(context);
                if (index >= 0 && index < folders.length()) {
                    folders.remove(index);
                    save(context, folders);
                }
            }
            if (onChanged != null) onChanged.run();
        }, true);
        sheet.show();
    }

    // Backward-compatible entrypoints for Piko's existing settings and folder UI.
    static void showPost(Context context, int folderIndex, int postIndex) {
        showPost(context, folderIndex, postIndex, null);
    }

    static void manageFolder(Context context, int index) {
        manageFolder(context, index, () -> showFolders(context));
    }
}
