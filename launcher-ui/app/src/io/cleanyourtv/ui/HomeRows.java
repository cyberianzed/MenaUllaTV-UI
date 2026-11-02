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
        String series;   // the TV's series id for an episode (TvContract), when the app gives one
        boolean portrait;
        long position, duration, when;
        long rank;   // when it was last watched, as best known: the app's own time, or when its position last moved
    }

    static boolean canRead(Context c) {
        return c.checkSelfPermission(LISTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The TV's Continue Watching list (apps put their unfinished titles there), most recent first. */
    static List<Card> watchNext(Context c) {
        List<Card> out = new ArrayList<>();
        if (!canRead(c)) return out;
        Set<String> apps = launchablePackages(c);
        Set<String> serverDown = new HashSet<>();   // apps whose home media server (Emby, Jellyfin, Plex) is off
        Set<String> hidden = Prefs.hiddenWatchNext();
        try (Cursor q = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (q != null && q.moveToNext()) {
                if (num(q, "browsable", 1) == 0) continue;
                Card card = card(q, "wn:");
                if (card == null || !apps.contains(card.pkg)) continue;
                if (!serverUp(card)) serverDown.add(card.pkg);
                card.when = num(q, "last_engagement_time_utc_millis", 0);
                card.position = num(q, "last_playback_position_millis", 0);
                if (hidden.contains(card.key + "@" + card.when)) continue;   // taken off the row on this TV
                card.duration = num(q, "duration_millis", 0);
                card.series = str(q, "series_id");
                out.add(card);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
        // All of that app's titles wait for its server, even those whose own picture is the app's placeholder.
        if (!serverDown.isEmpty()) out.removeIf(card -> serverDown.contains(card.pkg));
        rank(out);
        played(c, out, apps, hidden);
        Collections.sort(out, (a, b) -> Long.compare(b.rank, a.rank));
        onePerShow(out);
        List<Card> top = out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
        // The row never waits for pictures: those already found are used, the rest are looked up apart and the row
        // is refreshed once they arrive (see onArt).
        List<String> missing = new ArrayList<>();
        for (Card card : top) {
            if (card.image != null && !card.image.isEmpty()) continue;
            String known = knownArt(card.title);
            if (known != null) card.image = known;
            else missing.add(card.title);
        }
        if (!missing.isEmpty()) lookUpArt(missing);
        for (Card card : top) appBadge(c, card.pkg);
        AppLog.w("watch next: " + top.size() + " titles, " + missing.size() + " pictures to look up");
        if (!loggedLinks) {
            loggedLinks = true;
            for (Card card : top) {
                if (!card.pkg.contains("emby")) continue;
                Uri img = card.image == null || card.image.isEmpty() ? null : Uri.parse(card.image);
                AppLog.w("watch next link: " + card.pkg + " picture " + (img == null ? "none" : img.getScheme() + "://" + img.getHost()));
            }
        }
        return top;
    }

    /** Each app's icon, small (56 px) and rounded, for Continue watching's corner badge: once per app per process. */
    private static final java.util.Map<String, Bitmap> BADGES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void appBadge(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || BADGES.containsKey(pkg)) return;
        try {
            android.graphics.drawable.Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            int size = 56;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(0, 0, size, size, size * 0.24f, size * 0.24f, android.graphics.Path.Direction.CW);
            cv.clipPath(clip);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            BADGES.put(pkg, b);
        } catch (Exception e) {
            AppLog.w("badge for " + pkg + ": " + e.getMessage());
        }
    }

    /**
     * What video apps said they played (NowPlaying, with Notification access): a title already in the row moves to when
     * it was played; one that isn't (SonyLIV never adds its own) joins it, opening its app. The last two weeks.
     */
    private static void played(Context c0, List<Card> cards, Set<String> apps, Set<String> hidden) {
        org.json.JSONArray all = Prefs.played();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            org.json.JSONObject p = all.optJSONObject(i);
            if (p == null) continue;
            String pkg = p.optString("pkg"), title = p.optString("title");
            long at = p.optLong("t");
            if (!apps.contains(pkg) || title.isEmpty() || now - at > 14L * 24 * 3600_000) continue;
            String name = norm(title);
            Card match = null;
            for (Card c : cards) {
                if (!pkg.equals(c.pkg)) continue;
                String other = norm(c.title);
                if (!other.isEmpty() && (other.contains(name) || name.contains(other))) { match = c; break; }
            }
            if (match != null) {
                match.rank = Math.max(match.rank, at);
                // The app's own entry is older: the episode and how far in are what was just played.
                if (at >= match.when) {
                    String episode = p.optString("sub");
                    if (!episode.isEmpty()) match.sub = episode;
                    String art = playedArt(c0, p.optString("img"), title);
                    // The app's own picture first; the one it showed while playing only where it gave none.
                    if (!art.isEmpty() && (match.image == null || match.image.isEmpty())) match.image = art;
                    if (p.optLong("d") > 0) {
                        match.position = p.optLong("p");
                        match.duration = p.optLong("d");
                    }
                }
                AppLog.w("continue watching: played \"" + title + "\" moves up");
                continue;
            }
            Card card = new Card();
            card.key = "wn:np:" + pkg + ":" + Integer.toHexString(title.hashCode());
            card.when = at;
            if (hidden.contains(card.key + "@" + card.when)) continue;
            card.pkg = pkg;
            card.title = title;
            card.image = playedArt(c0, p.optString("img"), title);   // the app's wide picture, else TMDB's
            card.intent = "";       // opens the app
            card.sub = p.optString("sub");
            card.position = p.optLong("p");
            card.duration = p.optLong("d");
            card.rank = at;
            cards.add(card);
            AppLog.w("continue watching: played \"" + title + "\" added");
        }
    }

    /**
     * The picture for a played title: the app's own when it's wide; a tall one (SonyLIV's posters) gives way to TMDB's
     * wide backdrop for that exact title (looked up apart; the row is redrawn when it comes), and is shown framed only
     * until then or when TMDB has none. "" when there's nothing: TMDB is then looked up like any card without one.
     */
    private static String playedArt(Context c0, String own, String title) {
        String shown = NowPlaying.widen(c0, own);
        boolean tall = shown != null && shown.startsWith("file:") && shown.endsWith("-w.jpg");
        if (!tall) return shown;
        String tmdb = knownArt(title);
        if (tmdb == null) {   // not looked up yet: now, apart
            java.util.List<String> one = new ArrayList<>();
            one.add(title);
            lookUpArt(one);
            return shown;
        }
        return tmdb.isEmpty() ? shown : tmdb;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * One card per show, its latest episode (the list is sorted latest first): as Android TV asks apps to keep one
     * Continue watching entry per series, which some (and file players) don't. The same show is the same TV series id
     * in one app, or the same name cleaned of episode tags ("Show - S01E54 (BD)" and "Show: · S1 E49"), in any app.
     */
    private static void onePerShow(List<Card> cards) {
        Set<String> seen = new HashSet<>();
        java.util.Iterator<Card> it = cards.iterator();
        while (it.hasNext()) {
            Card card = it.next();
            String name = norm(cleanTitle(card.title));
            boolean repeat = false;
            if (card.series != null && !card.series.isEmpty() && !seen.add("id:" + card.pkg + ":" + card.series)) repeat = true;
            if (!name.isEmpty() && !seen.add("name:" + name)) repeat = true;
            if (repeat) it.remove();
        }
    }

    private static boolean loggedTimes;
    private static int loggedNetflix;

    /**
     * Latest watched first, even for apps that don't keep their "last watched" time up to date (SonyLIV): the
     * launcher remembers each title's position, and a title whose position moved, or that's new to the row, counts as
     * watched now. Only the titles in the row are remembered.
     */
    private static void rank(List<Card> cards) {
        org.json.JSONObject before = Prefs.watchNextSeen(), after = new org.json.JSONObject(), launched = Prefs.launches();
        long now = System.currentTimeMillis();
        boolean known = before.length() > 0;   // the first look at the list marks nothing: it has no "before"
        // Each app's latest title (by the app's own time) also takes the time Home last opened that app.
        java.util.Map<String, Card> latest = new java.util.HashMap<>();
        for (Card card : cards) {
            Card top = latest.get(card.pkg);
            if (top == null || card.when > top.when) latest.put(card.pkg, card);
        }
        for (Card card : cards) {
            org.json.JSONObject was = before.optJSONObject(card.key);
            long mark = was == null ? (known ? now : 0) : was.optLong("t", 0);
            if (was != null && was.optLong("p", -1) != card.position) mark = now;
            card.rank = Math.max(card.when, Math.max(mark, launched.optLong(card.key, 0)));
            if (latest.get(card.pkg) == card) card.rank = Math.max(card.rank, launched.optLong(card.pkg, 0));
            try {
                after.put(card.key, new org.json.JSONObject().put("p", card.position).put("t", mark));
            } catch (org.json.JSONException ignored) {
            }
            if (!loggedTimes && card.pkg.contains("sony")) {
                AppLog.w("watch next time: " + card.pkg + " app " + card.when + ", position " + card.position);
            }
        }
        loggedTimes = true;
        Prefs.setWatchNextSeen(after);
    }

    private static final java.util.Map<String, Object[]> SERVERS = new java.util.HashMap<>();   // host:port -> {up, when}

    /**
     * False when the card's picture (or link) points at a media server on the home network that doesn't answer:
     * Emby, Jellyfin or Plex switched off. Their titles would only show without pictures and fail to open. A quick
     * connection (0.4 s), remembered for a minute. Anything else, and anything on the internet, is kept.
     */
    static boolean serverUp(Card card) {
        Uri u = null;
        for (String link : new String[] {card.image, card.intent}) {
            if (link == null || !(link.startsWith("http://") || link.startsWith("https://"))) continue;
            u = Uri.parse(link);
            break;
        }
        if (u == null || u.getHost() == null || !local(u.getHost())) return true;
        int port = u.getPort() > 0 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
        String key = u.getHost() + ":" + port;
        long now = System.currentTimeMillis();
        synchronized (SERVERS) {
            Object[] seen = SERVERS.get(key);
            if (seen != null && now - (Long) seen[1] < 60_000L) return (Boolean) seen[0];
        }
        boolean up;
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(u.getHost(), port), 400);
            up = true;
        } catch (Exception e) {
            up = false;
        }
        synchronized (SERVERS) { SERVERS.put(key, new Object[] {up, now}); }
        if (!up) AppLog.w("continue watching: " + card.pkg + "'s server " + key + " doesn't answer, its titles are hidden");
        return up;
    }

    private static boolean local(String host) {
        return host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local") || host.equals("localhost")
                || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    private static final java.util.Map<String, String> ART = new java.util.HashMap<>();
    private static final java.util.Set<String> LOOKING = new java.util.HashSet<>();
    private static boolean loggedLinks;
    /** Lookups that failed (offline, a bad key), and when: not tried again for 10 minutes. */
    private static final java.util.Map<String, Long> FAILED = new java.util.HashMap<>();
    private static final ExecutorService ART_POOL = Executors.newSingleThreadExecutor();
    private static java.lang.ref.WeakReference<Runnable> onArt = new java.lang.ref.WeakReference<>(null);

    /** Runs (on the lookup's thread) when looked-up pictures arrive; held weakly, so a closed screen isn't kept. */
    static void onArt(Runnable r) { onArt = new java.lang.ref.WeakReference<>(r); }

    /** The picture found for this title so far ("" for none), or null when it hasn't been looked up yet. */
    private static String knownArt(String title) {
        String name = cleanTitle(title);
        if (name.isEmpty() || !Tmdb.hasKey()) return "";
        synchronized (ART) { return ART.get(name); }
    }

    private static void lookUpArt(List<String> titles) {
        List<String> todo = new ArrayList<>();
        synchronized (ART) {
            for (String t : titles) {
                String name = cleanTitle(t);
                if (!name.isEmpty() && !ART.containsKey(name) && LOOKING.add(name)) todo.add(name);
            }
        }
        if (todo.isEmpty()) return;
        ART_POOL.execute(() -> {
            boolean found = false;
            for (String name : todo) {
                String art = artFor(name);
                synchronized (ART) { LOOKING.remove(name); }
                found |= art != null && !art.isEmpty();
            }
            Runnable r = onArt.get();
            if (found && r != null) r.run();
        });
    }

    /**
     * A picture for a card its app left without one (a file player's "Show - S01E54 (BD 1080p).mkv"): the title,
     * cleaned of episode tags, brackets and the file's extension, looked up on TMDB; its backdrop, else its poster.
     * "" when there's no match, null when offline (looked up again later). Off the main thread only.
     */
    static String artFor(String title) {
        String name = cleanTitle(title);
        if (name.isEmpty() || !Tmdb.hasKey()) return "";
        synchronized (ART) {
            String known = ART.get(name);
            if (known != null) return known;
            Long failedAt = FAILED.get(name);
            if (failedAt != null && System.currentTimeMillis() - failedAt < 10 * 60_000L) return null;
        }
        String art = "";
        try {
            org.json.JSONArray results = Tmdb.get("/search/multi?query=" + java.net.URLEncoder.encode(name, "UTF-8")
                    + "&include_adult=false").optJSONArray("results");
            // A title whose name is exactly this one first; any match only for a longer, telling name: "DC" would
            // otherwise take whatever TMDB ranks first for those two letters.
            String wanted = norm(name);
            boolean shortName = wanted.length() <= 3;
            for (int pass = 0; pass < (shortName ? 1 : 2) && art.isEmpty(); pass++) {
                for (int i = 0; results != null && i < results.length() && art.isEmpty(); i++) {
                    org.json.JSONObject r = results.optJSONObject(i);
                    if (r == null || "person".equals(r.optString("media_type"))) continue;
                    String found = norm(r.optString("title", r.optString("name", "")));
                    String original = norm(r.optString("original_title", r.optString("original_name", "")));
                    if (pass == 0 && !wanted.equals(found) && !wanted.equals(original)) continue;
                    String backdrop = r.optString("backdrop_path", ""), poster = r.optString("poster_path", "");
                    String clean = textless(r.optString("media_type", "movie"), r.optInt("id"));
                    if (!clean.isEmpty()) art = clean;
                    else if (!backdrop.isEmpty() && !"null".equals(backdrop)) art = "https://image.tmdb.org/t/p/w780" + backdrop;
                    else if (!poster.isEmpty() && !"null".equals(poster)) art = Tmdb.POSTER + poster;
                }
            }
        } catch (Exception e) {
            AppLog.w("art for " + name + ": " + e.getMessage());
            synchronized (ART) { FAILED.put(name, System.currentTimeMillis()); }
            return null;   // offline: looked up again, after a pause
        }
        synchronized (ART) { ART.put(name, art); }
        return art;
    }

    /** A title's backdrop with no text on it (TMDB keeps those apart: no language), else "". */
    private static String textless(String kind, int id) {
        if (id <= 0 || !(kind.equals("movie") || kind.equals("tv"))) return "";
        try {
            org.json.JSONArray backdrops = Tmdb.get("/" + kind + "/" + id + "/images?include_image_language=null").optJSONArray("backdrops");
            org.json.JSONObject best = backdrops == null ? null : backdrops.optJSONObject(0);
            String path = best == null ? "" : best.optString("file_path", "");
            return path.isEmpty() ? "" : "https://image.tmdb.org/t/p/w780" + path;
        } catch (Exception e) {
            return "";
        }
    }

    /** "Fullmetal Alchemist Brotherhood - S01E54 (BD 1080p)" -> "Fullmetal Alchemist Brotherhood". */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title.replaceAll("(?i)\\.(mkv|mp4|avi|mov|webm|m4v|ts)$", "");
        t = t.replaceAll("[\\[(].*?([\\])]|$)", " ");                    // (BD 1080p), [Group]
        t = t.replaceAll("(?i)\\b(S\\d{1,2}\\s*E\\d{1,3}|\\d{1,2}x\\d{2,3}|Season\\s*\\d+|Episode\\s*\\d+)\\b.*$", "");
        t = t.replaceAll("[._]+", " ").replaceAll("\\s+-\\s*$|\\s*[-·:|]+\\s*$", "");
        return t.replaceAll("\\s+", " ").trim();
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
                if (card != null && card.pkg.contains("netflix") && loggedNetflix < 6) {
                    loggedNetflix++;
                    AppLog.w("netflix channel: \"" + card.title + "\" type " + num(q, "type", -1) + ", watch next type "
                            + num(q, "watch_next_type", -1) + ", position " + num(q, "last_playback_position_millis", -1)
                            + ", duration " + num(q, "duration_millis", -1) + ", engaged " + num(q, "last_engagement_time_utc_millis", -1)
                            + ", interaction " + num(q, "interaction_type", -1) + "/" + num(q, "interaction_count", -1));
                }
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
        Prefs.noteLaunch(card.key);   // this title, just now: first in Continue watching
        Prefs.noteLaunch(card.pkg);
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
        card.addView(placeholder(a, k), new FrameLayout.LayoutParams(w, h));   // under the picture, seen until (or if not) it loads
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(w, h));
        Bitmap from = c.key != null && c.key.startsWith("wn:") ? BADGES.get(c.pkg) : null;
        if (from != null) {   // Continue watching: which app it's in, as its icon in the corner
            ImageView badge = new ImageView(a);
            badge.setImageBitmap(from);
            badge.setClipToOutline(true);
            badge.setBackground(TvUi.round(0x66000000, 8 * k));
            int pad = Math.round(1.5f * k);
            badge.setPadding(pad, pad, pad, pad);
            int bs = Math.round(28 * k);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs, Gravity.TOP | Gravity.START);
            blp.leftMargin = blp.topMargin = Math.round(10 * k);
            card.addView(badge, blp);
        }
        load(img, c.image, w, h, c.title);
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
        if (c.key != null && c.key.startsWith("wn:")) card.setOnLongClickListener(v -> {   // hold OK: take it off the row
            android.widget.PopupMenu menu = new android.widget.PopupMenu(a, v, Gravity.END);
            menu.getMenu().add(Ui.tr("Remover de Continuar assistindo", "Remove from Continue watching"));
            menu.getMenu().add(Ui.tr("Abrir", "Open"));
            menu.setOnMenuItemClickListener(item -> {
                if (item.getOrder() == 0 && item.getTitle().toString().startsWith(Ui.tr("Remover", "Remove"))) {
                    Prefs.hideWatchNext(c.key + "@" + c.when);
                    ViewGroup row = (ViewGroup) cell.getParent();
                    if (row != null) {
                        int at = row.indexOfChild(cell);
                        row.removeView(cell);
                        View next = row.getChildAt(Math.min(at, row.getChildCount() - 1));   // the focus moves to its neighbour
                        if (next != null) next.requestFocus();
                    }
                } else {
                    open.open(c);
                }
                return true;
            });
            menu.show();
            return true;
        });
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

        @Override public View focusSearch(View focused, int direction) {
            return TvUi.rowEnd(this, focused, direction, super.focusSearch(focused, direction));
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

    /** A card with no picture: a soft play badge on a dark gradient (the name is under the card already). */
    private static View placeholder(Context c, float k) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF2A2F3A, 0xFF171A21}));
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(TvUi.round(0x1FFFFFFF, 40 * k));
        TvUi.Icon play = new TvUi.Icon(c, "play", 40 * k);
        play.color(0xB3FFFFFF);
        badge.addView(play, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int size = Math.round(80 * k);
        box.addView(badge, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return box;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    /** A file player's title ("Show - S01E54 (BD 1080p).mkv"): its app's own picture is usually a placeholder. */
    private static final java.util.regex.Pattern FILE_LIKE =
            java.util.regex.Pattern.compile("(?i)S\\d{1,2}E\\d{1,3}|\\.(mkv|mp4|avi|mov|webm|m4v)\\b|[\\[(]");

    private static void load(ImageView img, String uri, int w, int h, String title) {
        boolean fileLike = title != null && FILE_LIKE.matcher(title).find();
        if (uri == null || uri.isEmpty()) return;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            Images.load(img, uri, w, h);
            return;
        }
        Uri u = Uri.parse(uri);
        img.setTag(u);   // not a String: Explore's release/restore of pictures leaves it alone
        Bitmap hit = CACHE.get(uri);
        if (hit != null && !fileLike) {
            img.setImageBitmap(hit);
            return;
        }
        ContentResolver cr = img.getContext().getContentResolver();
        POOL.execute(() -> {
            // A file player's card: TMDB's picture of the show first; the app's own picture only when there's none.
            String art = fileLike ? artFor(title) : null;
            if (art != null && !art.isEmpty()) {
                MAIN.post(() -> { if (u.equals(img.getTag())) Images.load(img, art, w, h); });
                return;
            }
            Bitmap b = hit != null ? hit : decode(cr, u, w, h);
            if (b == null) {   // the app's picture won't open: TMDB's, if it has one
                String found = fileLike ? art : artFor(title);
                if (found != null && !found.isEmpty()) MAIN.post(() -> { if (u.equals(img.getTag())) Images.load(img, found, w, h); });
                return;
            }
            CACHE.put(uri, b);
            MAIN.post(() -> { if (u.equals(img.getTag())) img.setImageBitmap(b); });
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
