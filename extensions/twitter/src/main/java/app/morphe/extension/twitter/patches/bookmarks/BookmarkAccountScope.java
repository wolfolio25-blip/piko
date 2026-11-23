/*
 * Local bookmark-folder account scope. No account credentials or tokens are read.
 * Uses X's own Android AccountManager entries (also used by Piko token import).
 * If multiple accounts exist and the active X account cannot be determined,
 * requests an explicit choice rather than mixing folders across accounts.
 */
package app.morphe.extension.twitter.patches.bookmarks;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

final class BookmarkAccountScope {
    private static final String TYPE = "com.twitter.android.auth.login";
    private static final String STORE = "piko_bookmark_account_scope";
    private static final String SELECTED = "selected_account_id";

    private BookmarkAccountScope() {}

    private static Account[] accounts(Context context) {
        try {
            return AccountManager.get(context.getApplicationContext()).getAccountsByType(TYPE);
        } catch (Throwable ignored) {
            return new Account[0];
        }
    }

    private static String id(Context context, Account account) {
        try {
            String result = AccountManager.get(context.getApplicationContext())
                    .getUserData(account, "account_user_id");
            if (result != null && !result.isEmpty()) return result;
        } catch (Throwable ignored) {}
        return account.name;
    }

    private static String intentAccount(Context context, Account[] accounts) {
        Context current = context;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof Activity) {
                Intent intent = ((Activity) current).getIntent();
                if (intent != null) {
                    for (String key : new String[]{"account_user_id", "account_id", "user_id",
                            "selected_account_id", "account_name"}) {
                        String value;
                        try { value = intent.getStringExtra(key); }
                        catch (Throwable badExtra) { continue; }
                        if (value == null || value.isEmpty()) continue;
                        for (Account account : accounts)
                            if (value.equals(id(context, account)) || value.equals(account.name))
                                return id(context, account);
                    }
                }
                break;
            }
            current = current instanceof ContextWrapper
                    ? ((ContextWrapper) current).getBaseContext() : null;
        }
        return null;
    }

    /**
     * Resolve X's own currently selected account, not the last manually
     * chosen folder owner. Read only selected/current account preference keys.
     * No authentication tokens, cookies, or unrelated values are accessed.
     */
    private static String nativeSelectedAccount(Context context, Account[] entries) {
        try {
            java.io.File prefsDir = new java.io.File(
                    context.getApplicationInfo().dataDir, "shared_prefs");
            java.io.File[] files = prefsDir.listFiles();
            if (files == null) return null;
            String[] keys = {
                    "selected_account", "selected_account_id", "current_account",
                    "current_account_id", "active_account", "active_account_id",
                    "active_user", "active_user_id", "current_user_id",
                    "selectedAccount", "currentAccount", "activeAccount"
            };
            for (String key : keys) {
                String match = null;
                for (java.io.File file : files) {
                    String filename = file.getName();
                    if (!filename.endsWith(".xml") || filename.startsWith("piko_")
                            || filename.startsWith("morphe_")) continue;
                    String name = filename.substring(0, filename.length() - 4);
                    SharedPreferences prefs = context.getSharedPreferences(name,
                            Context.MODE_PRIVATE);
                    if (!prefs.contains(key)) continue;
                    String value;
                    try { value = prefs.getString(key, null); }
                    catch (ClassCastException notString) {
                        try { value = String.valueOf(prefs.getLong(key, 0L)); }
                        catch (ClassCastException notLong) {
                            try { value = String.valueOf(prefs.getInt(key, 0)); }
                            catch (ClassCastException notInt) { continue; }
                        }
                    }
                    if (value == null || value.isEmpty()) continue;
                    for (Account a : entries) {
                        String userId = id(context, a);
                        if (value.equals(userId) || value.equalsIgnoreCase(a.name)
                                || value.equalsIgnoreCase("@" + a.name)) {
                            if (match != null && !match.equals(userId)) return null;
                            match = userId;
                        }
                    }
                }
                if (match != null) return match;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static String current(Context context) {
        Account[] entries = accounts(context);
        if (entries.length == 0) return null;
        if (entries.length == 1) return id(context, entries[0]);

        // Prefer X's live selected account, which changes when users switch
        // in the app. The old manual selection is only a legacy fallback.
        String nativeAccount = nativeSelectedAccount(context, entries);
        if (nativeAccount != null) return nativeAccount;
        String fromIntent = intentAccount(context, entries);
        if (fromIntent != null) return fromIntent;
        String selected = context.getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(SELECTED, "");
        for (Account account : entries)
            if (id(context, account).equals(selected)) return selected;
        return null;
    }

    static String label(Context context) {
        String active = current(context);
        if (active == null) return "Choose X account";
        for (Account a : accounts(context))
            if (active.equals(id(context, a))) return "@" + a.name;
        return "Choose X account";
    }

    static int count(Context context) { return accounts(context).length; }

    static void ensure(Context context, Runnable ready) {
        if (current(context) != null) {
            if (ready != null) ready.run();
            return;
        }
        choose(context, ready);
    }

    static void choose(Context context, Runnable chosen) {
        Account[] entries = accounts(context);
        BookmarkChrome chrome = new BookmarkChrome(context);
        BookmarkChrome.Sheet sheet = chrome.sheet("Bookmark account");
        if (entries.length == 0) {
            sheet.note("No X account is available. Sign in before using local folders.");
            sheet.show();
            return;
        }
        sheet.note("Folders stay separate for each X account. Choose the account you're using.");
        for (Account account : entries) {
            String accountId = id(context, account);
            sheet.row(BookmarkChrome.BOOKMARK, "@" + account.name, null, false, () -> {
                context.getApplicationContext()
                        .getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                        .putString(SELECTED, accountId).apply();
                BookmarkNativeTimeline.clearFolder();
                if (chosen != null) chosen.run();
            });
        }
        sheet.show();
    }
}
