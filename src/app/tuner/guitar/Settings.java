package app.tuner.guitar;

import android.content.SharedPreferences;

final class Settings {
    static final int A4_MIN = 415, A4_MAX = 466;
    static final float[] TOLERANCE = {1f, 2f, 3f, 5f};

    String theme = "Aurum";
    String tuning = Tuning.ALL[0].name;
    int a4 = 440;
    int tolIdx = 1;
    boolean fast;
    boolean flats;
    boolean haptics = true;
    boolean keepOn = true;
    boolean guideAuto = true;    // guided page: switch strings automatically

    void load(SharedPreferences p) {
        theme = p.getString("theme", theme);
        tuning = Tuning.ALL[Tuning.indexOf(p.getString("tuning", tuning))].name;
        a4 = Math.max(A4_MIN, Math.min(A4_MAX, p.getInt("a4", a4)));
        int t = p.getInt("tol", tolIdx);
        tolIdx = t < 0 || t >= TOLERANCE.length ? 1 : t;
        fast = p.getBoolean("fast", fast);
        flats = p.getBoolean("flats", flats);
        haptics = p.getBoolean("haptics", haptics);
        keepOn = p.getBoolean("keepOn", keepOn);
        guideAuto = p.getBoolean("guideAuto", guideAuto);
    }

    void save(SharedPreferences p) {
        p.edit()
                .putString("theme", theme)
                .putString("tuning", tuning)
                .putInt("a4", a4)
                .putInt("tol", tolIdx)
                .putBoolean("fast", fast)
                .putBoolean("flats", flats)
                .putBoolean("haptics", haptics)
                .putBoolean("keepOn", keepOn)
                .putBoolean("guideAuto", guideAuto)
                .apply();
    }
}
