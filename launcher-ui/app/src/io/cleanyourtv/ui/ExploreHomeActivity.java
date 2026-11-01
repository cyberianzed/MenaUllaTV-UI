package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * The Explore-style home screen: Explore's banner and rows as the home screen, with every app on a glass shelf under
 * the banner, the shortcuts (All apps, Wi-Fi, Home Settings, TV Settings, inputs) under it, then Continue Watching,
 * Malayalam picks, MovieShelf's new films and the apps' own channels before Explore's rows. config.json's
 * "home_style": "explore" picks it, "classic" goes back to HomeActivity.
 */
public class ExploreHomeActivity extends ExploreActivity {
    @Override boolean isHome() {
        return true;
    }

    /**
     * Leaves only `style`'s home screen answering Home (the shell can't switch our components off on Android 14, the
     * app can), and hands over to it when `from` is the other one. True when `from` handed over and is finishing.
     */
    static boolean switchTo(Activity from, String style) {
        boolean explore = "explore".equals(style);
        PackageManager pm = from.getPackageManager();
        ComponentName mine = new ComponentName(from, ExploreHomeActivity.class), classic = new ComponentName(from, HomeActivity.class);
        try {
            set(pm, explore ? mine : classic, true);
            set(pm, explore ? classic : mine, false);
        } catch (RuntimeException e) {
            AppLog.w("home style: " + e.getMessage());
            return false;
        }
        if (explore == (from instanceof ExploreHomeActivity)) return false;
        from.startActivity(new Intent(from, explore ? ExploreHomeActivity.class : HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        from.finish();
        return true;
    }

    private static void set(PackageManager pm, ComponentName c, boolean on) {
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(c) != want) pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP);
    }
}
