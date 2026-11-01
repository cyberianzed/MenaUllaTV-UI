package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The rows only the Explore-style home screen has: every app on a glass shelf, and what other apps share with the TV
 * (their Continue Watching entries and preview channels, readable with the TV listings permission).
 */
final class HomeRows {
    private HomeRows() {}

    /** Android's "TV listings" permission: with it the TV's provider answers every app's searchable rows. */
    static final String LISTINGS = "android.permission.READ_TV_LISTINGS";
    static final int APP_W = 300, APP_H = 169, APP_GAP = 24;
    private static final int WIDE_W = 384, WIDE_H = 216;

    // MARK: - Apps

    /** Every launchable app as {package, name}: the person's order first (moves on the shelf, Settings > Shelf), then the rest by name. */
    static List<String[]> apps(Context c) {
        Set<String> skip = new HashSet<>(HomeApps.HIDDEN);
        skip.addAll(Prefs.hidden());
        skip.addAll(Arrays.asList(HomeApps.LIVE_TV_APPS));
        skip.add(c.getPackageName());
        Map<String, String> byPkg = new LinkedHashMap<>();
        for (String[] app : HomeApps.launchable(c)) if (!skip.contains(app[0])) byPkg.putIfAbsent(app[0], app[1]);
        List<String[]> out = new ArrayList<>();
        List<String> order = Prefs.shelf();
        if (order == null) order = HomeApps.shelfPackages(c);
        for (String pkg : order) {
            String label = byPkg.remove(pkg);
            if (label != null) out.add(new String[] {pkg, label});
        }
        List<String[]> rest = new ArrayList<>();
        for (Map.Entry<String, String> e : byPkg.entrySet()) rest.add(new String[] {e.getKey(), e.getValue()});
        Collections.sort(rest, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        out.addAll(rest);
        return out;
    }

    /** Every launchable app but this one, by name (All apps shows the hidden ones too). */
    static List<String[]> allApps(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] app : HomeApps.launchable(c)) if (!app[0].equals(c.getPackageName()) && seen.add(app[0])) out.add(app);
        Collections.sort(out, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return out;
    }

    interface OnApp { void on(View card, String pkg); }

    /** An app's card: its TV banner (or icon), the package as its tag. */
    static View appCard(Activity a, float k, String pkg, String label, Bitmap tile, OnApp open, OnApp hold) {
        int w = Math.round(APP_W * k), h = Math.round(APP_H * k);
        FrameLayout card = new FrameLayout(a);
        card.setBackground(TvUi.round(TvUi.CARD, 16 * k));
        card.setClipToOutline(true);
        card.setTag(pkg);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(tile);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setContentDescription(label);
        card.setOnClickListener(v -> open.on(v, pkg));
        if (hold != null) card.setOnLongClickListener(v -> {
            hold.on(v, pkg);
            return true;
        });
        TvUi.focusable(card, k, 16 * k);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = Math.round(APP_GAP * k);
        card.setLayoutParams(lp);
        return card;
    }

    // MARK: - What other apps share: Continue Watching and preview channels

    static final class Card {
        String key, title, sub, image, intent, pkg;
        boolean portrait;
        long position, duration, when;
    }

    static boolean canRead(Context c) {
        return c.checkSelfPermission(LISTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The TV's Continue Watching list (apps put their unfinished titles there), most recent first. */
    static List<Card> watchNext(Context c) {
        List<Card> out = new ArrayList<>();
        if (!canRead(c)) return out;
        Set<String> apps = launchablePackages(c);
        try (Cursor q = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (q != null && q.moveToNext()) {
                if (num(q, "browsable", 1) == 0) continue;
                Card card = card(q, "wn:");
                if (card == null || !apps.contains(card.pkg)) continue;
                card.when = num(q, "last_engagement_time_utc_millis", 0);
                card.position = num(q, "last_playback_position_millis", 0);
                card.duration = num(q, "duration_millis", 0);
                out.add(card);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
        Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    /** Each app's preview channels ("YouTube · Recommended"), as {row title, channel id}; this app's own skipped. */
    static List<Object[]> channels(Context c) {
        List<Object[]> out = new ArrayList<>();
        if (!canRead(c)) return out;
        Set<String> apps = launchablePackages(c);
        PackageManager pm = c.getPackageManager();
        org.json.JSONObject only = Prefs.appChannels();
        org.json.JSONArray drop = Prefs.hiddenChannels();
        try (Cursor q = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            while (q != null && q.moveToNext() && out.size() < 8) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(str(q, "type"))) continue;
                String pkg = str(q, "package_name");
                if (pkg.isEmpty() || !apps.contains(pkg) || pkg.equals(c.getPackageName()) || Prefs.hidden().contains(pkg)) continue;
                String app;
                try {
                    app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                String name = str(q, "display_name");
                org.json.JSONArray keep = only.optJSONArray(pkg);
                if (keep != null && !listed(keep, name)) continue;   // config.json "app_channels" keeps only these
                String title = name.isEmpty() || name.equalsIgnoreCase(app) ? app : app + " · " + name;
                if (listed(drop, name) || listed(drop, title)) continue;   // config.json "hidden_channels"
                out.add(new Object[] {title, num(q, "_id", -1), pkg});
            }
        } catch (RuntimeException e) {
            AppLog.w("channels: " + e.getMessage());
        }
        return out;
    }

    private static boolean listed(org.json.JSONArray names, String name) {
        for (int i = 0; i < names.length(); i++) if (names.optString(i).trim().equalsIgnoreCase(name.trim())) return true;
        return false;
    }

    static List<Card> channelPrograms(Context c, long channel) {
        List<Card> out = new ArrayList<>();
        if (!canRead(c)) return out;
        try (Cursor q = c.getContentResolver().query(TvContract.buildPreviewProgramsUriForChannel(channel), null, null, null, null)) {
            while (q != null && q.moveToNext() && out.size() < 20) {
                if (num(q, "browsable", 1) == 0) continue;
                Card card = card(q, "pp:");
                if (card != null) out.add(card);
            }
        } catch (RuntimeException e) {
            AppLog.w("channel " + channel + ": " + e.getMessage());
        }
        return out;
    }

    private static Card card(Cursor q, String prefix) {
        Card card = new Card();
        card.key = prefix + num(q, "_id", 0);
        card.pkg = str(q, "package_name");
        card.title = str(q, "title");
        card.intent = str(q, "intent_uri");
        String episode = str(q, "episode_title");
        long season = num(q, "season_display_number", 0), number = num(q, "episode_display_number", 0);
        if (season > 0 && number > 0) card.sub = "S" + season + " E" + number + (episode.isEmpty() ? "" : " · " + episode);
        else card.sub = episode;
        String poster = str(q, "poster_art_uri"), thumb = str(q, "thumbnail_uri");
        long posterRatio = num(q, "poster_art_aspect_ratio", -1);
        // 4 = 2:3 and 5 = movie poster: the only portrait shapes. A landscape thumbnail beats a portrait poster in a wide row.
        card.portrait = !poster.isEmpty() && (posterRatio == 4 || posterRatio == 5) && thumb.isEmpty();
        card.image = card.portrait || thumb.isEmpty() ? poster : thumb;
        if (card.title.isEmpty() || card.intent.isEmpty()) return null;
        return card;
    }

    private static Set<String> launchablePackages(Context c) {
        Set<String> out = new HashSet<>();
        for (String[] app : HomeApps.launchable(c)) out.add(app[0]);
        return out;
    }

    private static String str(Cursor q, String col) {
        int i = q.getColumnIndex(col);
        if (i < 0 || q.isNull(i)) return "";
        String s = q.getString(i);
        return s == null ? "" : s;
    }

    private static long num(Cursor q, String col, long fallback) {
        int i = q.getColumnIndex(col);
        if (i < 0 || q.isNull(i)) return fallback;
        try {
            return q.getLong(i);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Opens what the card points at in its app (the app itself when the link is gone). */
    static void open(Activity a, Card card) {
        Intent intent;
        try {
            intent = card.intent.startsWith("intent:") || card.intent.contains("#Intent;")
                    ? Intent.parseUri(card.intent, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(card.intent));
        } catch (Exception e) {
            intent = null;
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(intent);
                return;
            } catch (RuntimeException e) {
                AppLog.w("couldn't open " + card.title + ": " + e.getMessage());
            }
        }
        Intent app = HomeApps.launchIntent(a.getPackageManager(), card.pkg);
        if (app != null) {
            try {
                a.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
    }

    interface OnCard { void open(Card card); }

    /** A row of shared cards, laid out like Explore's rows: wide pictures, or posters for a channel of posters. */
    static View cardsRow(Activity a, float k, String title, List<Card> cards, int inset, OnCard open) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h = TvUi.text(a, k, 32, Ui.semibold, TvUi.INK);
        h.setText(title);
        h.setPadding(0, 0, 0, Math.round(18 * k));
        box.addView(h);
        TvUi.Row line = new TvUi.Row(a);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(inset + Math.round(24 * k), Math.round(30 * k), Math.round(96 * k), Math.round(20 * k));
        boolean posters = true;
        for (Card c : cards) posters &= c.portrait;
        for (Card c : cards) line.addView(cardView(a, k, c, posters, open));
        TvUi.HScroll scroll = new TvUi.HScroll(a, Math.round(300 * k));
        scroll.addView(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -inset;
        box.addView(scroll, lp);
        box.setClipChildren(false);
        box.setPadding(0, Math.round(26 * k), 0, Math.round(10 * k));
        return box;
    }

    private static View cardView(Activity a, float k, Card c, boolean poster, OnCard open) {
        int w = Math.round((poster ? TvUi.POSTER_W : WIDE_W) * k), h = Math.round((poster ? TvUi.POSTER_H : WIDE_H) * k);
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = new FrameLayout(a);
        card.setBackground(TvUi.round(TvUi.CARD, 14 * k));
        card.setClipToOutline(true);
        TextView fallback = TvUi.text(a, k, 24, Ui.medium, TvUi.DIM);
        fallback.setText(c.title);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(Math.round(14 * k), 0, Math.round(14 * k), 0);
        card.addView(fallback, new FrameLayout.LayoutParams(w, h));
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(w, h));
        load(img, c.image, w, h);
        if (c.duration > 0 && c.position > 0) {   // how far along: a thin bar over the picture's bottom
            FrameLayout track = new FrameLayout(a);
            track.setBackground(TvUi.round(0x59FFFFFF, 3 * k));
            View done = new View(a);
            done.setBackground(TvUi.round(TvUi.INK, 3 * k));
            int barW = w - Math.round(36 * k);
            float f = Math.max(0.04f, Math.min(1f, (float) c.position / c.duration));
            track.addView(done, new FrameLayout.LayoutParams(Math.round(barW * f), ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(barW, Math.round(6 * k), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            tl.bottomMargin = Math.round(16 * k);
            card.addView(track, tl);
        }
        card.setContentDescription(c.title);
        card.setOnClickListener(v -> open.open(c));
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        TextView name = TvUi.text(a, k, 22, Ui.regular, TvUi.DIM);
        TvUi.focusable(card, k, 14 * k, name);
        name.setText(c.sub == null || c.sub.isEmpty() ? c.title : c.title + " · " + c.sub);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMarqueeRepeatLimit(-1);
        name.setPadding(0, Math.round(26 * k), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(TvUi.POSTER_GAP * k);
        cell.setLayoutParams(lp);
        return cell;
    }

    // MARK: - The language · genre bar

    /** A line that, entered from above or below, lands on the word focused last (the dot at first). */
    static final class FocusRow extends LinearLayout {
        private View last;

        FocusRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
        }

        void remember(View v) {
            last = v;
        }

        @Override public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            last = child;
            if (getParent() instanceof CenterScroll) ((CenterScroll) getParent()).center(child, true);
        }

        @Override public void addFocusables(java.util.ArrayList<View> views, int direction, int mode) {
            if (hasFocus() || last == null || last.getParent() != this || (direction != FOCUS_UP && direction != FOCUS_DOWN)) {
                super.addFocusables(views, direction, mode);
            } else {
                last.addFocusables(views, direction, mode);
            }
        }
    }

    /** Keeps the focused word in the middle of the screen, as a dial would. */
    static final class CenterScroll extends android.widget.HorizontalScrollView {
        CenterScroll(Context c) {
            super(c);
            setFocusable(false);
            setHorizontalScrollBarEnabled(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        void center(View child, boolean smooth) {
            if (getWidth() == 0) return;
            int x = child.getLeft() + child.getWidth() / 2 - getWidth() / 2;
            if (smooth) smoothScrollTo(x, 0);
            else scrollTo(x, 0);
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            return 0;   // center() does the scrolling
        }
    }

    // MARK: - Pictures from other apps (content:// and android.resource://, not only the web)

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    private static void load(ImageView img, String uri, int w, int h) {
        if (uri == null || uri.isEmpty()) return;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            Images.load(img, uri, w, h);
            return;
        }
        img.setTag(Uri.parse(uri));   // not a String: Explore's release/restore of pictures leaves it alone
        Bitmap hit = CACHE.get(uri);
        if (hit != null) {
            img.setImageBitmap(hit);
            return;
        }
        ContentResolver cr = img.getContext().getContentResolver();
        POOL.execute(() -> {
            Bitmap b = decode(cr, Uri.parse(uri), w, h);
            if (b == null) return;
            CACHE.put(uri, b);
            MAIN.post(() -> { if (Uri.parse(uri).equals(img.getTag())) img.setImageBitmap(b); });
        });
    }

    private static Bitmap decode(ContentResolver cr, Uri uri, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            try (InputStream in = cr.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, real); }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
