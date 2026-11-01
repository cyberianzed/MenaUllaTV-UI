package io.cleanyourtv.ui;

/**
 * The Explore-style home screen: Explore's banner and rows as the home screen, with every app on a glass shelf under
 * the banner, the shortcuts (All apps, Wi-Fi, Home Settings, TV Settings, inputs) under it, then Continue Watching,
 * Malayalam picks, MovieShelf's new films and the apps' own channels before Explore's rows. Make it Home with
 * `cmd package set-home-activity --user 0 io.cleanyourtv.ui/.ExploreHomeActivity`; `.HomeActivity` is the classic one.
 */
public class ExploreHomeActivity extends ExploreActivity {
    @Override boolean isHome() {
        return true;
    }
}
