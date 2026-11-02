package io.cleanyourtv.ui;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * What video apps say they're playing, for Continue watching: some apps (SonyLIV) never update their own entry, so
 * the row can't tell what was watched in them. Android hands "now playing" (media sessions) only to a notification
 * listener, which is why this is one; notifications themselves are never read. Off until the person grants it
 * Notification access. Music apps are left out.
 */
public final class NowPlaying extends NotificationListenerService {
    private MediaSessionManager sessions;
    private String lastNoted;
    private long lastNotedAt;
    private final List<MediaController> watched = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::follow;

    @Override public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, NowPlaying.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me);
            List<MediaController> now = sessions.getActiveSessions(me);
            AppLog.w("now playing: listening, " + (now == null ? 0 : now.size()) + " sessions");
            follow(now);
        } catch (SecurityException e) {
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
    }

    private void follow(List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (!video(c.getPackageName())) continue;
            c.registerCallback(callback);
            watched.add(c);
            note(c);
        }
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { noteAll(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { noteAll(); }
    };

    private void noteAll() {
        for (MediaController c : watched) note(c);
    }

    /** Playing (or just paused) with a title: remembered as watched now, with how far in. */
    private void note(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (s == null || m == null) return;
        int st = s.getState();
        if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_PAUSED) return;
        CharSequence t = m.getDescription() == null ? null : m.getDescription().getTitle();
        String title = t == null ? "" : t.toString().trim();
        if (title.isEmpty() || title.equalsIgnoreCase(appName(c.getPackageName()))) return;   // "PrimeVideo": no title yet
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration > 0 && duration < 3 * 60_000L) return;   // an ad or a preview before the episode, not what's watched
        // The episode, where the app gives one: the description's subtitle, else the metadata's display subtitle.
        CharSequence sub = m.getDescription() == null ? null : m.getDescription().getSubtitle();
        String episode = sub == null ? m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) : sub.toString();
        // Apps report every few seconds while playing: the same title is saved again only every 20 s.
        String which = c.getPackageName() + "|" + title + "|" + episode;
        long now = android.os.SystemClock.uptimeMillis();
        if (which.equals(lastNoted) && now - lastNotedAt < 20_000) return;
        lastNoted = which;
        lastNotedAt = now;
        Prefs.notePlayed(c.getPackageName(), title, episode == null ? "" : episode.trim(), art(m, c.getPackageName() + title),
                s.getPosition(), duration);
        AppLog.w("now playing: " + c.getPackageName() + " \"" + title + "\"");
    }

    /**
     * The poster the app shows in the TV's playback controls: its web address, or (SonyLIV) the picture itself, kept as
     * a small JPEG, once per title. "" when the app gives none.
     */
    private String art(MediaMetadata m, String id) {
        String link = artLink(m);
        if (!link.isEmpty()) { AppLog.w("now playing: art from " + link); return link; }
        android.graphics.Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null && m.getDescription() != null) b = m.getDescription().getIconBitmap();
        if (b == null || b.getWidth() < 64) {
            AppLog.w("now playing: no art from the app" + (b == null ? "" : " (too small: " + b.getWidth() + " px)"));
            return "";
        }
        AppLog.w("now playing: art as a picture, " + b.getWidth() + "x" + b.getHeight());
        java.io.File dir = new java.io.File(getFilesDir(), "played");
        java.io.File f = new java.io.File(dir, Integer.toHexString(id.hashCode()) + "-w.jpg");
        if (!f.isFile()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                android.graphics.Bitmap small = wide(b);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
                }
                prune(dir);
            } catch (Exception e) {
                AppLog.w("now playing: art: " + e.getMessage());
                return "";
            }
        }
        return android.net.Uri.fromFile(f).toString();
    }

    /**
     * The card is wide (16:9): a wide picture is just scaled down; a tall poster (SonyLIV's) would be cut to a strip
     * across the middle, so it's set whole in the centre over a darkened, blurred copy of itself, Apple TV-like.
     */
    static android.graphics.Bitmap wide(android.graphics.Bitmap b) {
        int w = 480, h = 270;
        if (b.getWidth() >= b.getHeight() * 1.3f) {
            float scale = Math.min(1f, (float) w / b.getWidth());
            return scale < 1f ? android.graphics.Bitmap.createScaledBitmap(b, w, Math.max(1, Math.round(b.getHeight() * scale)), true) : b;
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint smooth = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        // the blur: down to a few pixels and back up, twice, which is soft and nearly free
        android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(b, 12, Math.max(1, Math.round(12f * b.getHeight() / b.getWidth())), true);
        android.graphics.Bitmap soft = android.graphics.Bitmap.createScaledBitmap(tiny, 48, Math.max(1, Math.round(48f * b.getHeight() / b.getWidth())), true);
        float cover = Math.max((float) w / soft.getWidth(), (float) h / soft.getHeight());
        float sw = soft.getWidth() * cover, sh = soft.getHeight() * cover;
        c.drawBitmap(soft, null, new android.graphics.RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2), smooth);
        c.drawColor(0x8C000000);   // darker, so the poster stands out
        float fit = (float) h / b.getHeight();
        float pw = b.getWidth() * fit;
        c.drawBitmap(b, null, new android.graphics.RectF((w - pw) / 2, 0, (w + pw) / 2, h), smooth);
        return out;
    }

    /**
     * A poster saved before pictures were made wide (its file name has no "-w"): made wide now, once. The new file's
     * address, or the same one when it can't be read. Off the main thread.
     */
    static String widen(android.content.Context c, String link) {
        if (link != null && (link.startsWith("https://") || link.startsWith("http://"))) return widenWeb(c, link);
        if (link == null || !link.startsWith("file:") || link.endsWith("-w.jpg")) return link;
        java.io.File old = new java.io.File(android.net.Uri.parse(link).getPath());
        java.io.File f = new java.io.File(old.getParentFile(), old.getName().replace(".jpg", "-w.jpg"));
        if (!f.isFile()) {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(old.getPath());
            if (b == null) return link;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                wide(b).compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            } catch (Exception e) {
                return link;
            }
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        return android.net.Uri.fromFile(f).toString();
    }

    /**
     * A poster the app gave as a web address (SonyLIV's are tall): fetched once, and a tall one kept as the wide framed
     * version, since the card would cut it to a strip. A wide one is used as it is. Off the main thread.
     */
    private static String widenWeb(android.content.Context c, String link) {
        java.io.File dir = new java.io.File(c.getFilesDir(), "played");
        java.io.File f = new java.io.File(dir, "web-" + Integer.toHexString(link.hashCode()) + "-w.jpg");
        java.io.File wideOk = new java.io.File(dir, "web-" + Integer.toHexString(link.hashCode()) + ".wide");
        if (f.isFile()) return android.net.Uri.fromFile(f).toString();
        if (wideOk.isFile()) return link;   // already known to be wide
        android.graphics.Bitmap b = Images.fetch(link);
        if (b == null) return link;
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (b.getWidth() >= b.getHeight() * 1.3f) {
                //noinspection ResultOfMethodCallIgnored
                wideOk.createNewFile();
                return link;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                wide(b).compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            }
            prune(dir);
            return android.net.Uri.fromFile(f).toString();
        } catch (Exception e) {
            return link;
        }
    }

    /** At most 40 kept pictures, the newest. */
    private static void prune(java.io.File dir) {
        java.io.File[] all = dir.listFiles();
        if (all == null || all.length <= 40) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 40; i < all.length; i++) //noinspection ResultOfMethodCallIgnored
            all[i].delete();
    }

    /** The poster's web address, when the app gives one. */
    private static String artLink(MediaMetadata m) {
        java.util.List<String> links = new java.util.ArrayList<>();
        if (m.getDescription() != null && m.getDescription().getIconUri() != null) links.add(m.getDescription().getIconUri().toString());
        for (String key : new String[] {MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI}) {
            String v = m.getString(key);
            if (v != null) links.add(v);
        }
        for (String link : links) if (link.startsWith("https://") || link.startsWith("http://")) return link;
        if (!links.isEmpty()) AppLog.w("now playing: art isn't a web address (" + links.get(0).split(":")[0] + ")");
        return "";
    }

    /** A video app: not this launcher, not Bluetooth, not one that calls itself audio (Spotify, YouTube Music). */
    private boolean video(String pkg) {
        if (pkg == null || pkg.equals(getPackageName()) || pkg.startsWith("com.android.")) return false;
        if (pkg.contains("music") || pkg.contains("spotify") || pkg.contains("saavn") || pkg.contains("gaana")) return false;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.category != ApplicationInfo.CATEGORY_AUDIO;
        } catch (Exception e) {
            return false;
        }
    }

    private String appName(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))).replace(" ", "");
        } catch (Exception e) {
            return "";
        }
    }
}
