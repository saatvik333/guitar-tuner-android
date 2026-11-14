package me.saatvik.tuner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import me.saatvik.hairline.Chrome;
import me.saatvik.hairline.Palette;
import me.saatvik.hairline.Themes;

public final class MainActivity extends Activity implements TunerView.Host, AudioInput.Sink, ToneSynth.Listener {

    private static final String MIC = Manifest.permission.RECORD_AUDIO;
    private static final int REQ_MIC = 1;

    private SharedPreferences prefs;
    private SharedPreferences device;               // per-device facts, excluded from backup (res/xml/backup_rules.xml)
    private final Settings settings = new Settings();
    private TunerView view;
    private AudioInput audio;
    private ToneSynth tone;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started, askedThisLaunch, blocked;
    private boolean rationaleBefore;                // shouldShowRequestPermissionRationale at request time
    private Chrome.Back back;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tuner", MODE_PRIVATE);
        device = getSharedPreferences("device", MODE_PRIVATE);
        settings.load(prefs);
        tone = new ToneSynth(this, getSystemService(AudioManager.class));
        if (state != null) {
            askedThisLaunch = state.getBoolean("asked");
            rationaleBefore = state.getBoolean("rationale");
            blocked = state.getBoolean("blocked");
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);   // volume keys set the reference tone

        audio = new AudioInput(this);
        view = new TunerView(this, new Renderer(Chrome.font(getAssets(), 300), Chrome.font(getAssets(), 400)),
                settings, Themes.all(this), this);
        back = new Chrome.Back(this, view);
        if (state != null) view.restoreState(state);
        setContentView(view);
        Chrome.edgeToEdge(getWindow());
        onThemeApplied(view.currentTarget());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", askedThisLaunch);
        out.putBoolean("rationale", rationaleBefore);
        out.putBoolean("blocked", blocked);
        view.saveState(out);
    }

    // Capture follows visibility, not focus: a multi-window pane that is visible but unfocused
    // keeps listening. Only the reference tone, which is output, yields with focus.
    @Override
    protected void onStart() {
        super.onStart();
        started = true;
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
        view.stopTone();                        // through the view, so its tone button clears too
        settings.save(prefs);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        view.pause();
        audio.stop();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC || results.length == 0) return;     // empty: the request was cancelled
        if (results[0] == PackageManager.PERMISSION_GRANTED) {
            if (started) micGranted();
            return;
        }
        // The system stops showing the dialog once the user has refused it for good; it then
        // answers at once with rationale false. Rationale is also false before the first refusal
        // and after a dismissed dialog, so it means "blocked" only when it was true before this
        // request, or a refusal was recorded on an earlier launch.
        boolean after = shouldShowRequestPermissionRationale(MIC);
        boolean refusedBefore = device.getBoolean("micRefused", false);
        if (after && !refusedBefore) device.edit().putBoolean("micRefused", true).apply();
        blocked = !after && (rationaleBefore || refusedBefore);
        view.micRefused(blocked ? TunerView.MIC_BLOCKED : TunerView.MIC_NEED);
    }

    private void requestMic() {
        rationaleBefore = shouldShowRequestPermissionRationale(MIC);
        requestPermissions(new String[]{MIC}, REQ_MIC);
    }

    private void micGranted() {
        blocked = false;
        if (device.getBoolean("micRefused", false)) device.edit().remove("micRefused").apply();
        view.setMic(TunerView.MIC_OK);
        if (!view.paused()) startAudio();
    }

    /** Multi-window: access may have been granted from another window while this one stayed started. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        int m = view.mic();
        if (hasFocus && started && (m == TunerView.MIC_NEED || m == TunerView.MIC_BLOCKED) && hasMic()) micGranted();
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
            if (started && hasMic()) {
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
            if (started && hasMic() && !view.paused()) startAudio();
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
                if (started) view.setMic(hasMic() ? TunerView.MIC_ERROR : TunerView.MIC_NEED);
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

    // ---- back and window ---------------------------------------------------------------------

    /** Before Android 13: legacy back dispatch. */
    @Override
    public void onBackPressed() {
        if (!view.back()) super.onBackPressed();
    }

    /** Android 13+: back reaches the view only while it has something to close (settings page, reference tone, Advanced). */
    @Override
    public void setBackIntercept(boolean on) {
        back.intercept(on);
    }

    @Override
    public void onThemeApplied(Palette p) {
        Chrome.theme(getWindow(), p);
    }
}
