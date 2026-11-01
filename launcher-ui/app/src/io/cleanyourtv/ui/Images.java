package io.cleanyourtv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters, backdrops and logos for Explore and the title page: loaded in the background, kept in memory and on disk
 * (the app's cache folder, up to DISK_MAX, each file for TTL), so a row seen before shows at once without the network.
 */
final class Images {
    private Images() {}

    private static final long TTL = 7L * 24 * 3600_000, DISK_MAX = 160L * 1024 * 1024;
    /** An eighth of what the process may use, at most 24 MB: the TV has little memory and the disk holds the rest. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<ImageView>> WAITING = new HashMap<>();
    private static final Object DISK_LOCK = new Object();
    private static volatile File dir;

    /** The disk cache's folder; old files are dropped once per start. */
    static synchronized void init(Context c) {
        if (dir != null) return;
        File folder = new File(c.getApplicationContext().getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        dir = folder;
        POOL.execute(Images::prune);
    }

    /** Shows the image in the view once it arrives; a view reused for another image in the meantime is left alone. */
    /** Into a view with a fixed size (its layout params), the picture is decoded at that size; else as it comes. */
    static void load(ImageView view, String url) {
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        load(view, url, lp != null && lp.width > 0 ? lp.width : 0, lp != null && lp.height > 0 ? lp.height : 0);
    }

    /**
     * Decoded just big enough to fill w x h (cropped to fill, as the cards are), not at the file's size: a 1280x720
     * channel picture in a 384x216 card takes a ninth of the memory. 0 x 0: as it comes.
     */
    static void load(ImageView view, String url, int w, int h) {
        MAIN.removeCallbacks(TRIM_LATER);   // a page with pictures is back: keep the cache
        if (dir == null) init(view.getContext());
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        String key = w > 0 && h > 0 ? url + "#" + w + "x" + h : url;
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        synchronized (WAITING) {
            List<ImageView> views = WAITING.get(key);
            if (views != null) {   // already on its way: this view gets it too
                views.add(view);
                return;
            }
            views = new ArrayList<>();
            views.add(view);
            WAITING.put(key, views);
        }
        POOL.execute(() -> {
            Bitmap b = fetch(url, w, h);
            if (b != null) CACHE.put(key, b);
            List<ImageView> views;
            synchronized (WAITING) { views = WAITING.remove(key); }
            if (b == null || views == null) return;
            MAIN.post(() -> {
                for (ImageView v : views) if (url.equals(v.getTag())) v.setImageBitmap(b);
            });
        });
    }

    /** From the disk cache when fresh, otherwise downloaded and saved. */
    static Bitmap fetch(String url) {
        return fetch(url, 0, 0);
    }

    static Bitmap fetch(String url, int w, int h) {
        File f = dir == null ? null : new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        if (f != null && f.isFile() && System.currentTimeMillis() - f.lastModified() < TTL) {
            Bitmap b = decode(f, url, w, h);
            if (b != null) return b;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                // We never request ranges; even a decodable partial body must not replace the cache.
                if (c.getResponseCode() == HttpURLConnection.HTTP_PARTIAL || c.getHeaderField("Content-Range") != null)
                    throw new IOException("Partial image response");
                bytes = Tmdb.readBytes(in);
                long length = c.getContentLengthLong();
                if (length >= 0 && bytes.length != length) throw new IOException("Incomplete image download");
            } finally {
                c.disconnect();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options(url, bounds, w, h));
            if (b == null) return f != null && f.isFile() ? decode(f, url, w, h) : null;
            if (f != null) store(f, bytes);
            return b;
        } catch (Exception e) {
            return f != null && f.isFile() ? decode(f, url, w, h) : null;   // offline: an old copy beats nothing
        }
    }

    /** Share prune's disk lock, not init's monitor: warm foreground init must not wait for disk IO. */
    private static void store(File f, byte[] bytes) {
        synchronized (DISK_LOCK) {
            File tmp = null;
            try {
                tmp = File.createTempFile("img-", ".tmp", f.getParentFile());
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // The decoded download is still usable in memory. No non-atomic delete/replace fallback.
            } finally {
                //noinspection ResultOfMethodCallIgnored
                try { if (tmp != null) tmp.delete(); } catch (SecurityException ignored) {}
            }
        }
    }

    private static Bitmap decode(File f, String url, int w, int h) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), options(url, bounds, w, h));
    }

    /**
     * Photos in half the memory; PNGs (title logos) keep their transparency. With a size: halved while it still covers
     * w x h, then scaled to just cover it.
     */
    private static BitmapFactory.Options options(String url, BitmapFactory.Options bounds, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = url.endsWith(".png") ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        int sw = bounds.outWidth, sh = bounds.outHeight;
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return o;
        float cover = Math.max((float) w / sw, (float) h / sh);   // the scale at which the picture just fills the view
        if (cover >= 1f) return o;
        int needW = (int) Math.ceil(sw * cover), needH = (int) Math.ceil(sh * cover);   // >= w and >= h
        int sample = 1;
        while (sw / (sample * 2) >= needW && sh / (sample * 2) >= needH) sample *= 2;
        o.inSampleSize = sample;
        int after = sw / sample, target = needW;
        if (after > target * 5 / 4) {   // still well over: scale the rest of the way while decoding
            o.inScaled = true;
            o.inDensity = after;
            o.inTargetDensity = target;
        }
        return o;
    }

    /** Drops files well past their TTL, then the oldest until the folder fits in DISK_MAX. */
    private static void prune() {
        synchronized (DISK_LOCK) {
            File[] files = dir.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis(), total = 0;
            List<File> kept = new ArrayList<>();
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                if (now - f.lastModified() > 2 * TTL) f.delete();
                else {
                    kept.add(f);
                    total += f.length();
                }
            }
            if (total <= DISK_MAX) return;
            File[] byAge = kept.toArray(new File[0]);
            Arrays.sort(byAge, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : byAge) {
                if (total <= DISK_MAX * 3 / 4) break;
                total -= f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    static void trim() { CACHE.evictAll(); }

    private static final Runnable TRIM_LATER = () -> {
        trim();
        Tmdb.trimMemory();
    };

    /**
     * Back on Home, which shares Explore's process (so no UI_HIDDEN trim comes): Explore's posters and TMDB answers go
     * after a minute there, unless a page loads pictures again first. Both stay on disk for the next visit.
     */
    static void trimSoon() {
        MAIN.removeCallbacks(TRIM_LATER);
        MAIN.postDelayed(TRIM_LATER, 60_000L);
    }
}
