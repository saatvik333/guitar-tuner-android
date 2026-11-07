package app.tuner.guitar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns animation state, input and accessibility; delegates drawing to {@link Renderer}.
 * Pitch frames arrive from the capture thread through {@link #offer} and are drained on the UI
 * thread into the {@link Tracker}; a frame is drawn only when something visible changed.
 */
final class TunerView extends View {

    interface Host {
        void onThemeApplied(Palette target);
        void onSettingsChanged();
        void setListening(boolean on);
        void onMicAction(int micState);
        void playTone(double hz);
        void stopTone();
        void setBackIntercept(boolean on);
        /** Lowest pitch one string of the selected tuning can sound; lower means several notes. */
        void setPitchFloor(double hz);
    }

    static final int MIC_OK = 0, MIC_NEED = 1, MIC_BLOCKED = 2, MIC_ERROR = 3;

    private static final float SPRING_W = 18f;    // rad/s
    private static final float SPRING_Z = 0.82f;
    private static final double SILENT_SEC = 1.5;
    private static final long TONE_TAIL_NS = 350_000_000L;   // speaker ring-out ignored after a tone

    private final Ui ui = new Ui();
    private final Renderer renderer;
    private final Tracker tracker = new Tracker();
    private final Settings settings;
    private final Palette[] themes;
    private final Host host;
    private final float slop;
    private final A11y a11y = new A11y();
    private final AccessibilityManager am;

    private int themeIdx, tuningIdx;
    private Palette palFrom, palTo;
    private float palT = 1f;
    private long lastNs;
    private boolean running;

    // Easy (the guided strings) is home; Advanced (the dial) is the other tab; settings goes over either.
    private static final int PAGE_TUNER = 0, PAGE_SETTINGS = 1, PAGE_GUIDE = 2;
    private int page = PAGE_GUIDE, basePage = PAGE_GUIDE;

    /** The Easy tab is showing, or settings opened from it. */
    private boolean guideMode() {
        return page == PAGE_GUIDE || page == PAGE_SETTINGS && basePage == PAGE_GUIDE;
    }

    // ---- guided tuning ----
    private static final long ADVANCE_MS = 1100;          // "Tuned" stays this long before the next string
    private static final long MUTE_NS = 3_000_000_000L;   // the previous string may still ring
    private int gStep;
    private boolean gComplete, gAdvancing;
    private double gMuteHz;
    private long gMuteUntilNs;
    private double gBest;                                 // closest |cents| in this note
    private int gBestSign;
    private boolean gWrongWay, gWasLive;
    private int gStatus = -1, gStatusArg;
    private double gFollowedOnset = -1.0;                 // onset already checked for another string
    private long gLastHeardNs;
    private boolean gUnheardShown;
    private String gDescription = "";
    private float gPluckClock;
    private long t0Ns;

    private final Runnable advancer = new Runnable() {
        @Override
        public void run() {
            if (!guideMode() || gComplete || !gAdvancing) return;
            gAdvancing = false;
            if ((tracker.tunedMask & (1 << gStep)) == 0) {   // drifted off during the pause
                invalidate();
                return;
            }
            guideNext(true);
        }
    };
    private boolean paused, toneOn;
    private int toneMidi = -1, toneString = -1;
    private long ignoreUntilNs;
    private int mic = MIC_OK;
    private boolean silent;
    private float beadV;

    // readout cache: strings are rebuilt only when what they show changes
    private int shownMidi = Integer.MIN_VALUE, shownCentsQ = Integer.MIN_VALUE, shownHzQ = Integer.MIN_VALUE, shownState = -1,
            shownOther = -2;
    private String description = "";
    private String dialSpoken = "";
    private long dialSpokenNs;

    private int downId, repeatId;
    private boolean repeated;
    private float downX, downY, lastY;
    private boolean swiping, scrolling, multi;
    private int hitSig;

    // ---- frames from the capture thread ----
    private static final int Q = 64;
    private final Object qLock = new Object();
    private final double[] qT = new double[Q], qF = new double[Q];
    private final boolean[] qV = new boolean[Q], qOn = new boolean[Q], qPoly = new boolean[Q];
    private final float[][] qStrum = new float[Q][];
    private int qFs = 48000;
    private int qHead, qCount;
    private double qSilent;
    private int session;                                     // guarded by qLock
    private final AtomicBoolean drainPosted = new AtomicBoolean();
    // View.post from a background thread before the first traversal is dropped on API 23
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean keepOn;
    private final double[] dT = new double[Q], dF = new double[Q];
    private final boolean[] dV = new boolean[Q];
    private final boolean[] dOn = new boolean[Q], dPoly = new boolean[Q];
    private final float[][] dStrum = new float[Q][];
    private int fsNow = 48000;

    // ---- strums ----
    private StrumAnalyzer.Result strumResult;   // shown until a single string is played again
    private Tuning strumTuning;
    private float[] strumSamples;
    private int strumSamplesFs, strumToken;
    private double strumAt = -1.0;              // audio time of the strum on show
    private long strumHeardNs, chordUntilNs;
    private boolean strumBusy;
    private static final long STRUM_WAIT_NS = 1_600_000_000L, CHORD_NS = 3_500_000_000L;

    private final Runnable drain = new Runnable() {
        @Override
        public void run() {
            drainFrames();
        }
    };

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (repeatId == 0 || ui.pressedId != repeatId) return;
            repeated = true;
            action(repeatId);
            postDelayed(this, 70);
        }
    };

    TunerView(Context ctx, Renderer renderer, Settings settings, Palette[] themes, Host host) {
        super(ctx);
        this.renderer = renderer;
        this.settings = settings;
        this.themes = themes;
        this.host = host;
        am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        float density = getResources().getDisplayMetrics().density;
        ui.dp = density;
        ui.sp = Math.min(getResources().getDisplayMetrics().scaledDensity, density * 1.3f);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        themeIdx = 0;
        for (int i = 0; i < themes.length; i++) if (themes[i].name.equals(settings.theme)) themeIdx = i;
        palFrom = palTo = themes[themeIdx];
        ui.pal = palTo;
        ui.themeName = palTo.name;
        tuningIdx = Tuning.indexOf(settings.tuning);
        applyTuning(true);
        syncSettings(true);
        t0Ns = System.nanoTime();
        enterGuide(true);                                    // everyone lands on Easy
        setHapticFeedbackEnabled(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    Palette currentTarget() {
        return palTo;
    }

    boolean paused() {
        return paused;
    }

    int mic() {
        return mic;
    }

    void resume() {
        running = true;
        lastNs = 0;
        invalidate();
    }

    void pause() {
        running = false;
        if (toneOn) stopTone();
        removeCallbacks(repeater);
        if (gAdvancing) {
            main.removeCallbacks(advancer);
            advancer.run();                             // the string was tuned: move on now
        }
    }

    // ---- microphone ------------------------------------------------------------------------

    void setMic(int state) {
        if (state != mic) {
            mic = state;
            shownState = -1;
            if (state != MIC_OK) announce(micMessage());
            invalidate();
        }
    }

    /** Capture (re)started: audio time begins again at zero; frames of older sessions are dropped. */
    void onAudioStart(int id) {
        synchronized (qLock) {
            session = id;
            qCount = 0;
            qSilent = 0.0;
        }
        drainPosted.set(false);
        tracker.reset(false);
        gFollowedOnset = -1e9;                       // audio time starts again at zero
        tracker.lastOnset = -1e9;
        clearStrum();
        host.setPitchFloor(pitchFloor());
        silent = false;
        invalidate();
    }

    /** Capture thread: one analysed hop. */
    void offer(PitchEngine e, int id) {
        synchronized (qLock) {
            if (id != session) return;
            int i = (qHead + qCount) % Q;
            if (qCount == Q) {
                qHead = (qHead + 1) % Q;           // UI is behind: drop the oldest
                i = (qHead + qCount - 1) % Q;
            } else {
                qCount++;
            }
            qT[i] = e.time;
            qF[i] = e.freq;
            qV[i] = e.voiced;
            qOn[i] = e.onset;
            qPoly[i] = e.poly;
            qStrum[i] = e.strum;                   // a fresh array per strum: safe to hand over
            qFs = e.fs;
            qSilent = e.silentSec;
        }
        if (drainPosted.compareAndSet(false, true)) main.post(drain);
    }

    private void drainFrames() {
        drainPosted.set(false);
        int n;
        double sil;
        synchronized (qLock) {
            n = qCount;
            for (int k = 0; k < n; k++) {
                int i = (qHead + k) % Q;
                dT[k] = qT[i];
                dF[k] = qF[i];
                dV[k] = qV[i];
                dOn[k] = qOn[i];
                dPoly[k] = qPoly[i];
                dStrum[k] = qStrum[i];
                qStrum[i] = null;
            }
            fsNow = qFs;
            qHead = (qHead + n) % Q;
            qCount = 0;
            sil = qSilent;
        }
        if (n == 0) return;
        int sig = trackerSig();
        double cents0 = tracker.cents;
        long nowNs = System.nanoTime();
        boolean guide = guideMode();
        boolean ignore = toneOn || paused || nowNs < ignoreUntilNs || (guide && gComplete);
        int strumSig = strumState();
        for (int k = 0; k < n; k++) {
            boolean v = dV[k] && !ignore && !dPoly[k];
            if (v && guide && gMuteHz > 0.0) {
                if (nowNs > gMuteUntilNs) gMuteHz = 0.0;
                else if (Math.abs(Tuning.cents(dF[k], gMuteHz)) < 80.0) v = false;   // last string still ringing
                else gMuteHz = 0.0;
            }
            if (dPoly[k] && !ignore) {
                strumHeardNs = nowNs;                // several strings at once: wait for the analysis
                if (strumAt < 0) strumAt = dT[k];
                if (nowNs < chordUntilNs) chordUntilNs = Math.max(chordUntilNs, nowNs + 1_500_000_000L);   // still ringing
            }
            if (dStrum[k] != null && !ignore) {
                strumAt = dT[k];                     // later plucks than this clear what it shows
                analyseStrum(dStrum[k], fsNow);
            }
            tracker.update(dT[k], v, v ? dF[k] : Double.NaN, dOn[k] && !ignore);
            boolean waitingOnly = strumResult == null && nowNs >= chordUntilNs && !strumBusy && nowNs - strumHeardNs < STRUM_WAIT_NS;
            if (v && (strumResult != null || nowNs < chordUntilNs || waitingOnly) && tracker.lastOnset > strumAt + 0.05) {
                clearStrum();                        // a single string again: back to tuning it
            }
        }
        if (guide) guideWatch();
        boolean strumChanged = strumState() != strumSig;
        boolean nowSilent = !paused && sil >= SILENT_SEC;
        boolean changed = nowSilent != silent || trackerSig() != sig || Math.abs(tracker.cents - cents0) > 0.01 || strumChanged;
        silent = nowSilent;
        int ev = tracker.takeEvents();
        if ((ev & Tracker.EV_IN_TUNE) != 0 && !ignore) {
            ui.pulseT = 0f;
            buzz(true);
            if (guide && !gComplete && tracker.string == gStep) {
                if (settings.guideAuto) {
                    gAdvancing = true;
                    main.removeCallbacks(advancer);
                    main.postDelayed(advancer, ADVANCE_MS);
                }
                announce("Tuned");
            } else {
                announce(tracker.string >= 0 ? "String " + tuning().stringNumber(tracker.string) + " in tune" : "In tune");
            }
            changed = true;
        }
        if (changed && running) invalidate();
    }

    private int trackerSig() {
        int s = tracker.live ? 1 : 0;
        s = s * 2 + (tracker.hasNote ? 1 : 0);
        s = s * 2 + (tracker.inTune ? 1 : 0);
        s = s * 31 + tracker.midi;
        s = s * 31 + tracker.string;
        s = s * 31 + tracker.tunedMask;
        return s;
    }

    // ---- strums and chords --------------------------------------------------------------------
    // Several strings at once: the detector stops reporting a pitch and hands over ~0.7 s of
    // audio. On a worker thread it is measured against every tuning: open strings of the
    // selected tuning (each string's deviation), open strings of another tuning (offered as a
    // switch), or a chord (pluck one string at a time).

    private int strumState() {
        long now = System.nanoTime();
        return (strumResult != null ? 1 : 0) + (now < chordUntilNs ? 2 : 0)
                + (strumBusy || now - strumHeardNs < STRUM_WAIT_NS ? 4 : 0) + 8 * strumToken;
    }

    private void analyseStrum(final float[] w, final int fs) {
        final int token = ++strumToken;
        final Tuning t = tuning();
        final double a4 = settings.a4;
        strumSamples = w;
        strumSamplesFs = fs;
        if (t.chromatic()) {
            showStrum(null, t, token);
            return;
        }
        strumBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                StrumAnalyzer.Result res = null;
                try {
                    res = new StrumAnalyzer().analyze(w, fs, t, a4);
                } catch (RuntimeException e) {
                    // reported as nothing to tell string by string: "checking" never stays up
                }
                final StrumAnalyzer.Result r = res;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        showStrum(r, t, token);
                    }
                });
            }
        }, "tuner-strum").start();
    }

    /** UI thread. {@code r} null or a chord: say so; otherwise the per-string results. */
    private void showStrum(StrumAnalyzer.Result r, Tuning t, int token) {
        if (token != strumToken) return;              // superseded by a newer strum
        strumBusy = false;
        if (t != tuning()) return;                    // the tuning changed meanwhile
        long now = System.nanoTime();
        if (r == null || r.chord || r.heard < Math.max(2, t.count() / 2)) {
            strumResult = null;                       // a chord, or nothing to tell string by string
            chordUntilNs = now + CHORD_NS;
            if (!guideMode()) announce("Several notes. Pluck one open string at a time");
        } else {
            strumResult = r;
            strumTuning = t;
            chordUntilNs = 0;
            if (!guideMode()) announce(strumSpoken());
        }
        shownState = -1;
        invalidate();
    }

    private void clearStrum() {
        strumResult = null;
        chordUntilNs = 0;
        strumHeardNs = 0;
        strumAt = -1.0;
        strumToken++;                                // an analysis under way is no longer wanted
        strumBusy = false;
        shownState = -1;
    }

    /** In-tune range of the strum check: the setting, but not finer than the check can tell. */
    private double strumOk() {
        return Math.max(4.0, Settings.TOLERANCE[settings.tolIdx]);
    }

    private int strumWorst() {
        StrumAnalyzer.Result r = strumResult;
        int w = -1;
        for (int i = 0; i < r.count; i++) {
            if (!Double.isNaN(r.cents[i]) && (w < 0 || Math.abs(r.cents[i]) > Math.abs(r.cents[w]))) w = i;
        }
        return w;
    }

    private int strumInTune() {
        StrumAnalyzer.Result r = strumResult;
        int ok = 0;
        for (int i = 0; i < r.count; i++) if (!Double.isNaN(r.cents[i]) && Math.abs(r.cents[i]) <= strumOk()) ok++;
        return ok;
    }

    private String strumSpoken() {
        StrumAnalyzer.Result r = strumResult;
        Tuning t = strumTuning;
        if (r.suggest >= 0) return "Strum check. Sounds like " + Tuning.ALL[r.suggest].name;
        StringBuilder sb = new StringBuilder("Strum check. " + strumInTune() + " of " + t.count() + " in tune");
        for (int i = 0; i < r.count; i++) {
            if (Double.isNaN(r.cents[i]) || Math.abs(r.cents[i]) <= strumOk()) continue;
            int c = (int) Math.round(r.cents[i]);
            sb.append(". String ").append(t.stringNumber(i)).append(' ').append(Math.abs(c)).append(c < 0 ? " flat" : " sharp");
        }
        return sb.toString();
    }

    /** A string other than the locked one is sounding: its index, or -1. */
    private int otherString() {
        if (tracker.locked < 0 || !tracker.live || Double.isNaN(tracker.rawHz)) return -1;
        Tuning t = tuning();
        if (Math.abs(Tuning.cents(tracker.rawHz, Tuning.freq(t.strings[tracker.locked], settings.a4))) < 300.0) return -1;
        for (int j = 0; j < t.count(); j++) {
            if (j != tracker.locked && Math.abs(Tuning.cents(tracker.rawHz, Tuning.freq(t.strings[j], settings.a4))) < 50.0) return j;
        }
        return -1;
    }

    /** The action offered on the dial: switch tuning, or switch the locked string. */
    private void suggestion() {
        StrumAnalyzer.Result r = strumResult;
        if (r != null && r.suggest >= 0) {
            int idx = r.suggest;
            float[] w = strumSamples;
            int fs = strumSamplesFs;
            double at = strumAt;
            setTuning(idx);
            strumAt = at;                            // the same strum: a later pluck still clears it
            if (w != null) analyseStrum(w, fs);      // ... measured against the new tuning
            return;
        }
        int j = otherString();
        if (j >= 0) {
            tracker.locked = j;
            tracker.reset(false);
            announce("String " + tuning().stringNumber(j) + " locked");
        }
    }

    private double pitchFloor() {
        Tuning t = tuning();
        if (t.chromatic() || t.count() == 0) return PitchEngine.F_MIN;
        int lo = Integer.MAX_VALUE;
        for (int m : t.strings) lo = Math.min(lo, m);
        return Tuning.freq(lo, settings.a4) * Math.pow(2.0, -600.0 / 1200.0);   // a slack string still counts
    }

    boolean handleBack() {
        if (page == PAGE_SETTINGS) {
            page = basePage;
            announce(page == PAGE_GUIDE ? "Easy tuner" : "Advanced tuner");
            invalidate();
            return true;
        }
        if (toneOn) {
            stopTone();
            invalidate();
            return true;
        }
        if (page == PAGE_TUNER) {
            enterGuide(false);                          // Advanced backs out to Easy, the home tab
            return true;
        }
        return false;
    }

    // ---- layout ------------------------------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        int l = in.getSystemWindowInsetLeft(), t = in.getSystemWindowInsetTop();
        int r = in.getSystemWindowInsetRight(), b = in.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 30) {
            // system bars + display cutout; the deprecated getters leave the cutout out
            try {
                Class<?> type = Class.forName("android.view.WindowInsets$Type");
                int mask = (Integer) type.getMethod("systemBars").invoke(null)
                        | (Integer) type.getMethod("displayCutout").invoke(null);
                Object ins = WindowInsets.class.getMethod("getInsets", int.class).invoke(in, mask);
                Class<?> ic = ins.getClass();
                l = ic.getField("left").getInt(ins);
                t = ic.getField("top").getInt(ins);
                r = ic.getField("right").getInt(ins);
                b = ic.getField("bottom").getInt(ins);
            } catch (Exception ignored) {
                // keep the legacy values
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            try {
                Object cut = WindowInsets.class.getMethod("getDisplayCutout").invoke(in);
                if (cut != null) {
                    Class<?> cc = cut.getClass();
                    l = Math.max(l, (Integer) cc.getMethod("getSafeInsetLeft").invoke(cut));
                    t = Math.max(t, (Integer) cc.getMethod("getSafeInsetTop").invoke(cut));
                    r = Math.max(r, (Integer) cc.getMethod("getSafeInsetRight").invoke(cut));
                    b = Math.max(b, (Integer) cc.getMethod("getSafeInsetBottom").invoke(cut));
                }
            } catch (Exception ignored) {
            }
        }
        ui.insetL = l;
        ui.insetT = t;
        ui.insetR = r;
        ui.insetB = b;
        invalidate();
        return in;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ui.width = w;
        ui.height = h;
        invalidate();
    }

    // ---- frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNs) * 1e-9f);
        lastNs = now;
        boolean animating = step(dt, now);
        renderer.draw(c, ui);
        if (ui.settingsScroll > ui.settingsMaxScroll) ui.settingsScroll = ui.settingsMaxScroll;
        if (ui.focusId != 0) {
            Ui.Hit f = ui.find(ui.focusId);
            if (f == null) {
                ui.focusId = nearestFocusable(focusX, focusY);   // scrolled away or page changed
                if (ui.focusId != 0) invalidate();
            } else {
                focusX = f.rect.centerX();
                focusY = f.rect.centerY();
            }
        }
        a11yAfterFrame(now);
        if (running && animating) postInvalidateOnAnimation();
        else lastNs = 0;                   // next frame after an idle gap starts with a nominal dt
    }

    private boolean step(float dt, long now) {
        boolean anim = false;

        if (palT < 1f) {
            palT = Math.min(1f, palT + dt / 0.45f);
            ui.pal = Palette.lerp(palFrom, palTo, ease(palT));
            anim = true;
        }
        float ps = page == PAGE_SETTINGS ? 1f : 0f, pg = guideMode() ? 1f : 0f;
        ui.pageT = approach(ui.pageT, ps, dt, 0.085f);
        ui.guideT = approach(ui.guideT, pg, dt, 0.085f);
        anim |= ui.pageT != ps || ui.guideT != pg;
        host.setBackIntercept(page != PAGE_GUIDE || toneOn);           // host no-ops if unchanged
        ui.time = (now - t0Ns) * 1e-9f;

        long nowNs0 = System.nanoTime();
        boolean strumView = strumResult != null && strumResult.suggest < 0 && !guideMode();
        boolean overlay = !guideMode() && (strumResult != null || nowNs0 < chordUntilNs
                || strumBusy || nowNs0 - strumHeardNs < STRUM_WAIT_NS);
        int worst = strumView ? strumWorst() : -1;
        boolean strumBead = worst >= 0 && Math.abs(strumResult.cents[worst]) > strumOk();
        boolean showing = (overlay ? strumBead : tracker.hasNote) && !toneOn && !paused && mic == MIC_OK && !silent;
        float nt = showing ? 1f : 0f, lt = showing && tracker.live && !strumView ? 1f : 0f;
        float tt = showing && !strumView && (tracker.live && tracker.inTune || guideMode() && gAdvancing) ? 1f : 0f;
        ui.noteT = approach(ui.noteT, nt, dt, 0.12f);
        ui.liveT = approach(ui.liveT, lt, dt, 0.10f);
        ui.tuneT = approach(ui.tuneT, tt, dt, 0.07f);
        anim |= ui.noteT != nt || ui.liveT != lt || ui.tuneT != tt;

        float target = strumBead ? Renderer.beadOf(strumResult.cents[worst])
                : !tracker.hasNote ? 0f
                : guideMode() ? (float) Math.tanh(tracker.cents / 90.0)    // wider: beginners start far off
                : Renderer.beadOf(tracker.cents);
        if (ui.noteT == 0f) {
            ui.bead = target;
            beadV = 0f;
        } else {
            anim |= spring(target, dt);
        }
        if (ui.pulseT >= 0f) {
            ui.pulseT += dt / 0.7f;
            if (ui.pulseT > 1f) ui.pulseT = -1f;
            anim = true;
        }

        float st = tuning().chromatic() ? 0f : 1f;
        ui.stringsT = approach(ui.stringsT, st, dt, 0.09f);
        anim |= ui.stringsT != st;
        int tgt = toneOn ? toneString : strumView ? worst : tracker.hasNote ? tracker.string : -1;
        float sv = strumView ? 1f : 0f;
        ui.strumT = approach(ui.strumT, sv, dt, 0.1f);
        anim |= ui.strumT != sv;
        ui.strumOk = (float) strumOk();
        for (int i = 0; i < ui.stringCount; i++) {
            ui.sStrum[i] = strumView && i < strumResult.count ? (float) strumResult.cents[i] : ui.sStrum[i];
        }
        for (int i = 0; i < ui.stringCount; i++) {
            float a = (tracker.tunedMask & (1 << i)) != 0 ? 1f : 0f;
            float b = i == tgt ? (toneOn || (tracker.live && !paused) ? 1f : 0.5f) : 0f;
            float k = tracker.locked == i ? 1f : 0f;
            ui.sTunedT[i] = approach(ui.sTunedT[i], a, dt, 0.08f);
            ui.sTargetT[i] = approach(ui.sTargetT[i], b, dt, 0.08f);
            ui.sLockT[i] = approach(ui.sLockT[i], k, dt, 0.08f);
            anim |= ui.sTunedT[i] != a || ui.sTargetT[i] != b || ui.sLockT[i] != k;
        }

        float h = settings.haptics ? 1f : 0f, ko = settings.keepOn ? 1f : 0f;
        ui.hapticsT = approach(ui.hapticsT, h, dt, 0.06f);
        ui.keepOnT = approach(ui.keepOnT, ko, dt, 0.06f);
        anim |= ui.hapticsT != h || ui.keepOnT != ko;

        ui.toneOn = toneOn;
        ui.paused = paused;
        updateReadout();
        long nowNs = System.nanoTime();
        if (nowNs < chordUntilNs || strumBusy || nowNs - strumHeardNs < STRUM_WAIT_NS) anim = true;
        if (ui.guideT > 0.001f) anim |= stepGuide(dt);

        // the screen stays on only while it is useful: tuning or a reference tone
        boolean awake = settings.keepOn && page != PAGE_SETTINGS && (toneOn || (!paused && mic == MIC_OK && !silent));
        if (awake != keepOn) {
            keepOn = awake;
            setKeepScreenOn(awake);
        }
        return anim;
    }

    /** Damped spring of the bead toward {@code target}, sub-stepped for stability. */
    private boolean spring(float target, float dt) {
        float h = 0.004f;
        for (float t = 0f; t < dt; t += h) {
            float s = Math.min(h, dt - t);
            float acc = SPRING_W * SPRING_W * (target - ui.bead) - 2f * SPRING_Z * SPRING_W * beadV;
            beadV += acc * s;
            ui.bead += beadV * s;
        }
        if (ui.bead > 1f) {
            ui.bead = 1f;
            beadV = Math.min(0f, beadV);
        } else if (ui.bead < -1f) {
            ui.bead = -1f;
            beadV = Math.max(0f, beadV);
        }
        boolean moving = Math.abs(target - ui.bead) > 0.0004f || Math.abs(beadV) > 0.004f;
        if (!moving) {
            ui.bead = target;
            beadV = 0f;
        }
        return moving;
    }

    // ---- readout -----------------------------------------------------------------------------

    private static final int ST_MESSAGE = 1, ST_PAUSED = 2, ST_TONE = 3, ST_IDLE = 4, ST_NOTE = 5,
            ST_STRUM = 6, ST_CHORD = 7, ST_STRUMMING = 8;

    private void updateReadout() {
        int state;
        // state = class * 16 + detail
        if (mic != MIC_OK) state = ST_MESSAGE * 16 + mic;
        else if (paused) state = ST_PAUSED * 16;
        else if (silent) state = ST_MESSAGE * 16 + 7;
        else if (toneOn) state = ST_TONE * 16;
        else if (strumResult != null) state = ST_STRUM * 16 + (strumToken & 15);
        else if (System.nanoTime() < chordUntilNs) state = ST_CHORD * 16;
        else if (strumBusy || System.nanoTime() - strumHeardNs < STRUM_WAIT_NS) state = ST_STRUMMING * 16;
        else if (!tracker.hasNote) state = ST_IDLE * 16;
        else state = ST_NOTE * 16 + (tracker.live ? 1 : 0) + (tracker.inTune ? 2 : 0) + (tracker.locked >= 0 ? 4 : 0);
        int other = state / 16 == ST_NOTE ? otherString() : -1;
        int midi = toneOn ? toneMidi : tracker.midi;
        int cq = (int) Math.round(tracker.cents * (Math.abs(tracker.cents) < 9.95 ? 10.0 : 1.0));
        int hq = (int) Math.round((toneOn ? Tuning.freq(toneMidi, settings.a4) : tracker.hz) * 100.0);
        if (state == shownState && midi == shownMidi && cq == shownCentsQ && hq == shownHzQ && other == shownOther) return;
        shownState = state;
        shownOther = other;
        shownMidi = midi;
        shownCentsQ = cq;
        shownHzQ = hq;

        ui.message = "";
        ui.messageAction = "";
        ui.hintAccent = false;
        ui.cents = "";
        ui.freq = "";
        ui.hint = "";
        ui.readCheck = false;
        ui.centsAccent = false;
        ui.readPill = "";
        Tuning t = tuning();
        boolean flats = t.flats(settings.flats);
        switch (state / 16) {
            case ST_MESSAGE:
                ui.message = micMessage();
                ui.messageAction = mic == MIC_NEED ? "Allow" : mic == MIC_BLOCKED ? "Open settings" : mic == MIC_ERROR ? "Retry" : "";
                ui.letter = 0;
                describe(ui.message);
                return;
            case ST_PAUSED:
                ui.caption = "Paused";
                ui.letter = 0;
                ui.hint = "Microphone off";
                describe("Paused. Microphone off");
                return;
            case ST_TONE: {
                ui.caption = "Reference tone";
                setNote(midi, flats);
                ui.freq = hz(Tuning.freq(midi, settings.a4));
                describe("Reference tone " + Tuning.spoken(midi, flats));
                return;
            }
            case ST_IDLE:
                ui.caption = "Listening";
                ui.letter = 0;
                ui.hint = t.chromatic() ? "Play a note" : "Play a string";
                describe("Listening. " + ui.hint);
                return;
            case ST_STRUMMING:
                ui.caption = "Strum";
                ui.letter = 0;
                ui.hint = "Checking every string…";
                describe("Strum. Checking every string");
                return;
            case ST_CHORD:
                ui.caption = "Chord";
                ui.letter = 0;
                ui.cents = "Several notes";
                ui.hint = "Pluck one open string at a time";
                describe("Chord. Pluck one open string at a time");
                return;
            case ST_STRUM:
                readStrum(flats);
                return;
            default:
                break;
        }
        ui.caption = tracker.string >= 0
                ? "String " + t.stringNumber(tracker.string) + (tracker.locked >= 0 ? " · Locked" : "")
                : "Chromatic";
        setNote(midi, flats);
        double c = tracker.cents;
        ui.cents = centsText(c);
        ui.freq = hz(tracker.hz);
        if (tracker.live) {
            if (tracker.inTune) {
                ui.hint = "In tune";
                ui.hintAccent = true;
            } else {
                ui.hint = c < 0 ? "Tune up" : "Tune down";
            }
        }
        if (other >= 0) {
            // locked to one string, another is sounding: say which, and offer to follow it
            ui.hint = "Sounds like string " + t.stringNumber(other);
            ui.hintAccent = true;
            ui.freq = "";
            ui.readPill = "Switch to string " + t.stringNumber(other);
            describe(spokenReading() + ". Sounds like string " + t.stringNumber(other));
            return;
        }
        describe(spokenReading());
    }

    private void readStrum(boolean flats) {
        StrumAnalyzer.Result r = strumResult;
        Tuning t = strumTuning;
        ui.caption = "Strum check";
        ui.letter = 0;
        if (r.suggest >= 0) {
            String name = Tuning.ALL[r.suggest].name;
            ui.cents = "Sounds like " + name;
            ui.hint = "Selected: " + t.name;
            ui.readPill = "Switch to " + name;
            describe(strumSpoken());
            return;
        }
        int w = strumWorst(), ok = strumInTune(), n = t.count();
        if (w < 0 || Math.abs(r.cents[w]) <= strumOk()) {
            ui.readCheck = true;
            ui.cents = r.heard == n ? "All in tune" : ok + " of " + n + " in tune";
            ui.centsAccent = true;
            ui.hint = r.heard == n ? "Pluck a string to fine-tune" : (n - r.heard) + " not heard";
        } else {
            ui.caption = "Strum check · " + ok + " of " + n + " in tune";
            setNote(t.strings[w], flats);
            ui.cents = centsText(r.cents[w]);
            ui.hint = "Tune string " + t.stringNumber(w) + (r.cents[w] < 0 ? " up" : " down") + " first";
        }
        describe(strumSpoken());
    }

    private void setNote(int midi, boolean flats) {
        ui.letter = Tuning.letter(midi, flats);
        ui.accidental = Tuning.accidental(midi, flats);
        ui.octave = String.valueOf(Tuning.octave(midi));
    }

    static String centsText(double c) {
        double a = Math.abs(c);
        String num = String.format(Locale.US, a < 9.95 ? "%.1f" : "%.0f", a);
        boolean zero = num.equals("0.0");
        return (zero ? "" : c < 0 ? "−" : "+") + num + " ¢";
    }

    private static String hz(double f) {
        return String.format(Locale.US, f < 1000 ? "%.2f Hz" : "%.1f Hz", f);
    }

    private String spokenReading() {
        Tuning t = tuning();
        String s = Tuning.spoken(tracker.midi, t.flats(settings.flats));
        if (tracker.string >= 0) s += ", string " + t.stringNumber(tracker.string);
        double c = tracker.cents;
        int r = (int) Math.round(Math.abs(c));
        if (tracker.inTune) s += ", in tune";
        else if (r == 0) s += ", within a cent";
        else s += ", " + r + (r == 1 ? " cent " : " cents ") + (c < 0 ? "flat" : "sharp");
        return tracker.live ? s : s + ", last reading";
    }

    private String micMessage() {
        switch (mic) {
            case MIC_NEED: return "Microphone access needed";
            case MIC_BLOCKED: return "Microphone access is off";
            case MIC_ERROR: return "Microphone unavailable";
            default: return silent ? "No sound from the microphone" : "";
        }
    }

    /** Text of the dial's virtual node. Not set on the host view: its changes would be spoken unthrottled. */
    private void describe(String s) {
        description = s;
    }

    private void announce(String s) {
        if (am != null && am.isEnabled()) announceForAccessibility(s);
    }

    // ---- input -------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastY = y;
                swiping = scrolling = multi = repeated = false;
                ui.focusId = 0;
                downId = ui.hitAt(x, y);
                ui.pressedId = downId;
                if (downId == Renderer.H_A4_PREV || downId == Renderer.H_A4_NEXT) {
                    repeatId = downId;
                    postDelayed(repeater, 450);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;                      // a second finger cancels the gesture
                cancelPress();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (multi) return true;
                float dx = x - downX, dy = y - downY;
                if (!swiping && !scrolling && !repeated && page != PAGE_SETTINGS && Math.abs(dx) > slop * 2f && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                }
                if (!swiping && !scrolling && !repeated && page == 1 && ui.settingsMaxScroll > 0f
                        && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    scrolling = true;
                }
                if (scrolling) {
                    ui.settingsScroll = clamp(ui.settingsScroll - (y - lastY), 0f, ui.settingsMaxScroll);
                    invalidate();
                }
                lastY = y;
                if (swiping || scrolling || (ui.pressedId != 0 && ui.hitAt(x, y) != downId)) {
                    if (ui.pressedId != 0) {
                        ui.pressedId = 0;
                        repeatId = 0;
                        removeCallbacks(repeater);
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = x - downX;
                removeCallbacks(repeater);
                ui.pressedId = 0;
                if (!multi) {
                    if (swiping) {
                        if (Math.abs(dx) > 64f * ui.dp) setTheme(themeIdx + (dx < 0f ? 1 : -1));
                    } else if (!scrolling && !repeated && downId != 0 && ui.hitAt(x, y) == downId) {
                        action(downId);
                    }
                }
                swiping = scrolling = multi = repeated = false;
                downId = repeatId = 0;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelPress();
                swiping = scrolling = multi = false;
                return true;
            default:
                return true;
        }
    }

    // ---- guided tuning -----------------------------------------------------------------------
    // One string at a time, top string first, in plain words: which string, tighten or loosen,
    // and when it is done. The tracker is locked to the string and does not fold octaves: a
    // beginner's string can be hundreds of cents off, and an octave error must read as one.

    /** In-tune range: the setting, but at least 3 cents on the guided page. */
    private double tolerance() {
        double t = Settings.TOLERANCE[settings.tolIdx];
        return guideMode() ? Math.max(3.0, t) : t;
    }

    private void applyTrackerMode() {
        boolean g = guideMode();
        tracker.tolerance = tolerance();
        tracker.foldOctaves = !g;
        if (g) tracker.locked = gComplete ? -1 : gStep;
    }

    /**
     * To the Easy tab. Progress carries over from Advanced: strings already marked tuned stay
     * marked, and the guide starts at the first one that is not, from the top.
     */
    private void enterGuide(boolean snap) {
        if (tuning().chromatic()) setTuning(Tuning.indexOf("Standard"));   // Easy works string by string
        if (toneOn) stopTone();
        page = basePage = PAGE_GUIDE;
        int n = tuning().count(), first = -1;
        for (int i = 0; i < n && first < 0; i++) if ((tracker.tunedMask & (1 << i)) == 0) first = i;
        gComplete = n > 0 && first < 0;
        if (!gComplete) gStep = Math.max(0, first);
        guideReset(0.0);
        ui.gCursor = gStep;
        ui.gDoneT = gComplete ? 1.2f : 0f;
        if (snap) ui.guideT = 1f;
        settings.guideSeen = true;
        gStatus = -1;
        if (!snap) announce("Easy tuner");
        invalidate();
    }

    /** To the Advanced tab: the dial, automatic strings. */
    private void exitGuide() {
        if (page != PAGE_GUIDE) return;
        page = basePage = PAGE_TUNER;
        main.removeCallbacks(advancer);
        gAdvancing = false;
        gMuteHz = 0.0;
        tracker.locked = -1;
        applyTrackerMode();
        tracker.reset(false);                               // the marks stay on the string row
        announce("Advanced tuner");
        invalidate();
    }

    /** Fresh state for the string at gStep; {@code muteHz} > 0 ignores the previous one ringing on. */
    private void guideReset(double muteHz) {
        main.removeCallbacks(advancer);
        gAdvancing = false;
        gStatus = -1;
        applyTrackerMode();
        tracker.reset(false);
        gMuteHz = muteHz;
        gMuteUntilNs = System.nanoTime() + MUTE_NS;
        gWrongWay = gWasLive = false;
        gPluckClock = 0f;
        gFollowedOnset = tracker.lastOnset;
        gLastHeardNs = System.nanoTime();
        gUnheardShown = false;
    }

    /** The reference tone, if playing, follows the step. */
    private void retargetTone() {
        if (!toneOn || gComplete || gStep >= tuning().count()) return;
        toneString = gStep;
        toneMidi = tuning().strings[gStep];
        host.playTone(Tuning.freq(toneMidi, settings.a4));
    }

    private void guideJump(int i) {
        gComplete = false;
        gStep = i;
        guideReset(0.0);
        retargetTone();
        ui.gDoneT = 0f;
        announce(guideTitle());
    }

    /** Next string not yet tuned after the current one, wrapping round; none left: the summary. */
    private void guideNext(boolean tuned) {
        Tuning t = tuning();
        int n = t.count(), prev = gStep, next = -1;
        for (int k = 1; k < n && next < 0; k++) {
            int j = (gStep + k) % n;
            if ((tracker.tunedMask & (1 << j)) == 0) next = j;
        }
        if (next < 0) {
            gComplete = true;
            guideReset(0.0);
            ui.gDoneT = 0f;
        } else {
            gStep = next;
            guideReset(tuned ? Tuning.freq(t.strings[prev], settings.a4) : 0.0);
            retargetTone();
        }
        announce(guideTitle());
        invalidate();
    }

    /** UI thread, after each batch of frames: notices a peg turned the wrong way. */
    private void guideWatch() {
        if (tracker.live) {
            gLastHeardNs = System.nanoTime();
            gUnheardShown = false;
        }
        if (settings.guideAuto && !gComplete && !gAdvancing && !toneOn && !paused && tracker.hasNote
                && !tracker.live && (tracker.tunedMask & (1 << gStep)) != 0) {
            gAdvancing = true;                           // re-checked, still tuned, rang out: next
            main.removeCallbacks(advancer);
            main.postDelayed(advancer, ADVANCE_MS);
        }
        if (!gComplete && settings.guideAuto && tracker.live && guideFollow()) return;
        if (gComplete || !tracker.live || !tracker.hasNote || tracker.now - tracker.lastOnset < 0.12) {
            gWasLive = tracker.live && gWasLive;           // the attack of a pluck says nothing yet
            return;
        }
        double c = tracker.cents, a = Math.abs(c);
        int sign = c < 0 ? -1 : 1;
        if (!gWasLive) {
            gBest = a;
            gBestSign = sign;
            gWrongWay = false;
        }
        gWasLive = true;
        if (a < gBest) {
            gBest = a;
            gBestSign = sign;
        }
        if (!gWrongWay && sign == gBestSign && a > gBest + 15.0 && a > 3.0 * tracker.tolerance) gWrongWay = true;
        else if (gWrongWay && a <= gBest + 6.0) gWrongWay = false;
    }

    /**
     * Automatic switching: a fresh pluck that is clearly another string (within 40 cents of it,
     * more than 300 from this one), heard 0.1 - 0.5 s after its onset, becomes the step. Never
     * during a held note: a peg being turned glides, it does not jump.
     */
    private boolean guideFollow() {
        double since = tracker.now - tracker.lastOnset;
        if (tracker.lastOnset <= gFollowedOnset || since < 0.1 || since > 0.5 || Double.isNaN(tracker.rawHz)) return false;
        gFollowedOnset = tracker.lastOnset;
        Tuning t = tuning();
        double f = tracker.rawHz;
        if (Math.abs(Tuning.cents(f, Tuning.freq(t.strings[gStep], settings.a4))) < 300.0) return false;
        int best = -1;
        double bc = 40.0;                                    // near pitch: a slack string rarely lands this close to another
        for (int j = 0; j < t.count(); j++) {
            double c = Math.abs(Tuning.cents(f, Tuning.freq(t.strings[j], settings.a4)));
            if (j != gStep && c < bc) {
                bc = c;
                best = j;
            }
        }
        if (best < 0) return false;
        double keep = tracker.lastOnset;
        guideJump(best);
        gFollowedOnset = keep;
        announce("Switched to the " + position(best));
        return true;
    }

    private static final int G_WAIT = 0, G_STALE = 1, G_TIGHTEN = 2, G_LOOSEN = 3, G_ALMOST = 4, G_TUNED = 5,
            G_WRONG_STRING = 6, G_WRONG_WAY = 7, G_DONE = 8, G_STRUM = 9, G_UNHEARD = 10, G_PAUSED = 11, G_TONE = 12,
            G_TOO_HIGH = 13;

    private static final String[] ORDINAL = {"", "", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth"};

    /** "top string", "third string from the top", "bottom string". */
    private String position(int i) {
        int n = tuning().count();
        if (i == 0) return "top string";
        if (i == n - 1) return "bottom string";
        return ORDINAL[i + 1].toLowerCase(Locale.US) + " string from the top";
    }

    /** "top string", "3rd from the top", "bottom string": for advice lines. */
    private String shortPos(int i) {
        int n = tuning().count();
        if (i == 0) return "top string";
        if (i == n - 1) return "bottom string";
        String[] o = {"", "", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th"};
        return o[i + 1] + " from the top";
    }

    private String guideTitle() {
        if (gComplete) {
            int n = tuning().count(), done = Integer.bitCount(tracker.tunedMask & ((1 << n) - 1));
            return done == n ? "All strings tuned" : done + " of " + n + " strings tuned";
        }
        int n = tuning().count();
        if (gStep == 0) return "Top string";
        if (gStep == n - 1) return "Bottom string";
        return ORDINAL[gStep + 1] + " from the top";
    }

    private String noteName(int midi) {
        boolean flats = tuning().flats(settings.flats);
        int acc = Tuning.accidental(midi, flats);
        return Tuning.letter(midi, flats) + (acc == 1 ? " sharp" : acc == 2 ? " flat" : "");
    }

    private String guideSub() {
        Tuning t = tuning();
        int n = t.count();
        if (gComplete) {
            return Integer.bitCount(tracker.tunedMask & ((1 << n) - 1)) == n
                    ? "Tuning one string pulls on the others a little. Go round once more to check."
                    : "Tap a string to go back to it.";
        }
        boolean mono = true;
        for (int i = 1; i < n; i++) mono &= t.strings[i] > t.strings[i - 1];
        String where;
        if (gStep == 0) where = mono ? "The thickest one, nearest your face. " : "The one nearest your face. ";
        else if (gStep == n - 1) where = mono ? "The thinnest one, nearest the floor. " : "The one nearest the floor. ";
        else where = "Pluck it and let it ring. ";
        return where + "Its note is " + noteName(t.strings[gStep]) + ".";
    }

    /** Status of the current step; returns true when the words changed. */
    private boolean guideStatus() {
        Tuning t = tuning();
        int st, arg = 0;
        double c = tracker.cents, tol = tracker.tolerance;
        long nowNs = System.nanoTime();
        boolean strummed = strumResult != null || nowNs < chordUntilNs || strumBusy || nowNs - strumHeardNs < STRUM_WAIT_NS;
        if (gComplete) st = G_DONE;
        else if (paused) st = G_PAUSED;
        else if (toneOn) st = G_TONE;
        else if (gAdvancing) st = G_TUNED;
        else if (strummed) st = G_STRUM;
        else if (!tracker.hasNote) st = nowNs - gLastHeardNs > 6_000_000_000L && mic == MIC_OK && !silent ? G_UNHEARD : G_WAIT;
        else {
            int other = -1;
            if (Math.abs(c) > 250.0) {
                for (int j = 0; j < t.count(); j++) {
                    if (j != gStep && Math.abs(Tuning.cents(tracker.hz, Tuning.freq(t.strings[j], settings.a4))) < 35.0) other = j;
                }
            }
            if (other >= 0) {
                st = G_WRONG_STRING;                                     // manual mode: point it out
                arg = other;
            } else if (c > 900.0) {
                // A string cannot be tightened most of an octave: it would have broken. This is a
                // misreading (a harmonic, an octave error), not a reason to say "loosen it now".
                st = G_TOO_HIGH;
            } else if (gWrongWay && tracker.live) st = G_WRONG_WAY;
            else if (Math.abs(c) <= tol || !tracker.live && (tracker.tunedMask & (1 << gStep)) != 0) {
                boolean marked = (tracker.tunedMask & (1 << gStep)) != 0;
                st = tracker.inTune || !tracker.live && marked ? G_TUNED : G_ALMOST;
                if (st == G_ALMOST && !tracker.live) arg = 10;                // stale: pluck again
            }
            else {
                st = c < 0 ? G_TIGHTEN : G_LOOSEN;
                double a = Math.abs(c);
                arg = a > (c < 0 ? 100.0 : 200.0) ? 2 : a > 15.0 ? 1 : 0;   // far, off, near
                if (c > 0 && a > 100.0 && a <= 200.0) arg = 3;               // high: go below first
                if (!tracker.live) arg += 10;                                // stale: pluck again
            }
        }
        if (st == gStatus && arg == gStatusArg) return false;
        gStatus = st;
        gStatusArg = arg;
        String word, detail;
        boolean accent = false;
        boolean stale = arg >= 10;
        int lvl = arg % 10;
        switch (st) {
            case G_DONE: word = ""; detail = ""; break;
            case G_TUNED:
                word = "Tuned";
                accent = true;
                detail = settings.guideAuto ? "" : "Tap Next for the next string.";
                break;
            case G_WAIT:
                word = "";
                detail = "Listening";
                break;
            case G_PAUSED:
                word = "";
                detail = "Paused. Tap the pause button to listen again.";
                break;
            case G_TONE:
                word = "Listen";
                detail = "How the " + position(gStep) + " should sound. Tap the fork to stop.";
                break;
            case G_UNHEARD:
                gUnheardShown = true;
                word = "";
                detail = "Not hearing it yet. Hold the phone close and pluck firmly.";
                break;
            case G_STRUM:
                word = "One string";
                // a string far too slack (new, unwound) sounds below anything a string should: it
                // reads as several notes too
                detail = "Pluck only the " + position(gStep) + ". If it is floppy, tighten it first.";
                break;
            case G_WRONG_STRING:
                word = "Wrong string?";
                detail = "That is the " + shortPos(arg) + ". Pluck the " + shortPos(gStep) + ", or tap that one.";
                break;
            case G_TOO_HIGH:
                word = "Pluck again";
                detail = "Far too high for this string. Pluck it again, mid-string.";
                break;
            case G_WRONG_WAY:
                word = "Other way";
                detail = "The note is moving away. Turn the peg the other way.";
                break;
            case G_ALMOST:
                word = "Almost";
                detail = stale ? "Pluck again to check it." : "Right there. Let it ring.";
                break;
            case G_TIGHTEN:
                word = "Tighten";
                detail = stale ? "Pluck again while you turn the peg."
                        : lvl == 2 ? "Much too low. Turn the peg slowly as you pluck."
                        : lvl == 1 ? "Too low. Keep turning slowly." : "Nearly there. Just a touch more.";
                break;
            default:
                word = "Loosen";
                detail = stale ? "Pluck again while you turn the peg."
                        : lvl == 2 ? "Far too high. Loosen it now, or it could break."
                        : lvl == 3 ? "Much too high. Go below the note, then tighten up to it."
                        : lvl == 1 ? "Too high. Go a little below, then tighten up to it." : "Nearly there. Just a touch looser.";
                break;
        }
        if (st == G_WRONG_WAY && ui.gShakeT < 0f) ui.gShakeT = 0f;
        if (!word.equals(ui.gWord) || !detail.equals(ui.gDetail)) {
            ui.gWordPrev = ui.gWord;
            ui.gDetailPrev = ui.gDetail;
            ui.gWord = word;
            ui.gDetail = detail;
            ui.gWordT = 0f;
        }
        ui.gWordAccent = accent;
        return true;
    }

    /** Words and motion of the guided page. Returns true while anything moves (always, when shown). */
    private boolean stepGuide(float dt) {
        Tuning t = tuning();
        if (t.chromatic() || gStep >= t.count()) {
            // Easy still fading out while Advanced selects a tuning it cannot show (Chromatic, or
            // fewer strings than the step): keep the last words, finish the fade.
            return running && ui.guideT > 0.001f;
        }
        int n = Math.min(t.count(), Ui.MAX_STRINGS);
        ui.guideDone = gComplete;
        ui.gStep = gStep;
        ui.gAuto = settings.guideAuto;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, t.strings[i]);
            hi = Math.max(hi, t.strings[i]);
        }
        for (int i = 0; i < n; i++) ui.gThick[i] = hi > lo ? (hi - t.strings[i]) / (float) (hi - lo) : 0.5f;

        String title = guideTitle(), sub = guideSub();
        boolean words = false;
        if (!title.equals(ui.gTitle) || !sub.equals(ui.gSub)) {
            ui.gTitlePrev = ui.gTitle;
            ui.gSubPrev = ui.gSub;
            ui.gTitle = title;
            ui.gSub = sub;
            ui.gTitleT = ui.gTitlePrev.length() == 0 ? 1f : 0f;
            words = true;
        }
        String caption = gComplete ? t.name + " tuning" : "Step " + (gStep + 1) + " of " + n + " · " + t.name;
        if (!caption.equals(ui.gCaption)) ui.gCaption = caption;
        words |= guideStatus();
        if (words) {
            gDescription = gComplete ? title + ". " + sub
                    : (title + ". " + (ui.gWord.length() > 0 ? ui.gWord + ". " : "") + ui.gDetail).trim();
        }

        ui.gTitleT = Math.min(1f, ui.gTitleT + dt / 0.35f);
        ui.gWordT = Math.min(1f, ui.gWordT + dt / 0.25f);
        ui.gCursor = approach(ui.gCursor, gStep, dt, 0.09f);
        boolean ok = mic == MIC_OK && !silent && !paused && !toneOn;
        boolean waiting = ok && !gComplete && !gAdvancing && !tracker.live;
        // Easy is the home screen: the demonstration plays three times, then rests (no frames,
        // no battery) until the step changes or the next sound has died away.
        if (waiting && gPluckClock < DEMO_S) {
            gPluckClock += dt;
            ui.gPluckT = gPluckClock < DEMO_S ? gPluckClock % 2.8f : -1f;
        } else {
            if (!waiting) gPluckClock = 0f;
            ui.gPluckT = -1f;
        }
        boolean live = ok && tracker.live && !gComplete;
        ui.gVib = approach(ui.gVib, live ? 1f : 0f, dt, live ? 0.05f : 0.4f);
        float sag = tracker.hasNote && !gComplete ? (float) Math.max(0.0, Math.min(1.0, -tracker.cents / 150.0)) : 0f;
        ui.gSag = approach(ui.gSag, sag, dt, 0.15f);
        boolean direction = gStatus == G_TIGHTEN || gStatus == G_LOOSEN || gStatus == G_WRONG_WAY || gStatus == G_ALMOST;
        float chev = live && !gAdvancing && direction && Math.abs(tracker.cents) > tracker.tolerance ? 1f : 0f;
        ui.gChevT = approach(ui.gChevT, chev, dt, 0.12f);
        if (ui.gShakeT >= 0f) {
            ui.gShakeT += dt / 0.5f;
            if (ui.gShakeT >= 1f) ui.gShakeT = -1f;
        }
        if (gComplete) ui.gDoneT = Math.min(1.2f, ui.gDoneT + dt / 0.8f);
        boolean moving = ui.gPluckT >= 0f || ui.gVib > 0f || ui.gChevT > 0f || ui.gShakeT >= 0f
                || ui.gTitleT < 1f || ui.gWordT < 1f || ui.gCursor != gStep || ui.gSag != sag
                || gComplete && ui.gDoneT < 1.2f
                || waiting && !gUnheardShown && System.nanoTime() - gLastHeardNs < 6_500_000_000L;   // for "not hearing it"
        return running && moving;
    }

    private static final float DEMO_S = 3 * 2.8f;

    // ---- keyboard and D-pad -------------------------------------------------------------------

    private float focusX, focusY;

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (gained && !isInTouchMode() && ui.focusId == 0) {
            ui.focusId = firstFocusable();
            invalidate();
        } else if (!gained && ui.focusId != 0) {
            ui.focusId = 0;
            invalidate();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return moveFocus(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return moveFocus(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP: return moveFocus(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return moveFocus(0, 1);
            case KeyEvent.KEYCODE_TAB: return stepFocus(e.isShiftPressed() ? -1 : 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE: {
                if (ui.focusId == 0 || ui.find(ui.focusId) == null) return stepFocus(1);
                int id = ui.focusId;
                boolean repeats = id == Renderer.H_A4_PREV || id == Renderer.H_A4_NEXT;
                if (e.getRepeatCount() == 0 || repeats) {
                    ui.pressedId = id;
                    action(id);
                    a11y.send(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    invalidate();
                }
                return true;
            }
            case KeyEvent.KEYCODE_ESCAPE:
                return handleBack();
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (ui.pressedId != 0) {
                    ui.pressedId = 0;
                    invalidate();
                }
                return true;
            default:
                return super.onKeyUp(code, e);
        }
    }

    private boolean focusable(Ui.Hit h) {
        return h.id != Renderer.H_DIAL && (h.id != Renderer.H_GUIDE || guideMeterActs());
    }

    /**
     * The guide's meter is a button when a tap has something to do: with Auto off and the
     * string tuned, Next; in windows too short for the Skip / Auto row, that row's Next / Skip.
     */
    private boolean guideMeterActs() {
        if (!guideMode() || gComplete) return false;
        return !ui.gPills || !settings.guideAuto && (tracker.tunedMask & (1 << gStep)) != 0;
    }

    /** Reading order: rows top to bottom, then left to right. */
    private float order(Ui.Hit h) {
        float row = Math.round(h.rect.centerY() / (24f * ui.dp));
        return row * 100000f + h.rect.centerX();
    }

    private int firstFocusable() {
        int best = 0;
        float bo = Float.MAX_VALUE;
        for (int i = 0; i < ui.hitCount; i++) {
            Ui.Hit h = ui.hits[i];
            if (!focusable(h)) continue;
            float o = order(h);
            if (o < bo) {
                bo = o;
                best = h.id;
            }
        }
        return best;
    }

    private int nearestFocusable(float x, float y) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < ui.hitCount; i++) {
            Ui.Hit h = ui.hits[i];
            if (!focusable(h)) continue;
            float dx = h.rect.centerX() - x, dy = h.rect.centerY() - y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = h.id;
            }
        }
        return best;
    }

    private boolean stepFocus(int dir) {
        Ui.Hit cur = ui.focusId != 0 ? ui.find(ui.focusId) : null;
        if (cur == null) {
            ui.focusId = firstFocusable();
        } else {
            float co = order(cur);
            int best = 0;
            float bo = dir > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
            for (int i = 0; i < ui.hitCount; i++) {
                Ui.Hit h = ui.hits[i];
                if (!focusable(h) || h == cur) continue;
                float o = order(h);
                if (dir > 0 ? (o > co && o < bo) : (o < co && o > bo)) {
                    bo = o;
                    best = h.id;
                }
            }
            if (best == 0) return false;                   // let the system move focus on
            ui.focusId = best;
        }
        focusMoved();
        return true;
    }

    private boolean moveFocus(int dx, int dy) {
        Ui.Hit cur = ui.focusId != 0 ? ui.find(ui.focusId) : null;
        if (cur == null) {
            ui.focusId = firstFocusable();
            focusMoved();
            return true;
        }
        float x = cur.rect.centerX(), y = cur.rect.centerY();
        int best = 0;
        float bs = Float.MAX_VALUE;
        for (int i = 0; i < ui.hitCount; i++) {
            Ui.Hit h = ui.hits[i];
            if (!focusable(h) || h == cur) continue;
            float hx = h.rect.centerX() - x, hy = h.rect.centerY() - y;
            float along = hx * dx + hy * dy, across = Math.abs(hx * dy - hy * dx);
            if (along < 4f * ui.dp) continue;
            float score = along + 3f * across;
            if (score < bs) {
                bs = score;
                best = h.id;
            }
        }
        if (best == 0 && dy != 0 && page == 1 && ui.settingsMaxScroll > 0f) {
            // the next row is scrolled out of view: bring it in
            float to = clamp(ui.settingsScroll + dy * 96f * ui.dp, 0f, ui.settingsMaxScroll);
            if (to != ui.settingsScroll) {
                ui.settingsScroll = to;
                invalidate();
            }
            return true;
        }
        if (best != 0) {
            ui.focusId = best;
            focusMoved();
        }
        return true;
    }

    private void focusMoved() {
        Ui.Hit h = ui.find(ui.focusId);
        if (h != null) {
            focusX = h.rect.centerX();
            focusY = h.rect.centerY();
            a11y.send(ui.focusId, AccessibilityEvent.TYPE_VIEW_FOCUSED);
        }
        invalidate();
    }

    private void cancelPress() {
        removeCallbacks(repeater);
        ui.pressedId = 0;
        downId = repeatId = 0;
        swiping = scrolling = false;
        invalidate();
    }

    private void action(int id) {
        boolean changed = false;
        if (id >= Renderer.H_GSTRING && id < Renderer.H_GSTRING + Ui.MAX_STRINGS) {
            int i = id - Renderer.H_GSTRING;
            if (i < tuning().count()) guideJump(i);           // the tone, if on, follows
            buzz(false);
            invalidate();
            return;
        }
        if (id >= Renderer.H_STRING && id < Renderer.H_STRING + Ui.MAX_STRINGS) {
            int i = id - Renderer.H_STRING;
            if (i >= tuning().count()) return;
            if (toneOn) {
                toneString = i;
                toneMidi = tuning().strings[i];
                host.playTone(Tuning.freq(toneMidi, settings.a4));
            } else {
                tracker.locked = tracker.locked == i ? -1 : i;
                tracker.reset(false);
                clearStrum();
                announce(tracker.locked >= 0 ? "String " + tuning().stringNumber(i) + " locked" : "Automatic string");
            }
            buzz(false);
            invalidate();
            return;
        }
        switch (id) {
            case Renderer.H_THEME_PREV: setTheme(themeIdx - 1); break;
            case Renderer.H_THEME_NEXT: setTheme(themeIdx + 1); break;
            case Renderer.H_SETTINGS:
                if (page != PAGE_SETTINGS) basePage = page;
                page = PAGE_SETTINGS;
                ui.settingsScroll = 0f;
                announce("Settings");
                break;
            case Renderer.H_BACK:
                page = basePage;
                announce(page == PAGE_GUIDE ? "Easy tuner" : "Advanced tuner");
                break;
            case Renderer.H_TONE:
                // stopping it on a tuned string waits for a pluck that rings out (guideWatch):
                // nothing is measured while the tone plays, so the string has not been re-checked
                if (toneOn) stopTone();
                else startTone();
                break;
            case Renderer.H_PAUSE:
                paused = !paused;
                if (paused && toneOn) stopTone();
                tracker.reset(false);
                gLastHeardNs = System.nanoTime();         // "not hearing it" counts from now
                host.setListening(!paused);
                announce(paused ? "Paused" : "Listening");
                break;
            case Renderer.H_MIC: host.onMicAction(mic); break;
            case Renderer.H_SUGGEST: if (!guideMode()) suggestion(); break;
            case Renderer.H_GUIDE_OPEN:
                if (page == PAGE_GUIDE) exitGuide();
                else enterGuide(false);
                break;
            case Renderer.H_GUIDE_SKIP: guideNext(false); break;
            case Renderer.H_GUIDE:
                if (guideMeterActs()) guideNext((tracker.tunedMask & (1 << gStep)) != 0);
                break;
            case Renderer.H_GUIDE_AUTO:
                settings.guideAuto = !settings.guideAuto;
                if (!settings.guideAuto && gAdvancing) {
                    main.removeCallbacks(advancer);
                    gAdvancing = false;
                } else if (settings.guideAuto && !gComplete && !toneOn && (tracker.tunedMask & (1 << gStep)) != 0
                        && (tracker.inTune || !tracker.live)) {
                    gAdvancing = true;                       // already tuned: move on as Auto would have
                    main.removeCallbacks(advancer);
                    main.postDelayed(advancer, ADVANCE_MS);
                }
                gStatus = -1;                                // the words depend on the mode
                announce(settings.guideAuto ? "Strings switch automatically" : "Strings switch when you tap Next");
                changed = true;
                break;
            case Renderer.H_GUIDE_AGAIN:
                tracker.tunedMask = 0;
                guideJump(0);
                break;
            case Renderer.H_GUIDE_DONE: exitGuide(); break;
            // Advanced only: a tap landing in the moment Easy fades in must not change the tuning
            case Renderer.H_TUNING_PREV: if (!guideMode()) setTuning(tuningIdx - 1); changed = true; break;
            case Renderer.H_TUNING_NEXT: if (!guideMode()) setTuning(tuningIdx + 1); changed = true; break;
            case Renderer.H_A4_PREV:
                if (settings.a4 > Settings.A4_MIN) settings.a4--;
                changed = true;
                break;
            case Renderer.H_A4_NEXT:
                if (settings.a4 < Settings.A4_MAX) settings.a4++;
                changed = true;
                break;
            case Renderer.H_TOL_PREV: settings.tolIdx = wrapIdx(settings.tolIdx - 1, Settings.TOLERANCE.length); changed = true; break;
            case Renderer.H_TOL_NEXT: settings.tolIdx = wrapIdx(settings.tolIdx + 1, Settings.TOLERANCE.length); changed = true; break;
            case Renderer.H_NEEDLE_PREV:
            case Renderer.H_NEEDLE_NEXT: settings.fast = !settings.fast; changed = true; break;
            case Renderer.H_NAMES_PREV:
            case Renderer.H_NAMES_NEXT: settings.flats = !settings.flats; changed = true; break;
            case Renderer.H_HAPTIC: settings.haptics = !settings.haptics; changed = true; break;
            case Renderer.H_KEEPON:
                settings.keepOn = !settings.keepOn;
                changed = true;
                break;
            default: return;
        }
        buzz(false);
        if (changed) {
            syncSettings(false);
            host.onSettingsChanged();
        }
        invalidate();
    }

    private void startTone() {
        Tuning t = tuning();
        if (t.chromatic()) {
            toneString = -1;
            toneMidi = tracker.hasNote && tracker.midi >= 0 ? tracker.midi : 69;
        } else {
            toneString = tracker.locked >= 0 ? tracker.locked : tracker.hasNote && tracker.string >= 0 ? tracker.string : 0;
            toneMidi = t.strings[toneString];
        }
        toneOn = true;
        tracker.reset(false);
        host.playTone(Tuning.freq(toneMidi, settings.a4));
        announce("Reference tone " + Tuning.spoken(toneMidi, t.flats(settings.flats)));
    }

    /** The reference tone could not play: back to tuning. */
    void onToneFailed() {
        if (!toneOn) return;
        toneOn = false;
        toneString = -1;
        tracker.reset(false);
        announce("Reference tone unavailable");
        invalidate();
    }

    private void stopTone() {
        if (!toneOn) return;
        toneOn = false;
        toneString = -1;
        host.stopTone();
        ignoreUntilNs = System.nanoTime() + TONE_TAIL_NS;
        tracker.reset(false);
        gLastHeardNs = System.nanoTime();             // nothing was listened to while it played
    }

    private void setTheme(int idx) {
        themeIdx = wrapIdx(idx, themes.length);
        palFrom = ui.pal;
        palTo = themes[themeIdx];
        palT = 0f;
        ui.themeName = palTo.name;
        settings.theme = palTo.name;
        host.onThemeApplied(palTo);
        host.onSettingsChanged();
        announce("Theme " + palTo.name);
        invalidate();
    }

    private Tuning tuning() {
        return Tuning.ALL[tuningIdx];
    }

    private void setTuning(int idx) {
        tuningIdx = wrapIdx(idx, Tuning.ALL.length);
        settings.tuning = tuning().name;
        if (toneOn) stopTone();
        applyTuning(false);
        announce("Tuning " + tuning().name);
    }

    private void applyTuning(boolean snap) {
        Tuning t = tuning();
        tracker.tuning = t;
        tracker.locked = -1;
        tracker.reset(true);
        clearStrum();
        strumToken++;                                  // an analysis under way is for the old tuning
        strumBusy = false;
        if (gStep >= t.count()) gStep = 0;
        if (!snap) host.setPitchFloor(pitchFloor());
        ui.tuningName = t.name;
        if (t.count() > 0) ui.stringCount = Math.min(t.count(), Ui.MAX_STRINGS);   // chromatic keeps the old row to fade out
        for (int i = 0; i < Ui.MAX_STRINGS; i++) {
            ui.sTunedT[i] = ui.sTargetT[i] = ui.sLockT[i] = 0f;
        }
        respell();
        if (snap) ui.stringsT = t.chromatic() ? 0f : 1f;
        shownState = -1;
    }

    private void respell() {
        Tuning t = tuning();
        boolean flats = t.flats(settings.flats);
        for (int i = 0; i < t.count() && i < Ui.MAX_STRINGS; i++) {
            ui.sLetter[i] = Tuning.letter(t.strings[i], flats);
            ui.sAcc[i] = Tuning.accidental(t.strings[i], flats);
        }
    }

    private void syncSettings(boolean snap) {
        if (!snap && (tracker.a4 != settings.a4 || tracker.tolerance != tolerance())) {
            tracker.reset(true);                   // marks earned against another reference are void
            clearStrum();
            if (tracker.a4 != settings.a4) {
                tracker.a4 = settings.a4;
                host.setPitchFloor(pitchFloor());
            }
        }
        tracker.a4 = settings.a4;
        applyTrackerMode();
        tracker.fast = settings.fast;
        ui.a4Label = "A4 = " + settings.a4 + " Hz";
        float tol = Settings.TOLERANCE[settings.tolIdx];
        ui.tolLabel = String.format(Locale.US, "± %d ¢", Math.round(tol));
        ui.needleLabel = settings.fast ? "Fast" : "Smooth";
        ui.namesLabel = settings.flats ? "Flats" : "Sharps";
        respell();
        if (toneOn) host.playTone(Tuning.freq(toneMidi, settings.a4));
        if (snap) {
            ui.hapticsT = settings.haptics ? 1f : 0f;
            ui.keepOnT = settings.keepOn ? 1f : 0f;
        }
        shownState = -1;
    }

    private void buzz(boolean strong) {
        if (!settings.haptics) return;
        int c;
        if (strong) c = Build.VERSION.SDK_INT >= 30 ? 16 /* CONFIRM */ : HapticFeedbackConstants.VIRTUAL_KEY;
        else c = HapticFeedbackConstants.KEYBOARD_TAP;
        performHapticFeedback(c);
    }

    // ---- accessibility -----------------------------------------------------------------------
    // The whole UI is one custom view; every hit target is a virtual node so TalkBack, Switch
    // Access and keyboard users can reach the controls. The dial is a polite live region that
    // speaks the reading at most every 1.5 s.

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return a11y;
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent e) {
        return a11y.onHover(e) || super.dispatchHoverEvent(e);
    }

    private void a11yAfterFrame(long now) {
        if (am == null || !am.isEnabled()) return;
        a11y.prune();
        int sig = 17;
        for (int i = 0; i < ui.hitCount; i++) sig = sig * 31 + ui.hits[i].id;
        if (page == 1) sig = sig * 31 + Math.round(ui.settingsScroll / ui.dp);
        ViewParent parent = getParent();
        if (sig != hitSig && parent != null) {
            hitSig = sig;
            AccessibilityEvent ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            onInitializeAccessibilityEvent(ev);
            ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
            parent.requestSendAccessibilityEvent(this, ev);
        }
        // The reading is phrased in whole cents, so it changes only when a listener would care.
        int liveId = ui.find(Renderer.H_DIAL) != null ? Renderer.H_DIAL : ui.find(Renderer.H_GUIDE) != null ? Renderer.H_GUIDE : 0;
        String said = liveId == Renderer.H_GUIDE ? gDescription : description;
        if (liveId != 0 && !said.equals(dialSpoken)) {
            if (now - dialSpokenNs > 1_500_000_000L) {
                dialSpoken = said;
                dialSpokenNs = now;
                a11y.send(liveId, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            } else if (running) {
                postInvalidateDelayed(300);            // come back to speak the settled reading
            }
        }
    }

    private String label(int id) {
        if (id >= Renderer.H_GSTRING && id < Renderer.H_GSTRING + Ui.MAX_STRINGS) {
            int i = id - Renderer.H_GSTRING;
            Tuning t = tuning();
            if (i >= t.count()) return "";
            String s = position(i) + ", " + noteName(t.strings[i]);
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
            if ((tracker.tunedMask & (1 << i)) != 0) s += ", tuned";
            return s + (i == gStep && !gComplete ? ", current" : ". Tune this string");
        }
        if (id >= Renderer.H_STRING && id < Renderer.H_STRING + Ui.MAX_STRINGS) {
            int i = id - Renderer.H_STRING;
            Tuning t = tuning();
            if (i >= t.count()) return "";
            String s = "String " + t.stringNumber(i) + ", " + Tuning.spoken(t.strings[i], t.flats(settings.flats));
            if ((tracker.tunedMask & (1 << i)) != 0) s += ", tuned";
            if (toneOn) return s + ". Play its reference tone";
            return s + (tracker.locked == i ? ", locked. Unlock" : ". Lock to this string");
        }
        switch (id) {
            case Renderer.H_THEME_PREV: return "Previous theme. Current theme " + ui.themeName;
            case Renderer.H_THEME_NEXT: return "Next theme. Current theme " + ui.themeName;
            case Renderer.H_SETTINGS: return "Settings";
            case Renderer.H_TONE: return "Reference tone";       // state is the switch's checked flag
            case Renderer.H_PAUSE: return "Listening";
            case Renderer.H_DIAL: return description;
            case Renderer.H_GUIDE: return guideMeterActs() ? gDescription + " Double-tap for the next string." : gDescription;
            case Renderer.H_GUIDE_OPEN: return "Advanced tuner";
            case Renderer.H_SUGGEST: return ui.readPill;
            case Renderer.H_GUIDE_SKIP: return settings.guideAuto ? "Skip this string" : "Next string";
            case Renderer.H_GUIDE_AUTO: return "Switch strings automatically";
            case Renderer.H_GUIDE_AGAIN: return "Check again";
            case Renderer.H_GUIDE_DONE: return "Done";
            case Renderer.H_BACK: return "Back";
            case Renderer.H_MIC: return ui.messageAction;
            case Renderer.H_TUNING_PREV: return "Tuning " + ui.tuningName + ". Previous";
            case Renderer.H_TUNING_NEXT: return "Tuning " + ui.tuningName + ". Next";
            case Renderer.H_A4_PREV: return "Reference pitch A4 " + settings.a4 + " hertz. Lower";
            case Renderer.H_A4_NEXT: return "Reference pitch A4 " + settings.a4 + " hertz. Higher";
            case Renderer.H_TOL_PREV: return "In-tune range plus or minus " + Math.round(Settings.TOLERANCE[settings.tolIdx]) + " cents. Narrower";
            case Renderer.H_TOL_NEXT: return "In-tune range plus or minus " + Math.round(Settings.TOLERANCE[settings.tolIdx]) + " cents. Wider";
            case Renderer.H_NEEDLE_PREV:
            case Renderer.H_NEEDLE_NEXT: return "Needle " + ui.needleLabel + ". Change";
            case Renderer.H_NAMES_PREV:
            case Renderer.H_NAMES_NEXT: return "Note names " + ui.namesLabel + ". Change";
            case Renderer.H_HAPTIC: return "Haptic feedback";
            case Renderer.H_KEEPON: return "Keep screen on";
            default: return "";
        }
    }

    private static boolean isToggle(int id) {
        return id == Renderer.H_HAPTIC || id == Renderer.H_KEEPON || id == Renderer.H_TONE || id == Renderer.H_PAUSE
                || id == Renderer.H_GUIDE_AUTO || id == Renderer.H_GUIDE_OPEN;
    }

    private boolean checked(int id) {
        switch (id) {
            case Renderer.H_HAPTIC: return settings.haptics;
            case Renderer.H_KEEPON: return settings.keepOn;
            case Renderer.H_TONE: return toneOn;
            case Renderer.H_PAUSE: return !paused;
            case Renderer.H_GUIDE_AUTO: return settings.guideAuto;
            case Renderer.H_GUIDE_OPEN: return !guideMode();
            default: return false;
        }
    }

    private final class A11y extends AccessibilityNodeProvider {
        private static final int NONE = Integer.MIN_VALUE;
        private int focused = NONE, hovered = NONE;
        private final Rect r = new Rect();
        private final int[] loc = new int[2];

        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(TunerView.this);
                onInitializeAccessibilityNodeInfo(info);
                for (int i = 0; i < ui.hitCount; i++) info.addChild(TunerView.this, ui.hits[i].id);
                if (scrollable()) {
                    info.setClassName("android.widget.ScrollView");
                    info.setScrollable(true);
                    if (ui.settingsScroll > 0f) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                    }
                    if (ui.settingsScroll < ui.settingsMaxScroll) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    }
                }
                return info;
            }
            Ui.Hit h = ui.find(id);
            if (h == null) return null;
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(TunerView.this, id);
            info.setPackageName(getContext().getPackageName());
            boolean dial = id == Renderer.H_DIAL || id == Renderer.H_GUIDE;
            boolean toggle = isToggle(id);
            info.setClassName(dial ? "android.widget.TextView" : toggle ? "android.widget.Switch" : "android.widget.Button");
            if (id == Renderer.H_GUIDE && guideMeterActs()) {
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
            info.setContentDescription(label(id));
            if (toggle) {
                info.setCheckable(true);
                info.setChecked(checked(id));
            }
            info.setParent(TunerView.this);
            r.set(Math.round(h.rect.left), Math.round(h.rect.top), Math.round(h.rect.right), Math.round(h.rect.bottom));
            info.setBoundsInParent(r);
            getLocationOnScreen(loc);
            r.offset(loc[0], loc[1]);
            info.setBoundsInScreen(r);
            info.setVisibleToUser(true);
            info.setEnabled(true);
            info.setFocusable(true);
            if (dial) {
                info.setLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            } else {
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
            if (focused == id) {
                info.setAccessibilityFocused(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            } else {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
            }
            return info;
        }

        @Override
        public boolean performAction(int id, int action, Bundle args) {
            if (id == HOST_VIEW_ID) {
                if (scrollable() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    float pageH = Math.max(48f * ui.dp, (ui.height - ui.insetB - ui.insetT - 56f * ui.dp) * 0.8f);
                    float to = clamp(ui.settingsScroll
                            + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? pageH : -pageH),
                            0f, ui.settingsMaxScroll);
                    if (to == ui.settingsScroll) return false;
                    ui.settingsScroll = to;
                    invalidate();
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                    return true;
                }
                return performAccessibilityAction(action, args);
            }
            if (ui.find(id) == null) return false;
            switch (action) {
                case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                    if (focused != id) {
                        int prev = focused;
                        focused = id;
                        if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                        send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    }
                    return true;
                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (focused == id) {
                        focused = NONE;
                        send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    }
                    return true;
                case AccessibilityNodeInfo.ACTION_CLICK:
                    if (id == Renderer.H_DIAL || id == Renderer.H_GUIDE && !guideMeterActs()) return false;
                    action(id);
                    send(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    return true;
                default:
                    return false;
            }
        }

        private boolean scrollable() {
            return page == 1 && ui.settingsMaxScroll > 0f;
        }

        void prune() {
            if (focused != NONE && ui.find(focused) == null) focused = NONE;
            if (hovered != NONE && ui.find(hovered) == null) hovered = NONE;
        }

        boolean onHover(MotionEvent e) {
            if (am == null || !am.isEnabled() || !am.isTouchExplorationEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    int id = ui.hitAt(e.getX(), e.getY());
                    setHovered(id == 0 ? NONE : id);
                    return id != 0;
                }
                case MotionEvent.ACTION_HOVER_EXIT:
                    if (hovered != NONE) {
                        setHovered(NONE);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private void setHovered(int id) {
            if (id == hovered) return;
            int prev = hovered;
            hovered = id;
            if (id != NONE) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        }

        void send(int id, int type) {
            if (am == null || !am.isEnabled()) return;
            ViewParent parent = getParent();
            if (parent == null) return;
            AccessibilityEvent ev = AccessibilityEvent.obtain(type);
            ev.setPackageName(getContext().getPackageName());
            boolean toggle = isToggle(id);
            ev.setClassName(id == Renderer.H_DIAL || id == Renderer.H_GUIDE ? "android.widget.TextView"
                    : toggle ? "android.widget.Switch" : "android.widget.Button");
            ev.setSource(TunerView.this, id);
            ev.setEnabled(true);
            ev.setContentDescription(label(id));
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION);
            }
            if (toggle) ev.setChecked(checked(id));
            parent.requestSendAccessibilityEvent(TunerView.this, ev);
        }
    }

    // ---- math --------------------------------------------------------------------------------

    private static float approach(float v, float target, float dt, float tau) {
        float n = target + (v - target) * (float) Math.exp(-dt / tau);
        return Math.abs(n - target) < 0.002f ? target : n;
    }

    private static float ease(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static int wrapIdx(int i, int n) {
        return ((i % n) + n) % n;
    }
}
