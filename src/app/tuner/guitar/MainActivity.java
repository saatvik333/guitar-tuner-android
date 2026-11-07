package app.tuner.guitar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

public final class MainActivity extends Activity implements TunerView.Host, AudioInput.Sink, ToneSynth.Listener {

    private static final String MIC = Manifest.permission.RECORD_AUDIO;
    private static final int REQ_MIC = 1;

    private SharedPreferences prefs;
    private final Settings settings = new Settings();
    private TunerView view;
    private AudioInput audio;
    private final ToneSynth tone = new ToneSynth(this);
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed, askedThisLaunch, blocked;
    private boolean rationaleBefore;                // shouldShowRequestPermissionRationale at request time
    private Object backCallback;
    private boolean backRegistered, backUnavailable;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tuner", MODE_PRIVATE);
        settings.load(prefs);
        if (state != null) {
            askedThisLaunch = state.getBoolean("asked");
            rationaleBefore = state.getBoolean("rationale");
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);   // volume keys set the reference tone

        Typeface light, regular;
        try {
            light = Typeface.createFromAsset(getAssets(), "fonts/rubik_300.ttf");
            regular = Typeface.createFromAsset(getAssets(), "fonts/rubik_400.ttf");
        } catch (RuntimeException e) {
            light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            regular = Typeface.create("sans-serif", Typeface.NORMAL);
        }
        audio = new AudioInput(this);
        view = new TunerView(this, new Renderer(light, regular), settings, themes(), this);
        setContentView(view);
        setupWindow();
        onThemeApplied(view.currentTarget());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", askedThisLaunch);
        out.putBoolean("rationale", rationaleBefore);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        view.resume();
        if (hasMic()) {
            micGranted();
        } else if (!askedThisLaunch) {
            askedThisLaunch = true;
            view.setMic(TunerView.MIC_NEED);
            requestMic();
        } else {
            view.setMic(blocked ? TunerView.MIC_BLOCKED : TunerView.MIC_NEED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        view.pause();
        audio.stop();
        tone.stop();
        settings.save(prefs);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC || results.length == 0) return;     // empty: the request was cancelled
        if (results[0] == PackageManager.PERMISSION_GRANTED) {
            if (resumed) micGranted();
            return;
        }
        // The system stops showing the dialog once the user has refused it for good; it then
        // answers at once with rationale false. Rationale is also false before the first refusal
        // and after a dismissed dialog, so it means "blocked" only when it was true before this
        // request, or a refusal was recorded on an earlier launch.
        boolean after = shouldShowRequestPermissionRationale(MIC);
        boolean refusedBefore = prefs.getBoolean("micRefused", false);
        if (after && !refusedBefore) prefs.edit().putBoolean("micRefused", true).apply();
        blocked = !after && (rationaleBefore || refusedBefore);
        view.setMic(blocked ? TunerView.MIC_BLOCKED : TunerView.MIC_NEED);
    }

    private void requestMic() {
        rationaleBefore = shouldShowRequestPermissionRationale(MIC);
        requestPermissions(new String[]{MIC}, REQ_MIC);
    }

    private void micGranted() {
        blocked = false;
        if (prefs.getBoolean("micRefused", false)) prefs.edit().remove("micRefused").apply();
        view.setMic(TunerView.MIC_OK);
        if (!view.paused()) startAudio();
    }

    /** Multi-window: access may have been granted from another window while this one stayed resumed. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        int m = view.mic();
        if (hasFocus && resumed && (m == TunerView.MIC_NEED || m == TunerView.MIC_BLOCKED) && hasMic()) micGranted();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        onThemeApplied(view.currentTarget());   // Android 11 can drop bar appearance set before attach
    }

    private boolean hasMic() {
        return checkSelfPermission(MIC) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAudio() {
        audio.start();                          // no-op while a session runs
    }

    // ---- TunerView.Host ----------------------------------------------------------------------

    @Override
    public void setListening(boolean on) {
        if (on) {
            if (resumed && hasMic()) {
                if (view.mic() == TunerView.MIC_ERROR) audio.stop();     // fresh attempt
                view.setMic(TunerView.MIC_OK);
                startAudio();
            }
        } else {
            audio.stop();
        }
    }

    @Override
    public void onMicAction(int state) {
        if (state == TunerView.MIC_BLOCKED) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (RuntimeException ignored) {
            }
        } else if (state == TunerView.MIC_NEED) {
            requestMic();
        } else if (state == TunerView.MIC_ERROR) {
            view.setMic(TunerView.MIC_OK);
            audio.stop();
            if (resumed && hasMic() && !view.paused()) startAudio();
        }
    }

    @Override
    public void setPitchFloor(double hz) {
        audio.setMinHz(hz);
    }

    @Override
    public void playTone(double hz) {
        tone.play(hz);
    }

    @Override
    public void stopTone() {
        tone.stop();
    }

    @Override
    public void onSettingsChanged() {
        settings.save(prefs);
    }

    // ---- AudioInput.Sink ----------------------------------------------------------------------

    @Override
    public void onCaptureStart(int session) {
        view.onAudioStart(session);
    }

    /** Capture thread. */
    @Override
    public void onFrame(PitchEngine e, int session) {
        view.offer(e, session);
    }

    /** Capture thread. */
    @Override
    public void onFailure(final int session) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!audio.isCurrent(session)) return;      // a session that has since ended
                audio.stop();
                if (resumed) view.setMic(hasMic() ? TunerView.MIC_ERROR : TunerView.MIC_NEED);
            }
        });
    }

    /** Tone thread. */
    @Override
    public void onToneFailed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!tone.playing()) view.onToneFailed();  // unless a newer tone is already playing
            }
        });
    }

    // ---- back --------------------------------------------------------------------------------

    /** Android 15 and below: legacy back dispatch. */
    @Override
    public void onBackPressed() {
        if (!view.handleBack()) super.onBackPressed();
    }

    /**
     * Android 16+ with targetSdk 36 no longer calls onBackPressed. A callback is registered only
     * while the view has something to close (settings page, reference tone), so at the top level
     * the system keeps its predictive back-to-home animation. OnBackInvokedCallback (API 33) is not
     * in the API 23 stubs this compiles against, hence the dynamic proxy.
     */
    @Override
    public void setBackIntercept(boolean on) {
        if (Build.VERSION.SDK_INT < 36 || on == backRegistered || backUnavailable) return;
        try {
            Class<?> cbType = Class.forName("android.window.OnBackInvokedCallback");
            Class<?> dispType = Class.forName("android.window.OnBackInvokedDispatcher");
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
            if (backCallback == null) {
                backCallback = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method m, Object[] args) {
                                switch (m.getName()) {
                                    case "onBackInvoked":
                                        if (!view.handleBack()) setBackIntercept(false);
                                        return null;
                                    case "equals":
                                        return proxy == args[0];
                                    case "hashCode":
                                        return System.identityHashCode(proxy);
                                    case "toString":
                                        return "TunerBackCallback";
                                    default:
                                        return null;
                                }
                            }
                        });
            }
            if (on) {
                dispType.getMethod("registerOnBackInvokedCallback", int.class, cbType)
                        .invoke(dispatcher, 0 /* PRIORITY_DEFAULT */, backCallback);
            } else {
                dispType.getMethod("unregisterOnBackInvokedCallback", cbType).invoke(dispatcher, backCallback);
            }
            backRegistered = on;
        } catch (Exception e) {
            backUnavailable = true;
        }
    }

    // ---- window ------------------------------------------------------------------------------

    @Override
    public void onThemeApplied(Palette p) {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(p.bg));
        boolean lightBars = !p.dark;
        if (Build.VERSION.SDK_INT < 26) {
            // No dark navigation icons before 8.0: give the white ones a scrim on light themes.
            w.setNavigationBarColor(lightBars ? 0x66000000 : 0);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Object ctl = Window.class.getMethod("getInsetsController").invoke(w);
                if (ctl != null) {
                    int mask = 8 | 16;   // APPEARANCE_LIGHT_STATUS_BARS | APPEARANCE_LIGHT_NAVIGATION_BARS
                    Method m = Class.forName("android.view.WindowInsetsController")
                            .getMethod("setSystemBarsAppearance", int.class, int.class);
                    m.invoke(ctl, lightBars ? mask : 0, mask);
                    if (Build.VERSION.SDK_INT > 30) return;
                    // Android 11 still honours the legacy flags; keep them in agreement
                }
            } catch (Exception ignored) {
                // fall through to legacy flags
            }
        }
        View decor = w.getDecorView();
        int f = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? 0x10 : 0);
        decor.setSystemUiVisibility(lightBars ? (f | light) : (f & ~light));
    }

    private void setupWindow() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT < 35) {
            w.setStatusBarColor(0);
            w.setNavigationBarColor(0);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            invoke(w, "setNavigationBarContrastEnforced", boolean.class, false);
            invoke(w, "setStatusBarContrastEnforced", boolean.class, false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WindowManager.LayoutParams lp = w.getAttributes();
                WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, 1); // SHORT_EDGES
                w.setAttributes(lp);
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            invoke(w, "setDecorFitsSystemWindows", boolean.class, false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private static void invoke(Object target, String name, Class<?> type, Object arg) {
        try {
            target.getClass().getMethod(name, type).invoke(target, arg);
        } catch (Exception ignored) {
        }
    }

    // ---- themes ------------------------------------------------------------------------------

    private Palette[] themes() {
        ArrayList<Palette> list = new ArrayList<>();
        for (Palette p : Palette.BUILT_IN) list.add(p);
        Palette dyn = wallpaperPalette();
        if (dyn != null) list.add(dyn);
        return list.toArray(new Palette[0]);
    }

    /** Material You colours on Android 12+, read by name so the app compiles against API 23. */
    private Palette wallpaperPalette() {
        if (Build.VERSION.SDK_INT < 31) return null;
        try {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            if (night) {
                return new Palette("Wallpaper", true,
                        sys("system_neutral1_900"), sys("system_neutral1_100"), sys("system_neutral2_400"),
                        sys("system_neutral2_700"), sys("system_accent1_200"), sys("system_accent1_800"));
            }
            return new Palette("Wallpaper", false,
                    sys("system_neutral1_10"), sys("system_neutral1_800"), sys("system_neutral2_600"),
                    sys("system_neutral2_100"), sys("system_accent1_600"), sys("system_accent1_0"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int sys(String name) {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier(name, "color", "android");
        if (id == 0) throw new Resources.NotFoundException(name);
        return getColor(id);
    }
}
