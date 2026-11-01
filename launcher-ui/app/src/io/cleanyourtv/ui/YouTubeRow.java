package io.cleanyourtv.ui;

import android.content.Context;
import android.content.pm.PackageManager;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A row of YouTube videos worth watching: the latest uploads of hand-picked channels (no Shorts), read from YouTube's
 * public per-channel feeds (no key), shuffled. About a fifth Malayalam; the rest new songs, tech, trailers and podcasts.
 * Each opens in TizenTube when it's on the TV, else in whatever plays youtube.com links.
 */
final class YouTubeRow {
    private YouTubeRow() {}

    static final String TIZENTUBE = "io.gh.reisxd.tizentube.cobalt";

    /** {channel id, kind}: kind "ml" is the Malayalam fifth. */
    private static final String[][] CHANNELS = {
        {"UChRi1dpwnsZcIo1LSwzY0Iw", "ml"},     // Muzik247
        {"UCoRF8GByEjmM_yHwUGIDGyQ", "ml"},     // Saregama Malayalam
        {"UCUSFb3Dwt8AdfDIxdHTlNuA", "ml"},     // Mallu Traveler
        {"UCq6f9bBWaWTHl0FhH167RgA", "ml"},     // Goodwill Entertainments
        {"UC56gTxNs4f9xZ7Pa2i5xNzg", "songs"},  // Sony Music India
        {"UC_A7K2dXFsTMAciGmnNxy-Q", "songs"},  // Saregama Music
        {"UCfHRZWNY3vv3vSIpIofiXjw", "songs"},  // Coke Studio Bharat
        {"UCBJycsmduvYEL83R_U4JriQ", "tech"},   // Marques Brownlee
        {"UCMiJRAwDNSNzuYeN2uWa0pA", "tech"},   // Mrwhosetheboss
        {"UCVYamHliCI9rw1tHR1xbkfw", "tech"},   // Dave2D
        {"UCHnyfMqiRRG1u-2MsSQLbXA", "tech"},   // Veritasium
        {"UCsXVk37bltHxD1rDPwtNM8Q", "tech"},   // Kurzgesagt
        {"UCZSNzBgFub_WWil6TOTYwAg", "trailers"},  // Netflix India
        {"UC4zWG9LccdWGUlF77LZ8toA", "trailers"},  // Prime Video India
        {"UCi8e0iOVk1fEOogdfu4YgfA", "trailers"},  // Rotten Tomatoes Trailers
        {"UC2D2CMWXMOVWx7giW1n3LIg", "podcasts"},  // Huberman Lab
        {"UCGq-a57w-aPwyi3pW7XLiHw", "podcasts"},  // The Diary Of A CEO
        {"UCzwCEE_PchiBULMnAJqhGVg", "podcasts"},  // Raj Shamani
    };
    private static final int SIZE = 20, MALAYALAM = 4;
    private static final long MAX_AGE = 45L * 24 * 3600 * 1000, KEEP = 3600L * 1000;

    private static List<HomeRows.Card> cached;
    private static long cachedAt;

    static synchronized List<HomeRows.Card> videos(Context c) {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < KEEP) return cached;
        boolean tizen = installed(c, TIZENTUBE);
        List<List<HomeRows.Card>> feeds = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<HomeRows.Card>>> jobs = new ArrayList<>();
            for (String[] ch : CHANNELS) jobs.add(pool.submit(() -> feed(ch[0], tizen, now)));
            for (Future<List<HomeRows.Card>> f : jobs) {
                try {
                    feeds.add(f.get());
                } catch (Exception e) {
                    feeds.add(new ArrayList<>());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // A fifth Malayalam; the rest taken a channel at a time, so no one channel fills the row.
        List<List<HomeRows.Card>> ml = new ArrayList<>(), other = new ArrayList<>();
        for (int i = 0; i < CHANNELS.length; i++) {
            Collections.shuffle(feeds.get(i));
            ("ml".equals(CHANNELS[i][1]) ? ml : other).add(feeds.get(i));
        }
        List<HomeRows.Card> out = take(ml, MALAYALAM);
        out.addAll(take(other, SIZE - out.size()));
        Collections.shuffle(out);
        if (!out.isEmpty()) {
            cached = out;
            cachedAt = now;
        }
        return out;
    }

    private static List<HomeRows.Card> take(List<List<HomeRows.Card>> feeds, int n) {
        List<HomeRows.Card> out = new ArrayList<>();
        Collections.shuffle(feeds);
        for (int round = 0; out.size() < n; round++) {
            boolean any = false;
            for (List<HomeRows.Card> f : feeds) {
                if (round < f.size() && out.size() < n) {
                    out.add(f.get(round));
                    any = true;
                }
            }
            if (!any) break;
        }
        return out;
    }

    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);

    /** One channel's recent videos (Shorts and anything older than MAX_AGE left out). */
    private static List<HomeRows.Card> feed(String channel, boolean tizen, long now) {
        List<HomeRows.Card> out = new ArrayList<>();
        String xml;
        try {
            xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=" + channel);
        } catch (Exception e) {
            AppLog.w("youtube feed " + channel + ": " + e.getMessage());
            return out;
        }
        String author = tag(xml, "name");
        Matcher m = ENTRY.matcher(xml);
        while (m.find()) {
            String e = m.group(1), id = tag(e, "yt:videoId");
            if (id.isEmpty() || e.contains("/shorts/")) continue;
            long when = time(tag(e, "published"));
            if (when > 0 && now - when > MAX_AGE) continue;
            HomeRows.Card card = new HomeRows.Card();
            card.key = "yt:" + id;
            card.title = unescape(tag(e, "title"));
            card.sub = unescape(author);
            card.image = "https://i.ytimg.com/vi/" + id + "/hq720.jpg";
            card.pkg = TIZENTUBE;
            card.when = when;
            String url = "https://www.youtube.com/watch?v=" + id;
            card.intent = tizen ? "intent://www.youtube.com/watch?v=" + id + "#Intent;scheme=https;package=" + TIZENTUBE + ";end" : url;
            out.add(card);
        }
        return out;
    }

    private static String tag(String s, String name) {
        int a = s.indexOf("<" + name + ">");
        if (a < 0) return "";
        a += name.length() + 2;
        int b = s.indexOf("</" + name + ">", a);
        return b < 0 ? "" : s.substring(a, b).trim();
    }

    private static long time(String iso) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            return Tmdb.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
