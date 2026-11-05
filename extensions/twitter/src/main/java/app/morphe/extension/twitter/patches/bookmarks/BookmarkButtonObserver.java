/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Observe Android's actual clicked bookmark controls (including inline tweet
 * icons) without replacing X's onClick listeners or disrupting server bookmarks.
 * Uses public View accessibility APIs rather than guessed obfuscated methods.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import app.morphe.extension.twitter.entity.Tweet;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class BookmarkButtonObserver {
    private static final String TAG = "PikoBookmarkObserver";
    private static final WeakHashMap<Activity, Watcher> WATCHERS = new WeakHashMap<>();
    private static boolean installed;
    private static String lastTweetId = "";
    private static long lastClickTime;

    private BookmarkButtonObserver() {}

    public static synchronized void install(Application app) {
        if (installed || app == null || Build.VERSION.SDK_INT < 29) return;
        installed = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle state) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {
                if (!a.getPackageName().equals("com.twitter.android")) return;
                View root = a.getWindow().getDecorView();
                if (root == null) return;
                Watcher watcher = WATCHERS.get(a);
                if (watcher == null) {
                    watcher = new Watcher(root);
                    WATCHERS.put(a, watcher);
                    watcher.attach();
                } else {
                    watcher.scan();
                }
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle s) {}
            @Override public void onActivityDestroyed(Activity a) {
                Watcher watcher = WATCHERS.remove(a);
                if (watcher != null) watcher.detach();
            }
        });
    }

    private static final class Watcher {
        final View root;
        final ViewTreeObserver.OnGlobalLayoutListener onLayout;
        final ViewTreeObserver.OnScrollChangedListener onScroll;
        long scannedAt;
        Watcher(View root) {
            this.root = root;
            onLayout = this::scan;
            onScroll = this::scan;
        }
        void attach() {
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(onLayout);
                root.getViewTreeObserver().addOnScrollChangedListener(onScroll);
            }
            root.post(this::scan);
        }
        void detach() {
            ViewTreeObserver tree = root.getViewTreeObserver();
            if (tree.isAlive()) {
                tree.removeOnGlobalLayoutListener(onLayout);
                tree.removeOnScrollChangedListener(onScroll);
            }
        }
        void scan() {
            long now = SystemClock.uptimeMillis();
            if (now - scannedAt < 300) return;
            scannedAt = now;
            int[] remaining = {2600};
            inspectViews(root, remaining, 0);
        }
    }

    private static void inspectViews(View view, int[] remaining, int depth) {
        if (view == null || depth > 30 || remaining[0]-- <= 0) return;
        if (isBookmarkControl(view)) {
            View target = view;
            if (!target.isClickable()) {
                ViewParent parent = target.getParent();
                for (int i = 0; i < 2 && parent instanceof View; i++) {
                    View p = (View) parent;
                    if (p.isClickable()) { target = p; break; }
                    parent = p.getParent();
                }
            }
            if (target.isClickable() &&
                    !(target.getAccessibilityDelegate() instanceof ClickDelegate)) {
                View.AccessibilityDelegate original = target.getAccessibilityDelegate();
                target.setAccessibilityDelegate(new ClickDelegate(original));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && remaining[0] > 0; i++) {
                inspectViews(group.getChildAt(i), remaining, depth + 1);
            }
        }
    }

    private static boolean isBookmarkControl(View view) {
        if (!view.isShown()) return false;
        CharSequence desc = view.getContentDescription();
        if (desc != null && desc.toString().toLowerCase(java.util.Locale.ROOT).contains("bookmark")) {
            return true;
        }
        int id = view.getId();
        if (id != View.NO_ID && id != 0) {
            try {
                String name = view.getResources().getResourceEntryName(id)
                        .toLowerCase(java.util.Locale.ROOT);
                return name.contains("bookmark") || name.contains("tweet_save");
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static final class ClickDelegate extends View.AccessibilityDelegate {
        final View.AccessibilityDelegate original;
        ClickDelegate(View.AccessibilityDelegate original) { this.original = original; }

        @Override
        public void sendAccessibilityEvent(View host, int eventType) {
            if (original == null) super.sendAccessibilityEvent(host, eventType);
            else original.sendAccessibilityEvent(host, eventType);
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                host.post(() -> onBookmarkClicked(host));
            }
        }

        @Override
        public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
            if (original == null) super.sendAccessibilityEventUnchecked(host, event);
            else original.sendAccessibilityEventUnchecked(host, event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            return original == null ? super.dispatchPopulateAccessibilityEvent(host, event)
                    : original.dispatchPopulateAccessibilityEvent(host, event);
        }

        @Override
        public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            if (original == null) super.onPopulateAccessibilityEvent(host, event);
            else original.onPopulateAccessibilityEvent(host, event);
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            if (original == null) super.onInitializeAccessibilityEvent(host, event);
            else original.onInitializeAccessibilityEvent(host, event);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            if (original == null) super.onInitializeAccessibilityNodeInfo(host, info);
            else original.onInitializeAccessibilityNodeInfo(host, info);
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(
                ViewGroup host, View child, AccessibilityEvent event) {
            return original == null ? super.onRequestSendAccessibilityEvent(host, child, event)
                    : original.onRequestSendAccessibilityEvent(host, child, event);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            return original == null ? super.performAccessibilityAction(host, action, args)
                    : original.performAccessibilityAction(host, action, args);
        }
    }

    private static void onBookmarkClicked(View clicked) {
        Context ctx = clicked.getContext();
        Activity activity = findActivity(ctx);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        // Do not open a picker for an already-bookmarked post being REMOVED.
        String description = clicked.getContentDescription() == null ? ""
                : clicked.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT);
        if (description.contains("remove") || description.contains("delete")) return;

        Tweet tweet = findTweet(clicked);
        if (tweet == null) {
            // Never save a guessed ID to the wrong folder.
            android.util.Log.w(TAG, "Couldn't resolve tweet model from inline bookmark control");
            return;
        }
        try {
            String id = String.valueOf(tweet.getTweetId());
            long now = SystemClock.uptimeMillis();
            if (id.equals(lastTweetId) && now - lastClickTime < 1500) return;
            lastTweetId = id;
            lastClickTime = now;
            BookmarkFolders.saveTweet(activity, tweet);
        } catch (Exception exception) {
            android.util.Log.w(TAG, "Could not open folder picker", exception);
        }
    }

    private static Activity findActivity(Context ctx) {
        for (int i = 0; i < 10 && ctx != null; i++) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ctx instanceof android.content.ContextWrapper
                    ? ((android.content.ContextWrapper) ctx).getBaseContext() : null;
        }
        return null;
    }

    private static Tweet findTweet(View view) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] budget = {420};
        View current = view;
        for (int i = 0; current != null && i < 12; i++) {
            Tweet fromTag = probe(current.getTag(), seen, budget, 0);
            if (fromTag != null) return fromTag;
            Tweet fromView = probe(current, seen, budget, 0);
            if (fromView != null) return fromView;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static Tweet probe(Object obj, Set<Object> seen, int[] budget, int depth) {
        if (obj == null || depth > 4 || budget[0]-- <= 0 || !seen.add(obj)) return null;
        Class<?> type = obj.getClass();
        String name = type.getName();

        if (name.startsWith("com.twitter.") || name.startsWith("com.x.")) {
            // Tweet#getTweetId and getTweetUsername are resolved by Piko's
            // generated entity patch. Require both to reject user IDs.
            try {
                java.lang.reflect.Method id = type.getDeclaredMethod("getId");
                if (id.getParameterTypes().length == 0 &&
                        (id.getReturnType() == long.class || id.getReturnType() == Long.class)) {
                    Tweet tweet = new Tweet(obj);
                    if (tweet.getTweetId() != null && tweet.getTweetUsername() != null) return tweet;
                }
            } catch (Exception ignored) {}
        }

        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (int i = 0; i < Math.min(list.size(), 6); i++) {
                Tweet tweet = probe(list.get(i), seen, budget, depth + 1);
                if (tweet != null) return tweet;
            }
            return null;
        }

        if (obj instanceof Object[]) {
            Object[] values = (Object[]) obj;
            for (int i = 0; i < Math.min(values.length, 6); i++) {
                Tweet tweet = probe(values[i], seen, budget, depth + 1);
                if (tweet != null) return tweet;
            }
            return null;
        }

        // Read fields only from X-owned classes, not Android framework internals.
        // App-defined View subclasses commonly retain a bound Tweet or timeline item.
        if (!name.startsWith("com.twitter.") && !name.startsWith("com.x.")
                && !name.startsWith("app.morphe.")) return null;
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            String declaring = cls.getName();
            if (!declaring.startsWith("com.twitter.") && !declaring.startsWith("com.x.")
                    && !declaring.startsWith("app.morphe.")) break;
            for (Field field : cls.getDeclaredFields()) {
                if (budget[0] <= 0) return null;
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value == null || value instanceof String || value instanceof Number
                            || value instanceof Context || value instanceof View) continue;
                    Tweet tweet = probe(value, seen, budget, depth + 1);
                    if (tweet != null) return tweet;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
