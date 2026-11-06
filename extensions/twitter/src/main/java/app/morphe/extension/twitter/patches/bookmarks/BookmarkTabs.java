/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Two tabs inside X's existing legacy BookmarkActivity. The "All Tweets" tab
 * retains the original X view and its server-backed bookmarks. "Folders"
 * displays locally saved tweet links with no Premium subscription.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.twitter.Utils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public final class BookmarkTabs {
    private static final String BOOKMARK_ACTIVITY =
            "com.twitter.app.bookmarks.legacy.BookmarkActivity";
    private static final int BLUE = 0xFF1D9BF0;
    private static final int DARK_BG = 0xFF000000;
    private static final int LIGHT_BG = 0xFFFFFFFF;
    private static final int DARK_TEXT = 0xFFE7E9EA;
    private static final int LIGHT_TEXT = 0xFF0F1419;
    private static boolean registered;
    private static final java.util.WeakHashMap<Activity, BookmarkPage> INSTALLED =
            new java.util.WeakHashMap<>();

    private BookmarkTabs() {}

    public static synchronized void install(Application application) {
        if (registered || application == null) return;
        registered = true;
        BookmarkButtonObserver.install(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle s) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {
                if (BOOKMARK_ACTIVITY.equals(a.getClass().getName())) {
                    a.getWindow().getDecorView().post(() -> attach(a));
                }
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle s) {}
            @Override public void onActivityDestroyed(Activity a) { INSTALLED.remove(a); }
        });
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private static boolean dark(Activity a) {
        try {
            int color = Utils.resolveColor(a, "coreColorAppBackground");
            return Color.red(color) + Color.green(color) + Color.blue(color) < 384;
        } catch (Exception ignored) {
            return (a.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
    }

    private static TextView label(Context c, String value, int size, int color) {
        TextView text = new TextView(c);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private static FrameLayout.LayoutParams fullFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void attach(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout)) return;
        FrameLayout content = (FrameLayout) root;
        // Only wrap the native Bookmarks content once per Activity instance.
        if (INSTALLED.containsKey(activity)) return;
        if (content.getChildCount() == 0) return;
        ArrayList<View> nativeChildren = new ArrayList<>();
        ArrayList<ViewGroup.LayoutParams> params = new ArrayList<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            nativeChildren.add(child);
            params.add(child.getLayoutParams());
        }
        try {
            // Prepare UI first; if this fails, leave X's original screen untouched.
            BookmarkPage page = new BookmarkPage(activity);
            content.removeAllViews();
            content.addView(page.shell, fullFrame());
            for (int i = 0; i < nativeChildren.size(); i++) {
                View child = nativeChildren.get(i);
                ViewGroup.LayoutParams original = params.get(i);
                FrameLayout.LayoutParams frameParams;
                if (original instanceof FrameLayout.LayoutParams) {
                    frameParams = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) original);
                } else {
                    frameParams = fullFrame();
                }
                page.originalView.addView(child, frameParams);
            }
            INSTALLED.put(activity, page);
            page.switchTo(false);
            page.shell.post(page::positionControls);
        } catch (Exception exception) {
            // Restore the original children if a vendor-specific layout rejects wrapping.
            content.removeAllViews();
            for (int i = 0; i < nativeChildren.size(); i++) {
                View child = nativeChildren.get(i);
                if (child.getParent() instanceof ViewGroup) {
                    ((ViewGroup) child.getParent()).removeView(child);
                }
                ViewGroup.LayoutParams original = params.get(i);
                content.addView(child, original instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) original : fullFrame());
            }
            INSTALLED.remove(activity);
            android.util.Log.e("PikoBookmarkTabs", "Could not attach tabs", exception);
        }
    }

    private static final class BookmarkPage {
        final Activity activity;
        final boolean isDark;
        final int background;
        final int primary;
        final int secondary;
        final FrameLayout shell;
        final FrameLayout originalView;
        final LinearLayout tabBar;
        final View tabDivider;
        private View adjustedList;
        private int listPaddingTop;
        final ScrollView foldersView;
        final TextView allTab;
        final TextView foldersTab;
        final View allIndicator;
        final View foldersIndicator;

        BookmarkPage(Activity activity) {
            this.activity = activity;
            this.isDark = dark(activity);
            this.background = isDark ? DARK_BG : LIGHT_BG;
            this.primary = isDark ? DARK_TEXT : LIGHT_TEXT;
            this.secondary = isDark ? 0xFF71767B : 0xFF536471;

            shell = new FrameLayout(activity);
            shell.setBackgroundColor(background);

            // Preserve X's entire Bookmarks screen, including its native top toolbar.
            originalView = new FrameLayout(activity);
            shell.addView(originalView, fullFrame());

            // Add the two tabs as an overlay below X's native Bookmarks heading,
            // never above the system status bar or above its back button.
            tabBar = new LinearLayout(activity);
            tabBar.setOrientation(LinearLayout.HORIZONTAL);
            tabBar.setBackgroundColor(background);
            tabBar.setElevation(dp(activity, 3));
            shell.addView(tabBar, new FrameLayout.LayoutParams(-1, dp(activity, 52)));

            LinearLayout bar = tabBar;
            LinearLayout left = tabContainer();
            LinearLayout right = tabContainer();
            bar.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            bar.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
            allTab = tabText("All Tweets");
            foldersTab = tabText("Folders");
            allIndicator = indicator();
            foldersIndicator = indicator();
            left.addView(allTab, new LinearLayout.LayoutParams(-1, 0, 1));
            right.addView(foldersTab, new LinearLayout.LayoutParams(-1, 0, 1));
            left.addView(allIndicator, new LinearLayout.LayoutParams(-1, dp(activity, 3)));
            right.addView(foldersIndicator, new LinearLayout.LayoutParams(-1, dp(activity, 3)));
            left.setOnClickListener(v -> switchTo(false));
            right.setOnClickListener(v -> switchTo(true));

            foldersView = new ScrollView(activity);
            foldersView.setFillViewport(true);
            foldersView.setBackgroundColor(background);
            foldersView.setClickable(true);
            shell.addView(foldersView, new FrameLayout.LayoutParams(-1, -1));
            foldersView.setElevation(dp(activity, 1));

            tabDivider = new View(activity);
            tabDivider.setBackgroundColor(isDark ? 0xFF2F3336 : 0xFFEFF3F4);
            shell.addView(tabDivider, new FrameLayout.LayoutParams(-1, dp(activity, 1)));
            tabDivider.setElevation(dp(activity, 4));
            tabBar.bringToFront();
        }

        /** Place tabs after the existing X toolbar, measured from its actual title. */
        void positionControls() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            final int[] rootPos = new int[2];
            shell.getLocationOnScreen(rootPos);
            TextView heading = findBookmarksTitle(originalView, 0);
            int top;
            if (heading != null && heading.getHeight() > 0) {
                int[] pos = new int[2];
                heading.getLocationOnScreen(pos);
                top = pos[1] - rootPos[1] + heading.getHeight() + dp(activity, 14);
            } else {
                // Edge-to-edge fallback; status bar height must be accounted for.
                int status = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                int statusHeight = status > 0
                        ? activity.getResources().getDimensionPixelSize(status) : dp(activity, 24);
                top = statusHeight + dp(activity, 58);
            }
            top = Math.max(dp(activity, 64), Math.min(top, shell.getHeight() / 3));
            FrameLayout.LayoutParams barParams =
                    new FrameLayout.LayoutParams(-1, dp(activity, 52));
            barParams.topMargin = top;
            tabBar.setLayoutParams(barParams);
            FrameLayout.LayoutParams lineParams =
                    new FrameLayout.LayoutParams(-1, dp(activity, 1));
            lineParams.topMargin = top + dp(activity, 52);
            tabDivider.setLayoutParams(lineParams);
            FrameLayout.LayoutParams foldersParams = new FrameLayout.LayoutParams(-1, -1);
            foldersParams.topMargin = top + dp(activity, 53);
            foldersView.setLayoutParams(foldersParams);

            // RecyclerView padding makes the first native post start BELOW the tabs
            // instead of being obscured by them. Never resize the native toolbar.
            if (adjustedList == null) {
                View list = findTweetList(originalView, 0, top + rootPos[1]);
                if (list != null) {
                    adjustedList = list;
                    listPaddingTop = list.getPaddingTop();
                    list.setPadding(list.getPaddingLeft(), listPaddingTop + dp(activity, 53),
                            list.getPaddingRight(), list.getPaddingBottom());
                    if (list instanceof ViewGroup) ((ViewGroup) list).setClipToPadding(false);
                }
            }
        }

        private TextView findBookmarksTitle(View view, int depth) {
            if (view == null || depth > 24) return null;
            if (view instanceof TextView) {
                String value = String.valueOf(((TextView) view).getText()).trim();
                if ("Bookmarks".equalsIgnoreCase(value)) return (TextView) view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    TextView match = findBookmarksTitle(group.getChildAt(i), depth + 1);
                    if (match != null) return match;
                }
            }
            return null;
        }

        private View findTweetList(View view, int depth, int headingBottom) {
            if (view == null || depth > 24) return null;
            Class<?> type = view.getClass();
            boolean recycler = false;
            for (Class<?> k = type; k != null; k = k.getSuperclass()) {
                String name = k.getName();
                if (name.equals("androidx.recyclerview.widget.RecyclerView")
                        || name.equals("android.widget.ListView")
                        || name.equals("com.twitter.android.widget.RefreshableListView")) {
                    recycler = true;
                    break;
                }
            }
            if (recycler && view.getWidth() >= dp(activity, 200)
                    && view.getHeight() >= dp(activity, 80)) {
                int[] pos = new int[2];
                view.getLocationOnScreen(pos);
                if (pos[1] >= headingBottom - dp(activity, 85)) return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View match = findTweetList(group.getChildAt(i), depth + 1, headingBottom);
                    if (match != null) return match;
                }
            }
            return null;
        }

        private LinearLayout tabContainer() {
            LinearLayout column = new LinearLayout(activity);
            column.setGravity(Gravity.CENTER);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClickable(true);
            return column;
        }

        private TextView tabText(String name) {
            TextView view = label(activity, name, 15, primary);
            view.setGravity(Gravity.CENTER);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }

        private View indicator() {
            View indicator = new View(activity);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(BLUE);
            shape.setCornerRadius(dp(activity, 3));
            indicator.setBackground(shape);
            return indicator;
        }

        void switchTo(boolean folders) {
            // Keep the native header visible on both tabs; cover only the post list.
            originalView.setVisibility(View.VISIBLE);
            foldersView.setVisibility(folders ? View.VISIBLE : View.GONE);
            allTab.setTextColor(folders ? secondary : primary);
            foldersTab.setTextColor(folders ? primary : secondary);
            allIndicator.setVisibility(folders ? View.INVISIBLE : View.VISIBLE);
            foldersIndicator.setVisibility(folders ? View.VISIBLE : View.INVISIBLE);
            if (folders) populateFolders();
        }

        /** Flat folder rows, no Android cards, icons, emoji or stock controls. */
        private void populateFolders() {
            BookmarkChrome ui = new BookmarkChrome(activity);
            LinearLayout list = ui.column();
            list.setBackgroundColor(background);
            list.setPadding(0, ui.dp(8), 0, ui.dp(26));
            foldersView.removeAllViews();
            foldersView.addView(list, new ScrollView.LayoutParams(-1, -2));

            LinearLayout create = ui.row();
            create.setPadding(ui.dp(20), 0, ui.dp(20), 0);
            create.setMinimumHeight(ui.dp(54));
            create.setBackground(ui.ripple());
            LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(ui.dp(23), ui.dp(23));
            iconP.rightMargin = ui.dp(18);
            create.addView(ui.icon(BookmarkChrome.PLUS, BookmarkChrome.BLUE, 23), iconP);
            TextView createLabel = ui.text("Create a folder", 16, true, BookmarkChrome.BLUE);
            create.addView(createLabel, new LinearLayout.LayoutParams(0, -2, 1f));
            create.setOnClickListener(v ->
                    BookmarkFolders.promptNewFolder(activity, this::populateFolders));
            list.addView(create, new LinearLayout.LayoutParams(-1, ui.dp(54)));
            list.addView(ui.divider());

            android.widget.EditText search = ui.input("Search folders", "");
            search.setSingleLine(true);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, ui.dp(44));
            searchParams.setMargins(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(12));
            list.addView(search, searchParams);

            LinearLayout rows = ui.column();
            list.addView(rows, new LinearLayout.LayoutParams(-1, -2));
            Runnable render = () -> drawFolderRows(ui, rows,
                    search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT));
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    render.run();
                }
                @Override public void afterTextChanged(android.text.Editable text) {}
            });
            render.run();
        }

        private void drawFolderRows(BookmarkChrome ui, LinearLayout rows, String query) {
            rows.removeAllViews();
            JSONArray folders = BookmarkFolders.read(activity);
            int matched = 0;
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folder = folders.optJSONObject(i);
                if (folder == null) continue;
                String name = folder.optString("name", "Folder");
                if (!query.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                matched++;
                int index = i;
                JSONArray posts = folder.optJSONArray("posts");
                int count = posts == null ? 0 : posts.length();

                LinearLayout item = ui.row();
                item.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
                item.setMinimumHeight(ui.dp(76));
                item.setBackground(ui.ripple());

                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26));
                iconParams.rightMargin = ui.dp(18);
                item.addView(ui.icon(BookmarkChrome.FOLDER, primary, 26), iconParams);

                LinearLayout labels = ui.column();
                labels.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                labels.addView(ui.text(name, 16, true, primary),
                        new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
                subtitleParams.topMargin = ui.dp(4);
                labels.addView(ui.text(count + (count == 1 ? " post" : " posts"),
                        14, false, secondary), subtitleParams);
                item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

                android.widget.ImageView menu =
                        ui.icon(BookmarkChrome.MORE, secondary, 22);
                menu.setContentDescription("Folder options");
                menu.setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
                menu.setBackground(ui.ripple());
                item.addView(menu, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)));
                menu.setOnClickListener(v ->
                        BookmarkFolders.manageFolder(activity, index, this::populateFolders));
                item.setOnClickListener(v -> showPosts(index));
                rows.addView(item, new LinearLayout.LayoutParams(-1, -2));
                rows.addView(ui.divider());
            }
            if (matched == 0) {
                String message = folders.length() == 0
                        ? "No folders yet. Create one to organize saved posts."
                        : "No matching folders";
                TextView empty = ui.text(message, 15, false, secondary);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(ui.dp(32), ui.dp(50), ui.dp(32), ui.dp(30));
                rows.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        private void showPosts(int folderIndex) {
            JSONArray folders = BookmarkFolders.read(activity);
            JSONObject folder = folders.optJSONObject(folderIndex);
            if (folder == null) { populateFolders(); return; }

            BookmarkChrome ui = new BookmarkChrome(activity);
            LinearLayout list = ui.column();
            foldersView.removeAllViews();
            foldersView.addView(list, new ScrollView.LayoutParams(-1, -2));

            LinearLayout backRow = ui.row();
            backRow.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            backRow.setMinimumHeight(ui.dp(54));
            backRow.setBackground(ui.ripple());
            LinearLayout.LayoutParams arrowParams =
                    new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
            arrowParams.rightMargin = ui.dp(14);
            backRow.addView(ui.icon(BookmarkChrome.BACK, primary, 22), arrowParams);
            TextView name = ui.text(folder.optString("name", "Folder"), 19, true, primary);
            backRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            backRow.setOnClickListener(v -> populateFolders());
            list.addView(backRow, new LinearLayout.LayoutParams(-1, ui.dp(56)));
            list.addView(ui.divider());

            android.widget.EditText search = ui.input("Search saved posts", "");
            LinearLayout.LayoutParams searchParams =
                    new LinearLayout.LayoutParams(-1, ui.dp(44));
            searchParams.setMargins(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(12));
            list.addView(search, searchParams);

            LinearLayout rows = ui.column();
            list.addView(rows, new LinearLayout.LayoutParams(-1, -2));
            Runnable render = () -> drawSavedPosts(ui, rows, folderIndex,
                    search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT));
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    render.run();
                }
                @Override public void afterTextChanged(android.text.Editable text) {}
            });
            render.run();
        }

        /** Render saved snapshots with X-like tweet rows and real image previews. */
        private void drawSavedPosts(BookmarkChrome ui, LinearLayout rows,
                                    int folderIndex, String query) {
            rows.removeAllViews();
            JSONArray folders = BookmarkFolders.read(activity);
            JSONObject folder = folders.optJSONObject(folderIndex);
            JSONArray posts = folder == null ? null : folder.optJSONArray("posts");
            int matched = 0;
            if (posts != null) {
                for (int i = posts.length() - 1; i >= 0; i--) {
                    JSONObject post = posts.optJSONObject(i);
                    if (post == null) continue;
                    String searchable = (post.optString("text") + " " +
                            post.optString("label") + " " + post.optString("username"))
                            .toLowerCase(java.util.Locale.ROOT);
                    if (!searchable.contains(query)) continue;
                    matched++;
                    int postIndex = i;
                    rows.addView(BookmarkPostView.build(activity, post, () ->
                            BookmarkFolders.showPost(activity, folderIndex,
                                    postIndex, () -> showPosts(folderIndex))),
                            new LinearLayout.LayoutParams(-1, -2));
                    rows.addView(ui.divider());
                }
            }
            if (matched == 0) {
                TextView empty = ui.text(
                        query.isEmpty() ? "No posts in this folder yet" : "No matching posts",
                        15, false, secondary);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(ui.dp(30), ui.dp(54), ui.dp(30), ui.dp(30));
                rows.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        }

    }
}
