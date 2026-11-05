/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Local bookmark folders for Piko. Not a server-side X Premium feature.
 * Folders and saved post links are stored on this device only.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import app.morphe.extension.twitter.entity.Tweet;
import org.json.JSONArray;
import org.json.JSONObject;

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

    private static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
    }

    /** Installed as a separate entry in Piko settings. */
    public static void addSettingsEntry(Context context, PreferenceScreen screen) {
        Preference preference = new Preference(context);
        preference.setTitle("Bookmark folders");
        preference.setSummary("Organize post links in local folders (not synced with X)");
        preference.setOnPreferenceClickListener(p -> {
            showFolders(context);
            return true;
        });
        screen.addPreference(preference);
    }

    /**
     * After X's native add-bookmark action, show the same local folder picker.
     * This leaves server bookmarking untouched and avoids Premium-only APIs.
     */
    public static void onBookmarkAction(Context context, Object action, Object tweetObject) {
        if (!(context instanceof android.app.Activity) || tweetObject == null || action == null) return;
        String name = String.valueOf(action);
        if ("Bookmark".equalsIgnoreCase(name)
                || "AddBookmark".equalsIgnoreCase(name)
                || "AddToBookmarks".equalsIgnoreCase(name)) {
            saveTweet(context, new Tweet(tweetObject));
        }
    }

    /** Called from the Piko tweet share menu; no additional X API permissions. */
    public static void saveTweet(Context context, Tweet tweet) {
        final String id;
        final String link;
        final String label;
        try {
            id = Long.toString(tweet.getTweetId());
            link = tweet.getTweetLink();
            String text = tweet.getText();
            label = "@" + tweet.getTweetUsername() + " — " +
                    (text == null || text.trim().isEmpty() ? "Post " + id : text.trim());
        } catch (Exception exception) {
            toast(context, "Could not identify the post");
            return;
        }
        chooseFolder(context, id, link, label);
    }

    private static String[] names(JSONArray folders, boolean withNew) {
        int length = folders.length();
        String[] choices = new String[length + (withNew ? 1 : 0)];
        for (int i = 0; i < length; i++) {
            JSONObject folder = folders.optJSONObject(i);
            choices[i] = folder == null ? "Folder" : folder.optString("name", "Folder");
        }
        if (withNew) choices[length] = "+ New folder";
        return choices;
    }

    private static void chooseFolder(Context context, String id, String url, String label) {
        JSONArray folders;
        synchronized (LOCK) { folders = read(context); }
        int count = folders.length();
        new AlertDialog.Builder(context)
                .setTitle("Save post to folder")
                .setItems(names(folders, true), (dialog, which) -> {
                    if (which == count) {
                        promptNewFolder(context, () -> chooseFolder(context, id, url, label));
                    } else {
                        synchronized (LOCK) {
                            JSONArray latest = read(context);
                            JSONObject folder = latest.optJSONObject(which);
                            if (folder == null) return;
                            JSONArray posts = folder.optJSONArray("posts");
                            if (posts == null) posts = new JSONArray();
                            boolean exists = false;
                            for (int i = 0; i < posts.length(); i++) {
                                JSONObject post = posts.optJSONObject(i);
                                if (post != null && id.equals(post.optString("id"))) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                JSONObject post = new JSONObject();
                                try {
                                    post.put("id", id);
                                    post.put("url", url);
                                    post.put("label", label);
                                    posts.put(post);
                                    folder.put("posts", posts);
                                    save(context, latest);
                                } catch (Exception ignored) {
                                    toast(context, "Could not save post");
                                    return;
                                }
                            }
                            toast(context, exists ? "Already in this folder" : "Saved to " + folder.optString("name"));
                        }
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    static void promptNewFolder(Context context, Runnable onCreated) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("Folder name");
        input.setPadding(28, 20, 28, 20);
        new AlertDialog.Builder(context)
                .setTitle("New bookmark folder")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, button) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(context, "Folder name cannot be empty");
                        return;
                    }
                    synchronized (LOCK) {
                        JSONArray folders = read(context);
                        for (int i = 0; i < folders.length(); i++) {
                            JSONObject existing = folders.optJSONObject(i);
                            if (existing != null && name.equalsIgnoreCase(existing.optString("name"))) {
                                toast(context, "Folder already exists");
                                return;
                            }
                        }
                        JSONObject folder = new JSONObject();
                        try {
                            folder.put("name", name);
                            folder.put("posts", new JSONArray());
                            folders.put(folder);
                            save(context, folders);
                        } catch (Exception exception) {
                            toast(context, "Could not create folder");
                            return;
                        }
                    }
                    if (onCreated != null) onCreated.run();
                }).show();
    }

    public static void showFolders(Context context) {
        JSONArray folders;
        synchronized (LOCK) { folders = read(context); }
        int size = folders.length();
        String[] choices = new String[size + 1];
        for (int i = 0; i < size; i++) {
            JSONObject folder = folders.optJSONObject(i);
            JSONArray posts = folder == null ? null : folder.optJSONArray("posts");
            choices[i] = (folder == null ? "Folder" : folder.optString("name")) +
                    "  (" + (posts == null ? 0 : posts.length()) + ")";
        }
        choices[size] = "+ New folder";
        new AlertDialog.Builder(context)
                .setTitle("Bookmark folders · on this device")
                .setItems(choices, (dialog, which) -> {
                    if (which == size) promptNewFolder(context, () -> showFolders(context));
                    else showFolder(context, which);
                })
                .setNegativeButton("Close", null).show();
    }

    private static void showFolder(Context context, int index) {
        JSONArray folders;
        synchronized (LOCK) { folders = read(context); }
        JSONObject folder = folders.optJSONObject(index);
        if (folder == null) { showFolders(context); return; }
        JSONArray posts = folder.optJSONArray("posts");
        if (posts == null) posts = new JSONArray();
        final String[] items = new String[posts.length()];
        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.optJSONObject(i);
            String label = post == null ? "Post" : post.optString("label", "Post");
            items[i] = label.length() > 100 ? label.substring(0, 100) + "…" : label;
        }
        new AlertDialog.Builder(context)
                .setTitle(folder.optString("name"))
                .setItems(items, (dialog, which) -> showPost(context, index, which))
                .setNeutralButton("Manage", (dialog, which) -> manageFolder(context, index))
                .setNegativeButton("Folders", (dialog, which) -> showFolders(context))
                .show();
    }

    private static void showPost(Context context, int folderIndex, int postIndex) {
        showPost(context, folderIndex, postIndex, null);
    }

    static void showPost(Context context, int folderIndex, int postIndex, Runnable onChanged) {
        JSONArray folders = read(context);
        JSONObject folder = folders.optJSONObject(folderIndex);
        JSONArray posts = folder == null ? null : folder.optJSONArray("posts");
        JSONObject post = posts == null ? null : posts.optJSONObject(postIndex);
        if (post == null) return;
        new AlertDialog.Builder(context)
                .setTitle(post.optString("label", "Saved post"))
                .setItems(new String[]{"Open post", "Remove from folder"}, (dialog, which) -> {
                    if (which == 0) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(post.optString("url")));
                            context.startActivity(intent);
                        } catch (Exception exception) {
                            toast(context, "Could not open post");
                        }
                    } else {
                        synchronized (LOCK) {
                            JSONArray current = read(context);
                            JSONObject currentFolder = current.optJSONObject(folderIndex);
                            JSONArray oldPosts = currentFolder == null ? null : currentFolder.optJSONArray("posts");
                            if (oldPosts != null && postIndex < oldPosts.length()) {
                                oldPosts.remove(postIndex);
                                save(context, current);
                            }
                        }
                        if (onChanged != null) onChanged.run();
                        else showFolder(context, folderIndex);
                    }
                }).setNegativeButton("Cancel", (dialog, which) -> {
                    if (onChanged != null) onChanged.run();
                    else showFolder(context, folderIndex);
                })
                .show();
    }

    private static void manageFolder(Context context, int folderIndex) {
        manageFolder(context, folderIndex, () -> showFolders(context));
    }

    static void manageFolder(Context context, int folderIndex, Runnable onChanged) {
        JSONArray folders = read(context);
        JSONObject folder = folders.optJSONObject(folderIndex);
        if (folder == null) return;
        new AlertDialog.Builder(context).setTitle(folder.optString("name"))
                .setItems(new String[]{"Rename folder", "Delete folder"}, (dialog, which) -> {
                    if (which == 0) {
                        EditText input = new EditText(context);
                        input.setSingleLine(true);
                        input.setText(folder.optString("name"));
                        new AlertDialog.Builder(context).setTitle("Rename folder").setView(input)
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Save", (d, b) -> {
                                    String newName = input.getText().toString().trim();
                                    if (newName.isEmpty()) return;
                                    synchronized (LOCK) {
                                        JSONArray current = read(context);
                                        JSONObject target = current.optJSONObject(folderIndex);
                                        if (target == null) return;
                                        try { target.put("name", newName); save(context, current); }
                                        catch (Exception ignored) { toast(context, "Rename failed"); }
                                    }
                                    if (onChanged != null) onChanged.run();
                                }).show();
                    } else {
                        new AlertDialog.Builder(context)
                                .setTitle("Delete folder?")
                                .setMessage("This removes its locally saved links, not your X bookmarks.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete", (d, b) -> {
                                    synchronized (LOCK) {
                                        JSONArray current = read(context);
                                        if (folderIndex < current.length()) {
                                            current.remove(folderIndex);
                                            save(context, current);
                                        }
                                    }
                                    if (onChanged != null) onChanged.run();
                                }).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
