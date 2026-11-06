/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Flat X-style local bookmark post previews. Opens real tweets in X.
 * Images are loaded only from X's pbs.twimg.com media host.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BookmarkPostView {
    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> CACHE = new LruCache<String,Bitmap>(16) {
        @Override protected int sizeOf(String key, Bitmap value) { return 1; }
    };

    private BookmarkPostView() {}

    static View build(Context context, JSONObject post, Runnable onActions) {
        BookmarkChrome theme = new BookmarkChrome(context);
        LinearLayout root = theme.column();
        root.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(14));
        root.setBackground(theme.ripple());

        LinearLayout top = theme.row();
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        String user = post.optString("username", "");
        String name = post.optString("displayName", user);
        if (user.isEmpty()) {
            String legacy = post.optString("label", "");
            if (legacy.startsWith("@") && legacy.contains(" — ")) {
                user = legacy.substring(1, legacy.indexOf(" — "));
                name = user;
            }
        }
        TextView avatar = theme.text(
                name.isEmpty() ? "•" : name.substring(0, 1).toUpperCase(Locale.ROOT), 17, true, Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setColor(theme.dark ? 0xFF24364A : 0xFF536D85);
        circle.setCornerRadius(theme.dp(21));
        avatar.setBackground(circle);
        top.addView(avatar, new LinearLayout.LayoutParams(theme.dp(42), theme.dp(42)));

        LinearLayout content = theme.column();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.leftMargin = theme.dp(10);
        top.addView(content, cp);

        LinearLayout header = theme.row();
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        String displayed = name.isEmpty() ? "Saved post" : name;
        TextView displayName = theme.text(displayed, 15, true, theme.text);
        displayName.setSingleLine(true);
        displayName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(displayName, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView actions = theme.text("···", 24, false, theme.secondary);
        actions.setGravity(Gravity.CENTER);
        actions.setContentDescription("Post options");
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                theme.dp(38), theme.dp(34));
        actionParams.leftMargin = theme.dp(8);
        header.addView(actions, actionParams);
        actions.setBackground(theme.ripple());
        actions.setOnClickListener(v -> { if (onActions != null) onActions.run(); });

        if (!user.isEmpty()) {
            TextView handle = theme.text("@" + user, 14, false, theme.secondary);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = theme.dp(1);
            content.addView(handle, hp);
        }

        String text = post.optString("text", "");
        if (text.isEmpty()) {
            String label = post.optString("label", "");
            int sep = label.indexOf(" — ");
            text = sep >= 0 ? label.substring(sep + 3) : label;
        }
        if (!text.isEmpty()) {
            TextView body = theme.text(text, 15, false, theme.text);
            body.setGravity(Gravity.START);
            body.setMaxLines(8);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setLineSpacing(theme.dp(2), 1f);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = theme.dp(8);
            content.addView(body, bp);
        }

        String media = post.optString("mediaUrl", "");
        if (media.startsWith("https://pbs.twimg.com/")) {
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable background = new GradientDrawable();
            background.setColor(theme.hover);
            background.setCornerRadius(theme.dp(15));
            background.setStroke(theme.dp(1), theme.line);
            image.setBackground(background);
            image.setClipToOutline(true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, theme.dp(194));
            p.topMargin = theme.dp(12);
            content.addView(image, p);
            loadImage(image, media);
        } else if ("video".equals(post.optString("mediaType", ""))) {
            TextView video = theme.text("Video · Tap to play in X", 14, false, theme.secondary);
            video.setGravity(Gravity.CENTER);
            GradientDrawable box = new GradientDrawable();
            box.setColor(theme.hover);
            box.setCornerRadius(theme.dp(15));
            box.setStroke(theme.dp(1), theme.line);
            video.setBackground(box);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, theme.dp(100));
            vp.topMargin = theme.dp(12);
            content.addView(video, vp);
        }

        root.setOnClickListener(v -> BookmarkFolders.openPost(context, post));
        return root;
    }

    private static void loadImage(ImageView view, String mediaUrl) {
        Bitmap cached = CACHE.get(mediaUrl);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        IMAGES.execute(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;
            try {
                URL url = new URL(mediaUrl);
                if (!"https".equalsIgnoreCase(url.getProtocol())
                        || !"pbs.twimg.com".equalsIgnoreCase(url.getHost())) return;
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getResponseCode() != 200) return;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 3;
                bitmap = BitmapFactory.decodeStream(connection.getInputStream(), null, opts);
                if (bitmap != null) CACHE.put(mediaUrl, bitmap);
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            Bitmap result = bitmap;
            if (result != null) {
                view.post(() -> {
                    if (view.getWindowToken() != null && !result.isRecycled()) {
                        view.setImageBitmap(result);
                    }
                });
            }
        });
    }
}
