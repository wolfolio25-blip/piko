/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * UI primitives for the local bookmark extension. No stock AlertDialogs,
 * Emoji icons or platform-colored controls: use X's dark/light palette,
 * typography, dividers and a compact custom bottom sheet.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class BookmarkChrome {
    static final int BLUE = 0xFF1D9BF0;
    static final int RED = 0xFFF4212E;

    static final int FOLDER = 1;
    static final int PLUS = 2;
    static final int CHECK = 3;
    static final int CLOSE = 4;
    static final int BACK = 5;
    static final int BOOKMARK = 6;
    static final int MORE = 7;
    static final int TRASH = 8;
    static final int SEARCH = 9;

    final Context context;
    final int background;
    final int text;
    final int secondary;
    final int line;
    final int hover;
    final boolean dark;

    BookmarkChrome(Context context) {
        this.context = context;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean darkTheme = mode == Configuration.UI_MODE_NIGHT_YES;
        try {
            // X has its own light/dim/dark theme independent of Android's setting.
            int color = app.morphe.extension.twitter.Utils.resolveColor(
                    context, "coreColorAppBackground");
            darkTheme = Color.red(color) + Color.green(color) + Color.blue(color) < 384;
        } catch (Exception ignored) {}
        dark = darkTheme;
        background = dark ? 0xFF000000 : 0xFFFFFFFF;
        text = dark ? 0xFFE7E9EA : 0xFF0F1419;
        secondary = dark ? 0xFF71767B : 0xFF536471;
        line = dark ? 0xFF2F3336 : 0xFFEFF3F4;
        hover = dark ? 0xFF16181C : 0xFFF7F9F9;
    }

    int dp(float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    TextView text(String value, int sp, boolean bold, int color) {
        TextView tv = new TextView(context);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(background);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    View divider() {
        View lineView = new View(context);
        lineView.setBackgroundColor(line);
        lineView.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return lineView;
    }

    Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x36FFFFFF : 0x16000000), null, null);
    }

    ImageView icon(int shape, int color, int dimension) {
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(new OutlineIcon(shape, color));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(dimension), dp(dimension)));
        return icon;
    }

    Sheet sheet(String title) {
        return new Sheet(this, title);
    }

    EditText input(String hint, String value) {
        EditText edit = new EditText(context);
        edit.setSingleLine(true);
        edit.setText(value == null ? "" : value);
        edit.setHint(hint);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        edit.setTextColor(text);
        edit.setHintTextColor(secondary);
        edit.setSelectAllOnFocus(value != null && !value.isEmpty());
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(background);
        outline.setCornerRadius(dp(10));
        outline.setStroke(dp(1), line);
        edit.setBackground(outline);
        return edit;
    }

    static final class Sheet {
        private final BookmarkChrome chrome;
        final Dialog dialog;
        final LinearLayout content;
        final LinearLayout scrollContent;

        Sheet(BookmarkChrome chrome, String title) {
            this.chrome = chrome;
            Context c = chrome.context;
            dialog = new Dialog(c);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout surface = chrome.column();
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(chrome.background);
            bg.setCornerRadii(new float[]{chrome.dp(18),chrome.dp(18),
                    chrome.dp(18),chrome.dp(18),0,0,0,0});
            surface.setBackground(bg);
            surface.setClipToOutline(true);

            View handle = new View(c);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(chrome.line);
            handleBg.setCornerRadius(chrome.dp(3));
            handle.setBackground(handleBg);
            LinearLayout.LayoutParams handleParams =
                    new LinearLayout.LayoutParams(chrome.dp(36), chrome.dp(4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.topMargin = chrome.dp(10);
            handleParams.bottomMargin = chrome.dp(18);
            surface.addView(handle, handleParams);

            TextView heading = chrome.text(title, 20, true, chrome.text);
            heading.setPadding(chrome.dp(20), 0, chrome.dp(20), chrome.dp(16));
            surface.addView(heading, new LinearLayout.LayoutParams(-1, -2));

            ScrollView scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(false);
            scrollContent = chrome.column();
            scroll.addView(scrollContent);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
            surface.addView(scroll, scrollParams);
            content = surface;

            dialog.setContentView(surface);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setGravity(Gravity.BOTTOM);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.45f;
                attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
                attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(attrs);
                window.setNavigationBarColor(chrome.background);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }

        Sheet row(int icon, String label, String supporting, boolean destructive, Runnable click) {
            LinearLayout item = chrome.row();
            item.setPadding(chrome.dp(20), chrome.dp(11), chrome.dp(20), chrome.dp(11));
            item.setMinimumHeight(chrome.dp(58));
            item.setBackground(chrome.ripple());

            ImageView symbol = chrome.icon(icon, destructive ? RED : chrome.text, 22);
            LinearLayout.LayoutParams symbolParams = new LinearLayout.LayoutParams(chrome.dp(22), chrome.dp(22));
            symbolParams.rightMargin = chrome.dp(20);
            item.addView(symbol, symbolParams);

            LinearLayout texts = chrome.column();
            texts.setBackgroundColor(Color.TRANSPARENT);
            TextView labelView = chrome.text(label, 16, false, destructive ? RED : chrome.text);
            texts.addView(labelView, new LinearLayout.LayoutParams(-1, -2));
            if (supporting != null && !supporting.isEmpty()) {
                TextView subtitle = chrome.text(supporting, 13, false, chrome.secondary);
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
                subtitleParams.topMargin = chrome.dp(4);
                texts.addView(subtitle, subtitleParams);
            }
            item.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            item.setOnClickListener(v -> {
                dialog.dismiss();
                if (click != null) click.run();
            });
            scrollContent.addView(item, new LinearLayout.LayoutParams(-1, -2));
            return this;
        }

        Sheet separator() {
            scrollContent.addView(chrome.divider());
            return this;
        }

        Sheet custom(View v, int horizontalDp, int bottomDp) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.leftMargin = chrome.dp(horizontalDp);
            params.rightMargin = chrome.dp(horizontalDp);
            params.bottomMargin = chrome.dp(bottomDp);
            scrollContent.addView(v, params);
            return this;
        }

        Sheet note(String value) {
            TextView note = chrome.text(value, 13, false, chrome.secondary);
            note.setPadding(chrome.dp(20), chrome.dp(12),
                    chrome.dp(20), chrome.dp(16));
            scrollContent.addView(note, new LinearLayout.LayoutParams(-1, -2));
            return this;
        }

        Sheet button(String value, Runnable onClick, boolean danger) {
            TextView button = chrome.text(value, 16, true, Color.WHITE);
            button.setGravity(Gravity.CENTER);
            button.setBackground(pill(chrome, danger ? RED : BLUE));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, chrome.dp(44));
            p.setMargins(chrome.dp(20), chrome.dp(8), chrome.dp(20), chrome.dp(18));
            button.setOnClickListener(v -> {
                dialog.dismiss();
                if (onClick != null) onClick.run();
            });
            scrollContent.addView(button, p);
            return this;
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(-1, -2);
                window.setGravity(Gravity.BOTTOM);
            }
        }
    }

    /** Minimal in-app confirmation instead of a stock Android Toast. */
    static void notice(Context context, String message) {
        Context next = context;
        android.app.Activity activity = null;
        for (int i = 0; i < 10 && next != null; i++) {
            if (next instanceof android.app.Activity) {
                activity = (android.app.Activity) next;
                break;
            }
            next = next instanceof android.content.ContextWrapper
                    ? ((android.content.ContextWrapper) next).getBaseContext() : null;
        }
        if (activity == null || activity.isFinishing()) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        BookmarkChrome ui = new BookmarkChrome(activity);
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof android.widget.FrameLayout)) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.FrameLayout frame = (android.widget.FrameLayout) root;
        TextView notice = ui.text(message, 14, false, 0xFFFFFFFF);
        notice.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        notice.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ui.dark ? 0xFF2F3336 : 0xFF0F1419);
        shape.setCornerRadius(ui.dp(12));
        notice.setBackground(shape);
        notice.setElevation(ui.dp(10));
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(-2, -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = ui.dp(72);
        lp.leftMargin = ui.dp(22);
        lp.rightMargin = ui.dp(22);
        frame.addView(notice, lp);
        notice.setAlpha(0f);
        notice.animate().alpha(1f).setDuration(130).start();
        notice.postDelayed(() -> notice.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (notice.getParent() instanceof ViewGroup)
                        ((ViewGroup) notice.getParent()).removeView(notice);
                }).start(), 2200);
    }

    static Drawable pill(BookmarkChrome chrome, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(chrome.dp(30));
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape, shape);
    }

    /** Deterministic stroke icons; no OS emoji or app resource dependencies. */
    private static final class OutlineIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        private final int color;

        OutlineIcon(int kind, int color) { this.kind = kind; this.color = color; }

        @Override public void draw(Canvas original) {
            android.graphics.Rect bounds = getBounds();
            int checkpoint = original.save();
            original.translate(bounds.left, bounds.top);
            original.scale(bounds.width() / 24f, bounds.height() / 24f);
            p.setColor(color);
            p.setStrokeWidth(1.8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);
            Path path = new Path();
            switch (kind) {
                case FOLDER:
                    path.moveTo(3, 7); path.lineTo(3, 19); path.quadTo(3, 21, 5, 21);
                    path.lineTo(19,21); path.quadTo(21,21,21,19); path.lineTo(21,8);
                    path.quadTo(21,6,19,6); path.lineTo(11,6);
                    path.lineTo(9,3); path.lineTo(5,3); path.quadTo(3,3,3,5); path.close();
                    original.drawPath(path,p); break;
                case PLUS:
                    original.drawLine(12,4,12,20,p);
                    original.drawLine(4,12,20,12,p); break;
                case CHECK:
                    path.moveTo(4,12); path.lineTo(9.5f,17.5f); path.lineTo(20,6);
                    original.drawPath(path,p); break;
                case CLOSE:
                    original.drawLine(5,5,19,19,p);
                    original.drawLine(19,5,5,19,p); break;
                case BACK:
                    path.moveTo(15,4); path.lineTo(7,12); path.lineTo(15,20);
                    original.drawPath(path,p); break;
                case BOOKMARK:
                    path.moveTo(7,3); path.lineTo(17,3); path.quadTo(19,3,19,5);
                    path.lineTo(19,21); path.lineTo(12,16); path.lineTo(5,21);
                    path.lineTo(5,5); path.quadTo(5,3,7,3); path.close();
                    original.drawPath(path,p); break;
                case MORE:
                    p.setStyle(Paint.Style.FILL);
                    original.drawCircle(5,12,1.65f,p);
                    original.drawCircle(12,12,1.65f,p);
                    original.drawCircle(19,12,1.65f,p); break;
                case TRASH:
                    original.drawRect(6,8,18,21,p);
                    original.drawLine(4,6,20,6,p);
                    original.drawLine(9,3,15,3,p);
                    original.drawLine(10,11,10,18,p);
                    original.drawLine(14,11,14,18,p); break;
                case SEARCH:
                    original.drawCircle(10.5f,10.5f,6.5f,p);
                    original.drawLine(15.5f,15.5f,21,21,p); break;
                default: break;
            }
            original.restoreToCount(checkpoint);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
