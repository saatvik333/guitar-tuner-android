package me.saatvik.tuner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import me.saatvik.hairline.Access;
import me.saatvik.hairline.Chrome;
import me.saatvik.hairline.Hits;
import me.saatvik.hairline.Kit;
import me.saatvik.hairline.Motion;
import me.saatvik.hairline.Palette;
import me.saatvik.hairline.Themes;

/**
 * Owns animation state and input; delegates drawing to {@link Renderer}, accessibility and keys to
 * {@link Access}. Pitch frames arrive from the capture thread through {@link #offer} and are
 * drained on the UI thread into the {@link Tracker}; a frame is drawn only when something visible changed.
 */
final class TunerView extends View implements Access.Nodes {

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
    private final Access access = new Access(this, ui.hits, this);
    private final Rect insets = new Rect();

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
    private final Guide guide = new Guide();
    private int gWordsKey = -1;                           // what the title, sub-line and caption were built for
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

    private int downId, repeatId;
    private boolean repeated;
    private float downX, downY, lastY;
    private boolean scrolling, multi;

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
    // posts from the capture and strum threads run on the main looper whether or not the view is attached
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
            if (repeatId == 0 || ui.hits.pressed != repeatId) return;
            repeated = true;
            activate(repeatId);
            postDelayed(this, 70);
        }
    };

    TunerView(Context ctx, Renderer renderer, Settings settings, Palette[] themes, Host host) {
        super(ctx);
        this.renderer = renderer;
        this.settings = settings;
        this.themes = themes;
        this.host = host;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        ui.dp = dm.density;
        // the font-size setting (non-linear from Android 14), up to the design system's cap
        ui.sp = Math.min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm), dm.density * Kit.MAX_FONT_SCALE);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        themeIdx = Themes.indexOf(themes, settings.theme);
        palFrom = palTo = themes[themeIdx];
        ui.pal = palTo;
        ui.themes = themes;
        ui.themeIdx = themeIdx;
        ui.tolOpts = new String[Settings.TOLERANCE.length];
        for (int i = 0; i < ui.tolOpts.length; i++) ui.tolOpts[i] = String.format(Locale.US, "±%d ¢", Math.round(Settings.TOLERANCE[i]));
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

    /** For recreation (rotation, process death): the tab, its progress and the pause. */
    void saveState(Bundle out) {
        out.putBoolean("paused", paused);
        out.putInt("page", page);
        out.putInt("basePage", basePage);
        out.putString("tuning", tuning().name);
        out.putInt("tuned", tracker.tunedMask);
        out.putInt("locked", tracker.locked);
        out.putInt("step", gStep);
        out.putBoolean("complete", gComplete);
    }

    /** Right after construction, which lands on Easy: back to where {@link #saveState} left off, unannounced. */
    void restoreState(Bundle in) {
        paused = in.getBoolean("paused");
        if (in.getInt("basePage", PAGE_GUIDE) == PAGE_TUNER) {
            // landing on Easy turned a chromatic tuning into Standard: Advanced gets its own back
            int idx = Tuning.indexOf(in.getString("tuning", settings.tuning));
            if (idx != tuningIdx) {
                tuningIdx = idx;
                settings.tuning = tuning().name;
                applyTuning(true);
            }
            page = basePage = PAGE_TUNER;
            ui.guideT = 0f;
            applyTrackerMode();
            int locked = in.getInt("locked", -1);
            tracker.locked = locked < tuning().count() ? locked : -1;
        }
        int n = tuning().count();
        tracker.tunedMask = in.getInt("tuned") & ((1 << n) - 1);
        for (int i = 0; i < Ui.MAX_STRINGS; i++) ui.sTunedT[i] = (tracker.tunedMask & (1 << i)) != 0 ? 1f : 0f;
        if (basePage == PAGE_GUIDE) {
            int step = in.getInt("step");
            gStep = step < n ? step : 0;
            gComplete = in.getBoolean("complete") && n > 0;
            guideReset(0.0);
            ui.gCursor = gStep;
            ui.gDoneT = gComplete ? 1.2f : 0f;
        }
        if (in.getInt("page", basePage) == PAGE_SETTINGS) {
            page = PAGE_SETTINGS;
            ui.pageT = 1f;
        }
    }

    // ---- microphone ------------------------------------------------------------------------

    void setMic(int state) {
        if (state == mic) return;
        mic = state;
        shownState = -1;
        if (state != MIC_OK) announce(micMessage());
        invalidate();
    }

    /** A permission request was refused: said again even when the message is already showing. */
    void micRefused(int state) {
        if (state != mic) setMic(state);
        else announce(micMessage());
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
        int strumSig = strumState(nowNs);
        for (int k = 0; k < n; k++) {
            float[] strum = dStrum[k];
            dStrum[k] = null;                        // ~0.7 s of audio: not held until the slot is reused
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
            if (strum != null && !ignore) {
                strumAt = dT[k];                     // later plucks than this clear what it shows
                analyseStrum(strum, fsNow);
            }
            tracker.update(dT[k], v, v ? dF[k] : Double.NaN, dOn[k] && !ignore);
            // a single string again: back to tuning it; an analysis under way is left to finish
            if (v && (strumResult != null || nowNs < chordUntilNs || !strumBusy && strumPending(nowNs))
                    && tracker.lastOnset > strumAt + 0.05) {
                clearStrum();
            }
        }
        if (guide) guideWatch();
        boolean strumChanged = strumState(nowNs) != strumSig;
        boolean nowSilent = !paused && sil >= SILENT_SEC;
        boolean changed = nowSilent != silent || trackerSig() != sig || Math.abs(tracker.cents - cents0) > 0.01 || strumChanged;
        boolean silenceStarts = nowSilent && !silent;
        silent = nowSilent;
        if (silenceStarts && mic == MIC_OK) announce(micMessage());
        int ev = tracker.takeEvents();
        if ((ev & Tracker.EV_IN_TUNE) != 0 && !ignore) {
            if (!tabsCovered()) ui.pulseT = 0f;     // a pulse played after settings closes would mark nothing
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
        // under settings the tabs are not drawn: they catch up when it slides away
        if (changed && running && !tabsCovered()) invalidate();
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

    private int strumState(long now) {
        return (strumResult != null ? 1 : 0) + (now < chordUntilNs ? 2 : 0)
                + (strumPending(now) ? 4 : 0) + 8 * strumToken;
    }

    /** Several strings were heard: their analysis is under way or their audio still to come. */
    private boolean strumPending(long now) {
        return strumBusy || now - strumHeardNs < STRUM_WAIT_NS;
    }

    /** A strum is on show: its result, "several notes", or "checking every string". */
    private boolean strumShown(long now) {
        return strumResult != null || now < chordUntilNs || strumPending(now);
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

    /** Closes settings, stops the reference tone, or goes from Advanced to Easy; false on Easy. Escape and system back. */
    @Override
    public boolean back() {
        if (page == PAGE_SETTINGS) {
            closeSettings();
            invalidate();
            return true;
        }
        if (toneOn) {
            stopTone();
            return true;
        }
        if (page == PAGE_TUNER) {
            enterGuide(false);                          // Advanced backs out to Easy, the home tab
            return true;
        }
        return false;
    }

    /** Back to the tab settings opened over; keyboard focus returns to the settings button. */
    private void closeSettings() {
        page = basePage;
        access.pageChanged(Renderer.H_SETTINGS);
        announce(page == PAGE_GUIDE ? "Easy tuner" : "Advanced tuner");
    }

    // ---- layout ------------------------------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        Chrome.insets(in, insets);
        ui.insetL = insets.left;
        ui.insetT = insets.top;
        ui.insetR = insets.right;
        ui.insetB = insets.bottom;
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
        access.afterFrame();
        // The reading is phrased in whole cents, so it changes only when a listener would care.
        int live = ui.hits.find(Renderer.H_DIAL) != null ? Renderer.H_DIAL
                : ui.hits.find(Renderer.H_GUIDE) != null ? Renderer.H_GUIDE : 0;
        boolean unsaid = access.live(live, live == Renderer.H_GUIDE ? gDescription : description, now);
        if (unsaid && running) postInvalidateDelayed(300);   // come back to say the settled reading
        if (running && animating) postInvalidateOnAnimation();
        else lastNs = 0;                   // next frame after an idle gap starts with a nominal dt
    }

    private boolean step(float dt, long now) {
        boolean anim = false;                    // settings, and the move between pages
        boolean tabAnim = false;                 // drawn only while a tab shows

        if (palT < 1f) {
            palT = Math.min(1f, palT + dt / Motion.THEME);
            ui.pal = Palette.lerp(palFrom, palTo, Motion.ease(palT));
            anim = true;
        }
        float ps = page == PAGE_SETTINGS ? 1f : 0f, pg = guideMode() ? 1f : 0f;
        ui.pageT = Motion.approach(ui.pageT, ps, dt, Motion.PAGE);
        ui.guideT = Motion.approach(ui.guideT, pg, dt, Motion.PAGE);
        anim |= ui.pageT != ps || ui.guideT != pg;
        access.list = page == PAGE_SETTINGS ? ui.page : null;
        host.setBackIntercept(page != PAGE_GUIDE || toneOn);           // host no-ops if unchanged
        ui.time = (now - t0Ns) * 1e-9f;

        boolean strumView = strumResult != null && strumResult.suggest < 0 && !guideMode();
        boolean overlay = !guideMode() && strumShown(now);
        int worst = strumView ? strumWorst() : -1;
        boolean strumBead = worst >= 0 && Math.abs(strumResult.cents[worst]) > strumOk();
        boolean showing = (overlay ? strumBead : tracker.hasNote) && !toneOn && !paused && mic == MIC_OK && !silent;
        float nt = showing ? 1f : 0f, lt = showing && tracker.live && !strumView ? 1f : 0f;
        float tt = showing && !strumView && (tracker.live && tracker.inTune || guideMode() && gAdvancing) ? 1f : 0f;
        ui.noteT = Motion.approach(ui.noteT, nt, dt, 0.12f);
        ui.liveT = Motion.approach(ui.liveT, lt, dt, 0.10f);
        ui.tuneT = Motion.approach(ui.tuneT, tt, dt, 0.07f);
        tabAnim |= ui.noteT != nt || ui.liveT != lt || ui.tuneT != tt;

        float target = strumBead ? Renderer.beadOf(strumResult.cents[worst])
                : !tracker.hasNote ? 0f
                : guideMode() ? Renderer.guideBeadOf(tracker.cents)
                : Renderer.beadOf(tracker.cents);
        if (ui.noteT == 0f) {
            ui.bead = target;
            beadV = 0f;
        } else {
            tabAnim |= spring(target, dt);
        }
        if (ui.pulseT >= 0f) {
            ui.pulseT += dt / 0.7f;
            if (ui.pulseT > 1f) ui.pulseT = -1f;
            tabAnim = true;
        }

        float st = tuning().chromatic() ? 0f : 1f;
        ui.stringsT = Motion.approach(ui.stringsT, st, dt, 0.09f);
        tabAnim |= ui.stringsT != st;
        int tgt = toneOn ? toneString : strumView ? worst : tracker.hasNote ? tracker.string : -1;
        float sv = strumView ? 1f : 0f;
        ui.strumT = Motion.approach(ui.strumT, sv, dt, 0.1f);
        tabAnim |= ui.strumT != sv;
        ui.strumOk = (float) strumOk();
        for (int i = 0; i < ui.stringCount; i++) {
            ui.sStrum[i] = strumView && i < strumResult.count ? (float) strumResult.cents[i] : ui.sStrum[i];
        }
        for (int i = 0; i < ui.stringCount; i++) {
            float a = (tracker.tunedMask & (1 << i)) != 0 ? 1f : 0f;
            float b = i == tgt ? (toneOn || (tracker.live && !paused) ? 1f : 0.5f) : 0f;
            float k = tracker.locked == i ? 1f : 0f;
            ui.sTunedT[i] = Motion.approach(ui.sTunedT[i], a, dt, 0.08f);
            ui.sTargetT[i] = Motion.approach(ui.sTargetT[i], b, dt, 0.08f);
            ui.sLockT[i] = Motion.approach(ui.sLockT[i], k, dt, 0.08f);
            tabAnim |= ui.sTunedT[i] != a || ui.sTargetT[i] != b || ui.sLockT[i] != k;
        }

        float h = settings.haptics ? 1f : 0f, ko = settings.keepOn ? 1f : 0f;
        ui.hapticsT = Motion.approach(ui.hapticsT, h, dt, Motion.CONTROL);
        ui.keepOnT = Motion.approach(ui.keepOnT, ko, dt, Motion.CONTROL);
        ui.tolPos = Motion.approach(ui.tolPos, ui.tolIdx, dt, Motion.CONTROL);
        ui.needlePos = Motion.approach(ui.needlePos, ui.needle, dt, Motion.CONTROL);
        ui.namesPos = Motion.approach(ui.namesPos, ui.names, dt, Motion.CONTROL);
        anim |= ui.hapticsT != h || ui.keepOnT != ko || ui.tolPos != ui.tolIdx || ui.needlePos != ui.needle
                || ui.namesPos != ui.names;

        ui.toneOn = toneOn;
        ui.paused = paused;
        updateReadout(now);
        if (now < chordUntilNs || strumPending(now)) tabAnim = true;      // shown until a moment passes
        if (ui.guideT > 0.001f) tabAnim |= stepGuide(dt, now);

        // the screen stays on only while it is useful: tuning or a reference tone
        boolean awake = settings.keepOn && page != PAGE_SETTINGS && (toneOn || (!paused && mic == MIC_OK && !silent));
        if (awake != keepOn) {
            keepOn = awake;
            setKeepScreenOn(awake);
        }
        // under settings the tabs are not drawn: their motion does not keep frames coming
        return anim || tabAnim && !tabsCovered();
    }

    /** Settings covers both tabs: nothing they show is visible. */
    private boolean tabsCovered() {
        return ui.pageT >= 1f;
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

    private void updateReadout(long now) {
        int state;
        // state = class * 16 + detail; the tone comes before the pause: it also plays while paused
        if (mic != MIC_OK) state = ST_MESSAGE * 16 + mic;
        else if (toneOn) state = ST_TONE * 16;
        else if (paused) state = ST_PAUSED * 16;
        else if (silent) state = ST_MESSAGE * 16 + 7;
        else if (strumResult != null) state = ST_STRUM * 16 + (strumToken & 15);
        else if (now < chordUntilNs) state = ST_CHORD * 16;
        else if (strumPending(now)) state = ST_STRUMMING * 16;
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

    // What is said here are moments (reaching pitch, a tab or mode switched, a refusal), not text
    // left on screen to re-read; the readings themselves are the throttled live region.
    private void announce(String s) {
        access.announce(s);
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
                scrolling = multi = repeated = false;
                ui.hits.focused = 0;                   // touch hides the keyboard focus ring
                downId = ui.hits.at(x, y);
                ui.hits.pressed = downId;
                if (ui.page.repeats(downId)) {         // a finger held on a stepper chevron steps on
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
                if (!scrolling && !repeated && page == PAGE_SETTINGS && ui.page.maxScroll > 0f
                        && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    scrolling = true;
                }
                if (scrolling && ui.page.scrollBy(lastY - y)) invalidate();
                lastY = y;
                if (ui.hits.pressed != 0 && (scrolling || ui.hits.at(x, y) != downId)) {
                    ui.hits.pressed = 0;
                    repeatId = 0;
                    removeCallbacks(repeater);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(repeater);
                ui.hits.pressed = 0;
                if (!multi && !scrolling && !repeated && downId != 0 && ui.hits.at(x, y) == downId) activate(downId);
                scrolling = multi = repeated = false;
                downId = repeatId = 0;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelPress();
                scrolling = multi = false;
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
        clearStrum();                                       // a strum heard on Advanced would hold "One string" here
        page = basePage = PAGE_GUIDE;
        int n = tuning().count(), first = -1;
        for (int i = 0; i < n && first < 0; i++) if ((tracker.tunedMask & (1 << i)) == 0) first = i;
        gComplete = n > 0 && first < 0;
        if (!gComplete) gStep = Math.max(0, first);
        guideReset(0.0);
        ui.gCursor = gStep;
        ui.gDoneT = gComplete ? 1.2f : 0f;
        if (snap) ui.guideT = 1f;
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
        guide.reset();
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
        announce("Switched to the " + Guide.position(t, best));
        return true;
    }

    private String guideTitle() {
        return Guide.title(tuning(), gStep, gComplete, tracker.tunedMask);
    }

    /** Status of the current step; returns true when the words changed. */
    private boolean guideStatus(long now) {
        boolean unheard = now - gLastHeardNs > 6_000_000_000L && mic == MIC_OK && !silent;
        if (!guide.update(tracker, gStep, gComplete, toneOn, paused, gAdvancing, strumShown(now), unheard, gWrongWay)) {
            return false;
        }
        int st = guide.status;
        if (st == Guide.UNHEARD) gUnheardShown = true;
        if (st == Guide.WRONG_WAY && ui.gShakeT < 0f) ui.gShakeT = 0f;
        String word = Guide.word(st), detail = Guide.detail(st, guide.arg, tuning(), gStep, settings.guideAuto);
        if (!word.equals(ui.gWord) || !detail.equals(ui.gDetail)) {
            ui.gWordPrev = ui.gWord;
            ui.gDetailPrev = ui.gDetail;
            ui.gWord = word;
            ui.gDetail = detail;
            ui.gWordT = 0f;
        }
        ui.gWordAccent = st == Guide.TUNED;
        return true;
    }

    /** Words and motion of the guided page. Returns true while anything moves (always, when shown). */
    private boolean stepGuide(float dt, long now) {
        Tuning t = tuning();
        if (t.chromatic() || gStep >= t.count()) {
            // Easy still fading out while Advanced selects a tuning it cannot show (Chromatic, or
            // fewer strings than the step): keep the last words, finish the fade.
            return running && ui.guideT > 0.001f;
        }
        ui.guideDone = gComplete;
        ui.gStep = gStep;
        ui.gAuto = settings.guideAuto;
        boolean flats = t.flats(settings.flats);
        // everything the title, sub-line and caption say: rebuilt when it changes, not every frame
        int key = (((tuningIdx * Ui.MAX_STRINGS + gStep) * 2 + (gComplete ? 1 : 0)) * 2 + (flats ? 1 : 0)) * 16
                + Integer.bitCount(tracker.tunedMask);
        boolean words = false;
        if (key != gWordsKey) {
            gWordsKey = key;
            int n = Math.min(t.count(), Ui.MAX_STRINGS);
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                lo = Math.min(lo, t.strings[i]);
                hi = Math.max(hi, t.strings[i]);
            }
            for (int i = 0; i < n; i++) ui.gThick[i] = hi > lo ? (hi - t.strings[i]) / (float) (hi - lo) : 0.5f;
            String title = guideTitle(), sub = Guide.sub(t, gStep, gComplete, tracker.tunedMask, flats);
            if (!title.equals(ui.gTitle) || !sub.equals(ui.gSub)) {
                ui.gTitlePrev = ui.gTitle;
                ui.gSubPrev = ui.gSub;
                ui.gTitle = title;
                ui.gSub = sub;
                ui.gTitleT = ui.gTitlePrev.length() == 0 ? 1f : 0f;
                words = true;
            }
            ui.gCaption = Guide.caption(t, gStep, gComplete);
        }
        words |= guideStatus(now);
        if (words) {
            gDescription = gComplete ? ui.gTitle + ". " + ui.gSub
                    : (ui.gTitle + ". " + (ui.gWord.length() > 0 ? ui.gWord + ". " : "") + ui.gDetail).trim();
        }

        ui.gTitleT = Math.min(1f, ui.gTitleT + dt / 0.35f);
        ui.gWordT = Math.min(1f, ui.gWordT + dt / 0.25f);
        ui.gCursor = Motion.approach(ui.gCursor, gStep, dt, 0.09f);
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
        ui.gVib = Motion.approach(ui.gVib, live ? 1f : 0f, dt, live ? 0.05f : 0.4f);
        float sag = tracker.hasNote && !gComplete ? (float) Math.max(0.0, Math.min(1.0, -tracker.cents / 150.0)) : 0f;
        ui.gSag = Motion.approach(ui.gSag, sag, dt, 0.15f);
        int gs = guide.status;
        boolean direction = gs == Guide.TIGHTEN || gs == Guide.LOOSEN || gs == Guide.WRONG_WAY || gs == Guide.ALMOST;
        float chev = live && !gAdvancing && direction && Math.abs(tracker.cents) > tracker.tolerance ? 1f : 0f;
        ui.gChevT = Motion.approach(ui.gChevT, chev, dt, 0.12f);
        if (ui.gShakeT >= 0f) {
            ui.gShakeT += dt / 0.5f;
            if (ui.gShakeT >= 1f) ui.gShakeT = -1f;
        }
        if (gComplete) ui.gDoneT = Math.min(1.2f, ui.gDoneT + dt / 0.8f);
        boolean moving = ui.gPluckT >= 0f || ui.gVib > 0f || ui.gChevT > 0f || ui.gShakeT >= 0f
                || ui.gTitleT < 1f || ui.gWordT < 1f || ui.gCursor != gStep || ui.gSag != sag
                || gComplete && ui.gDoneT < 1.2f
                || waiting && !gUnheardShown && now - gLastHeardNs < 6_500_000_000L;   // for "not hearing it"
        return running && moving;
    }

    private static final float DEMO_S = 3 * 2.8f;

    // ---- keyboard and D-pad: Access moves a focus ring over the targets ------------------------

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        access.onFocusChanged(gained);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        return access.onKeyDown(code, e) || super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        return access.onKeyUp(code, e) || super.onKeyUp(code, e);
    }

    private void cancelPress() {
        removeCallbacks(repeater);
        ui.hits.pressed = 0;
        downId = repeatId = 0;
        scrolling = false;
        invalidate();
    }

    @Override
    public void activate(int id) {
        if (!acts(id)) return;                            // a tap on text: no buzz either
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
            case Renderer.H_SETTINGS:
                if (page != PAGE_SETTINGS) basePage = page;
                page = PAGE_SETTINGS;
                ui.page.scroll = 0f;
                access.pageChanged(0);
                announce("Settings");
                break;
            case Renderer.H_BACK: closeSettings(); break;
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
            case Renderer.H_GUIDE: guideNext((tracker.tunedMask & (1 << gStep)) != 0); break;
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
                guide.reset();                               // the words depend on the mode
                announce(settings.guideAuto ? "Strings switch automatically" : "Strings switch when you tap Next");
                changed = true;
                break;
            case Renderer.H_GUIDE_AGAIN:
                tracker.tunedMask = 0;
                guideJump(0);
                break;
            // Advanced only: a tap landing in the moment Easy fades in must not change the tuning
            case Renderer.H_TUNING_PREV: if (!guideMode()) setTuning(tuningIdx - 1); changed = true; break;
            case Renderer.H_TUNING_NEXT: if (!guideMode()) setTuning(tuningIdx + 1); changed = true; break;
            case Renderer.H_A4:
                if (settings.a4 > Settings.A4_MIN) settings.a4--;
                changed = true;
                break;
            case Renderer.H_A4 + 1:
                if (settings.a4 < Settings.A4_MAX) settings.a4++;
                changed = true;
                break;
            case Renderer.H_HAPTIC: settings.haptics = !settings.haptics; changed = true; break;
            case Renderer.H_KEEPON: settings.keepOn = !settings.keepOn; changed = true; break;
            default:
                if (id >= Renderer.H_TOL && id < Renderer.H_TOL + Settings.TOLERANCE.length) {
                    settings.tolIdx = id - Renderer.H_TOL;
                } else if (id >= Renderer.H_NEEDLE && id < Renderer.H_NEEDLE + Renderer.NEEDLES.length) {
                    settings.fast = id - Renderer.H_NEEDLE == 1;
                } else if (id >= Renderer.H_NAMES && id < Renderer.H_NAMES + Renderer.NAMES.length) {
                    settings.flats = id - Renderer.H_NAMES == 1;
                } else if (id >= Renderer.H_THEME && id < Renderer.H_THEME + themes.length) {
                    setTheme(id - Renderer.H_THEME);
                    break;
                } else {
                    return;
                }
                changed = true;
                break;
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

    /** Stops the reference tone if it plays; readings resume after its ring-out. */
    void stopTone() {
        if (!toneOn) return;
        toneOn = false;
        toneString = -1;
        host.stopTone();
        ignoreUntilNs = System.nanoTime() + TONE_TAIL_NS;
        tracker.reset(false);
        gLastHeardNs = System.nanoTime();             // nothing was listened to while it played
        invalidate();
    }

    private void setTheme(int idx) {
        if (idx == themeIdx) return;
        themeIdx = idx;
        palFrom = ui.pal;
        palTo = themes[themeIdx];
        palT = 0f;
        ui.themeIdx = themeIdx;
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
        ui.tolIdx = settings.tolIdx;
        ui.needle = settings.fast ? 1 : 0;
        ui.names = settings.flats ? 1 : 0;
        ui.haptics = settings.haptics;
        ui.keepOn = settings.keepOn;
        respell();
        if (toneOn) host.playTone(Tuning.freq(toneMidi, settings.a4));
        if (snap) {
            ui.hapticsT = settings.haptics ? 1f : 0f;
            ui.keepOnT = settings.keepOn ? 1f : 0f;
            ui.tolPos = ui.tolIdx;
            ui.needlePos = ui.needle;
            ui.namesPos = ui.names;
        }
        shownState = -1;
    }

    private void buzz(boolean strong) {
        if (!settings.haptics) return;
        int c;
        if (strong) c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
        else c = HapticFeedbackConstants.KEYBOARD_TAP;
        performHapticFeedback(c);
    }

    // ---- accessibility -----------------------------------------------------------------------
    // The whole UI is one custom view: every target is a virtual node (Access), so TalkBack, Switch
    // Access and keyboard users reach the controls. The dial, or the guide's meter, is a polite
    // live region that says the reading at most every 1.5 s (onDraw).

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return access;
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent e) {
        return access.onHover(e) || super.dispatchHoverEvent(e);
    }

    @Override
    public String label(int id) {
        if (id >= Renderer.H_GSTRING && id < Renderer.H_GSTRING + Ui.MAX_STRINGS) {
            int i = id - Renderer.H_GSTRING;
            Tuning t = tuning();
            if (i >= t.count()) return "";
            String s = Guide.position(t, i) + ", " + Guide.noteName(t.strings[i], t.flats(settings.flats));
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
        // the symbols on screen, in words: "±2 ¢" and "Hz" are not reliably spoken
        if (id >= Renderer.H_TOL && id < Renderer.H_TOL + Settings.TOLERANCE.length) {
            int c = Math.round(Settings.TOLERANCE[id - Renderer.H_TOL]);
            return "In-tune range, plus or minus " + c + (c == 1 ? " cent" : " cents");
        }
        switch (id) {
            case Renderer.H_SETTINGS: return "Settings";
            case Renderer.H_TONE: return "Reference tone";       // state is the switch's checked flag
            case Renderer.H_PAUSE: return "Listening";
            case Renderer.H_DIAL: return description;
            case Renderer.H_GUIDE: return gDescription;
            case Renderer.H_GUIDE_OPEN: return "Advanced tuner";
            case Renderer.H_SUGGEST: return ui.readPill;
            case Renderer.H_GUIDE_SKIP: return settings.guideAuto ? "Skip this string" : "Next string";
            case Renderer.H_GUIDE_AUTO: return "Switch strings automatically";
            case Renderer.H_GUIDE_AGAIN: return "Check again";
            case Renderer.H_BACK: return "Back";
            case Renderer.H_MIC: return ui.messageAction.length() > 0 ? ui.message + ". " + ui.messageAction : ui.message;
            case Renderer.H_TUNING_PREV: return "Tuning " + ui.tuningName + ". Previous";
            case Renderer.H_TUNING_NEXT: return "Tuning " + ui.tuningName + ". Next";
            case Renderer.H_A4: return "Reference pitch, A4 " + settings.a4 + " hertz. Lower";
            case Renderer.H_A4 + 1: return "Reference pitch, A4 " + settings.a4 + " hertz. Higher";
            default: return ui.page.label(id);
        }
    }

    @Override
    public int role(int id) {
        int r = ui.page.role(id);
        if (r >= 0) return r;
        switch (id) {
            case Renderer.H_TONE:
            case Renderer.H_PAUSE:
            case Renderer.H_GUIDE_AUTO:
            case Renderer.H_GUIDE_OPEN: return Hits.SWITCH;
            case Renderer.H_GUIDE: return Hits.TEXT;      // the advice; what a tap does is its click label
            default: return acts(id) ? Hits.BUTTON : Hits.TEXT;
        }
    }

    @Override
    public boolean checked(int id) {
        switch (id) {
            case Renderer.H_TONE: return toneOn;
            case Renderer.H_PAUSE: return !paused;
            case Renderer.H_GUIDE_AUTO: return settings.guideAuto;
            case Renderer.H_GUIDE_OPEN: return !guideMode();
            default: return ui.page.checked(id);
        }
    }

    /**
     * The node does something when activated. The dial and a message without a button only read.
     * The guide's meter is a button when a tap has something to do: with Auto off and the string
     * tuned, Next; in windows too short for the Skip / Auto row, that row's Next / Skip.
     */
    @Override
    public boolean acts(int id) {
        if (id == Renderer.H_DIAL) return false;
        if (id == Renderer.H_MIC) return ui.messageAction.length() > 0;
        if (id == Renderer.H_GUIDE) {
            return guideMode() && !gComplete && (!ui.gPills || !settings.guideAuto && (tracker.tunedMask & (1 << gStep)) != 0);
        }
        return true;
    }

    @Override
    public CharSequence clickLabel(int id) {
        if (id != Renderer.H_GUIDE) return null;
        return settings.guideAuto && (tracker.tunedMask & (1 << gStep)) == 0 ? "Skip this string" : "Next string";
    }

    @Override
    public boolean repeats(int id) {
        return ui.page.repeats(id);
    }

    // ---- math --------------------------------------------------------------------------------

    private static int wrapIdx(int i, int n) {
        return ((i % n) + n) % n;
    }
}
