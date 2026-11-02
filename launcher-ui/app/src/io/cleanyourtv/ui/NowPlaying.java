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
        Prefs.notePlayed(c.getPackageName(), title, episode == null ? "" : episode.trim(), art(m), s.getPosition(), duration);
        AppLog.w("now playing: " + c.getPackageName() + " \"" + title + "\"");
    }

    /** The poster the app shows in the TV's playback controls, when it gives it as a web address. */
    private static String art(MediaMetadata m) {
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
