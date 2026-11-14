package me.saatvik.tuner;

import android.graphics.Canvas;
import android.graphics.Typeface;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

import me.saatvik.hairline.Palette;

/**
 * Renders the production Renderer on the JVM (AWT-backed android.graphics stubs from
 * hairline/preview) for visual review: a 412 x 914 dp phone, plus small windows (split screen,
 * narrow phone at large font, landscape), the keyboard focus ring, the settings page in every
 * theme, the system font at 2x and the system's high-contrast setting.
 */
public final class Preview {
    static final float DENSITY = 2.625f;
    /** The built-in themes and a Wallpaper theme as Android 12+ adds it (Material You's baseline colours). */
    static final Palette[] THEMES = new Palette[Palette.BUILT_IN.length + 1];
    static {
        System.arraycopy(Palette.BUILT_IN, 0, THEMES, 0, Palette.BUILT_IN.length);
        THEMES[THEMES.length - 1] = new Palette("Wallpaper", false, 0xFFFDF8FD, 0xFF1D1B20, 0xFF605D66,
                0xFFE7E0EB, 0xFF6750A4, 0xFFFFFFFF);
    }

    public static void main(String[] args) throws Exception {
        String fonts = args[0], out = args[1];
        new File(out).mkdirs();
        Renderer r = new Renderer(Typeface.fromFile(fonts + "/rubik_300.ttf"), Typeface.fromFile(fonts + "/rubik_400.ttf"));

        Ui u = phone("Aurum", "Standard");
        live(u, 'E', 0, "2", 12.4f, "String 6", "Tune down", "82.82 Hz");
        u.sTargetT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        shot(r, u, out + "/1_aurum_live.png");

        u = phone("Aurum", "Standard");
        live(u, 'A', 0, "2", 0.4f, "String 5", "In tune", "110.03 Hz");
        u.tuneT = 1f; u.pulseT = 0.35f; u.hintAccent = true;
        u.sTargetT[1] = 1f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        shot(r, u, out + "/2_aurum_in_tune.png");

        u = phone("Celeste", "Chromatic");
        live(u, 'C', 1, "4", -23f, "Chromatic", "Tune up", "258.29 Hz");
        shot(r, u, out + "/3_celeste_chromatic.png");

        u = phone("Rosa", "Half step down");
        live(u, 'E', 2, "2", -5.2f, "String 6", "Tune up", "77.55 Hz");
        u.sTargetT[0] = 1f;
        shot(r, u, out + "/4_rosa_flat.png");

        u = phone("Notte", "Standard");
        live(u, 'G', 0, "3", 3.1f, "String 3", "", "196.35 Hz");
        u.liveT = 0f; u.sTargetT[3] = 0.5f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        shot(r, u, out + "/5_notte_held.png");

        u = phone("Salvia", "Standard");
        u.message = "Microphone access needed"; u.messageAction = "Allow";
        shot(r, u, out + "/6_salvia_permission.png");

        u = phone("Ambra", "Drop D");
        u.caption = "Listening"; u.hint = "Play a string";
        shot(r, u, out + "/7_ambra_idle.png");

        // settings, once per theme
        for (Palette t : THEMES) {
            u = phone(t.name, "Standard");
            settings(u);
            shot(r, u, out + "/8_settings_" + t.name.toLowerCase(java.util.Locale.US) + ".png");
        }
        u = phone("Aurum", "Standard");
        settings(u);
        u.needle = 1; u.needlePos = 0.45f; u.keepOn = false; u.keepOnT = 0.4f;     // highlight and switch mid-way
        shot(r, u, out + "/8_settings_sliding.png");

        u = phone("Ambra", "Standard");
        u.noteT = 0f; u.letter = 'D'; u.octave = "3"; u.caption = "Reference tone"; u.freq = "146.83 Hz";
        u.toneOn = true; u.sTargetT[2] = 1f;
        shot(r, u, out + "/9_ambra_tone.png");

        u = phone("Celeste", "Standard");
        live(u, 'B', 0, "3", -38f, "String 2 · Locked", "Tune up", "241.57 Hz");
        u.sTargetT[4] = 1f; u.sLockT[4] = 1f; u.sTunedT[0] = 1f;
        shot(r, u, out + "/10_celeste_locked.png");

        u = phone("Salvia", "7-string");
        live(u, 'B', 0, "1", 7f, "String 7", "Tune down", "62.0 Hz");
        u.sTargetT[0] = 1f;
        shot(r, u, out + "/11_salvia_7string.png");

        u = phone("Aurum", "Ukulele");
        live(u, 'G', 0, "4", -1.2f, "String 4", "In tune", "391.73 Hz");
        u.tuneT = 1f; u.hintAccent = true; u.sTargetT[0] = 1f; u.sTunedT[0] = 1f;
        shot(r, u, out + "/12_aurum_ukulele.png");

        u = phone("Notte", "Standard");
        u.paused = true; u.caption = "Paused"; u.hint = "Microphone off";
        shot(r, u, out + "/13_notte_paused.png");

        // ---- small windows ----
        u = sized("Aurum", "Standard", 360, 370, 2.625f, 1f, 24, 0);
        live(u, 'E', 0, "2", 12.4f, "String 6", "Tune down", "82.82 Hz");
        u.sTargetT[0] = 1f;
        shot(r, u, out + "/s1_split.png");
        u = sized("Celeste", "Chromatic", 360, 370, 2.625f, 1f, 24, 0);
        live(u, 'C', 1, "4", -23f, "Chromatic", "Tune up", "258.29 Hz");
        shot(r, u, out + "/s2_split_chromatic.png");
        u = sized("Rosa", "Standard", 360, 370, 2.625f, 1f, 24, 0);
        settings(u);
        shot(r, u, out + "/s3_split_settings.png");
        u = sized("Notte", "Standard", 360, 370, 2.625f, 1f, 24, 0);
        settings(u);
        u.page.scroll = 1e9f;                              // clamped by the page to the bottom
        shot(r, u, out + "/s5_split_settings_scrolled.png");
        u = sized("Salvia", "Standard", 360, 370, 2.625f, 1f, 24, 0);
        u.message = "Microphone access needed"; u.messageAction = "Allow";
        shot(r, u, out + "/s4_split_permission.png");

        u = sized("Salvia", "Half step down", 320, 569, 2f, 1.3f, 24, 48);
        live(u, 'A', 2, "2", -14f, "String 5", "Tune up", "102.97 Hz");
        u.sTargetT[1] = 1f;
        shot(r, u, out + "/n1_narrow.png");
        u = sized("Rosa", "Standard", 320, 569, 2f, 1.3f, 24, 48);
        settings(u);
        shot(r, u, out + "/n2_narrow_settings.png");
        u = sized("Celeste", "Standard", 320, 569, 2f, 1.3f, 24, 48);
        settings(u);
        u.page.scroll = 1e9f;
        shot(r, u, out + "/n3_narrow_settings_scrolled.png");

        // short windows: split screen down to the 220 dp minimum
        int[][] shortSizes = {{412, 300, 24, 0}, {412, 260, 24, 0}, {412, 220, 0, 0}, {300, 260, 24, 48}};
        for (int[] z : shortSizes) {
            u = sized("Celeste", "Standard", z[0], z[1], 2.625f, 1f, z[2], z[3]);
            live(u, 'A', 0, "2", -8f, "String 5", "Tune up", "109.49 Hz");
            u.sTargetT[1] = 1f;
            shot(r, u, out + "/w_" + z[0] + "x" + z[1] + ".png");
        }
        u = sized("Salvia", "Standard", 412, 260, 2.625f, 1f, 24, 0);
        u.message = "Microphone access needed"; u.messageAction = "Allow";
        shot(r, u, out + "/w_412x260_permission.png");

        // keyboard focus ring: a string, a chevron, a segmented option, a stepper half
        u = phone("Notte", "Standard");
        live(u, 'D', 0, "3", -3f, "String 4", "Tune up", "146.58 Hz");
        u.sTargetT[2] = 1f;
        u.hits.focused = Renderer.H_STRING + 2;
        shot(r, u, out + "/k1_focus_string.png");
        u.hits.focused = Renderer.H_TUNING_NEXT;
        shot(r, u, out + "/k2_focus_tuning.png");
        u = phone("Salvia", "Standard");
        settings(u);
        u.hits.focused = Renderer.H_TOL + 2;
        shot(r, u, out + "/k3_focus_option.png");
        u.hits.focused = Renderer.H_A4 + 1;
        shot(r, u, out + "/k4_focus_stepper.png");

        u = sized("Ambra", "Standard", 914, 412, 2.625f, 1f, 24, 24);
        settings(u);
        shot(r, u, out + "/l2_landscape_settings.png");

        u = sides(sized("Aurum", "Standard", 914, 412, 2.625f, 1f, 24, 0), 32, 48);   // cutout left, buttons right
        live(u, 'D', 0, "3", 4.6f, "String 4", "Tune down", "147.22 Hz");
        u.sTargetT[2] = 1f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        shot(r, u, out + "/l1_landscape.png");

        // ---- guided page ----
        u = phone("Aurum", "Standard");
        u.caption = "Listening"; u.hint = "Play a string";
        shot(r, u, out + "/g0_entry.png");

        u = guide(phone("Aurum", "Standard"), 0, Guide.WAIT, 0);
        u.gPluckT = 0.55f;
        shot(r, u, out + "/g1_wait_pluck.png");

        u = guide(phone("Celeste", "Standard"), 0, Guide.TIGHTEN, Guide.FAR);
        reading(u, -180f, 0.37f);
        shot(r, u, out + "/g2_tighten_far.png");

        u = guide(phone("Rosa", "Standard"), 2, Guide.LOOSEN, Guide.NEAR);
        u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        reading(u, 9f, 0.81f);
        shot(r, u, out + "/g3_loosen_near.png");

        u = guide(phone("Salvia", "Standard"), 3, Guide.TUNED, 0);
        u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f; u.sTunedT[3] = 0.7f;
        reading(u, 0.6f, 0.2f);
        u.tuneT = 1f; u.pulseT = 0.3f; u.gChevT = 0f;
        shot(r, u, out + "/g4_tuned.png");

        u = guide(phone("Ambra", "Standard"), 4, Guide.WRONG_WAY, 0);
        for (int i = 0; i < 4; i++) u.sTunedT[i] = 1f;
        reading(u, -64f, 1.1f);
        u.gShakeT = 0.15f;
        shot(r, u, out + "/g5_other_way.png");

        u = guide(phone("Notte", "Standard"), 5, Guide.DONE, 0);
        for (int i = 0; i < 6; i++) u.sTunedT[i] = 1f;
        shot(r, u, out + "/g6_done.png");

        u = guide(phone("Salvia", "Standard"), 0, Guide.WAIT, 0);
        u.message = "Microphone access needed"; u.messageAction = "Allow";
        shot(r, u, out + "/g7_permission.png");

        u = guide(sides(sized("Aurum", "Standard", 914, 412, 2.625f, 1f, 24, 24), 32, 0), 1, Guide.TIGHTEN, Guide.OFF);
        u.sTunedT[0] = 1f;
        reading(u, -42f, 0.5f);
        shot(r, u, out + "/g8_landscape.png");

        u = guide(sized("Celeste", "Standard", 360, 370, 2.625f, 1f, 24, 0), 1, Guide.TIGHTEN, Guide.OFF);
        reading(u, -42f, 0.5f);
        shot(r, u, out + "/g9_split.png");

        u = guide(sized("Rosa", "Standard", 320, 569, 2f, 1.3f, 24, 48), 2, Guide.LOOSEN, Guide.OFF);
        reading(u, 35f, 0.5f);
        shot(r, u, out + "/g10_narrow.png");

        u = guide(phone("Celeste", "Ukulele"), 0, Guide.ALMOST, 0);
        reading(u, 1.5f, 0.5f);
        shot(r, u, out + "/g11_ukulele.png");

        u = guide(phone("Notte", "Standard"), 1, Guide.WAIT, 0);
        u.sTunedT[0] = 1f;
        u.gPluckT = 0.86f;
        shot(r, u, out + "/g12_pluck_release.png");

        // ---- strums, chords, suggestions ----
        u = phone("Aurum", "Standard");
        live(u, 'A', 0, "2", -24f, "Strum check · 4 of 6 in tune", "Tune string 5 up first", "");
        u.liveT = 0f; u.sTargetT[1] = 0.5f; u.strumT = 1f; u.strumOk = 4f;
        float[] sc = {1.2f, -24f, 2.1f, -3.0f, 11.4f, 0.6f};
        System.arraycopy(sc, 0, u.sStrum, 0, 6);
        shot(r, u, out + "/m1_strum_check.png");

        u = phone("Salvia", "Standard");
        u.caption = "Strum check"; u.letter = 0; u.readCheck = true; u.cents = "All in tune"; u.centsAccent = true;
        u.hint = "Pluck a string to fine-tune"; u.strumT = 1f; u.strumOk = 4f;
        float[] ok = {1.2f, -2f, 0.4f, -3.0f, 2.4f, 0.6f};
        System.arraycopy(ok, 0, u.sStrum, 0, 6);
        shot(r, u, out + "/m2_strum_ok.png");

        u = phone("Celeste", "Standard");
        u.caption = "Strum check"; u.letter = 0; u.cents = "Sounds like Drop D"; u.hint = "Selected: Standard";
        u.readPill = "Switch to Drop D";
        shot(r, u, out + "/m3_suggest.png");

        u = phone("Rosa", "Standard");
        u.caption = "Chord"; u.letter = 0; u.cents = "Several notes"; u.hint = "Pluck one open string at a time";
        shot(r, u, out + "/m4_chord.png");

        u = phone("Notte", "Standard");
        live(u, 'E', 0, "2", 499f, "String 6 · Locked", "Sounds like string 5", "");
        u.hintAccent = true; u.sLockT[0] = 1f; u.sTargetT[0] = 1f; u.readPill = "Switch to string 5";
        shot(r, u, out + "/m5_wrong_string.png");

        u = phone("Ambra", "Standard");
        u.gAuto = false;
        guide(u, 2, Guide.TUNED, 0);
        u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        reading(u, 0.5f, 0.3f);
        u.tuneT = 1f; u.gChevT = 0f;
        shot(r, u, out + "/g13_manual_tuned.png");

        u = guide(phone("Celeste", "Standard"), 2, Guide.STRUM, 0);
        u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        shot(r, u, out + "/g14_strum.png");

        u = guide(phone("Aurum", "Standard"), 3, Guide.UNHEARD, 0);
        u.gPluckT = 0.4f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        shot(r, u, out + "/g15_unheard.png");

        u = phone("Salvia", "Standard");
        u.gAuto = false;
        guide(u, 0, Guide.WRONG_STRING, 1);
        reading(u, 500f, 0.2f);
        u.gChevT = 0f;                                   // no direction to turn: the app shows none
        shot(r, u, out + "/g16_wrong_string.png");

        // the longest reason the guide gives, in the narrow window at the largest font scale
        int[] lw = longest(Tuning.ALL[Tuning.indexOf("Standard")]);
        u = guide(sized("Rosa", "Standard", 320, 569, 2f, 1.3f, 24, 48), lw[2], lw[0], lw[1]);
        shot(r, u, out + "/g17_longest_narrow.png");

        u = guide(phone("Notte", "Standard"), 5, Guide.LOOSEN, Guide.FAR);
        reading(u, 260f, 0.4f);
        shot(r, u, out + "/g18_far_too_high.png");

        u = guide(sized("Aurum", "Standard", 360, 370, 2.625f, 1f, 24, 0), 1, Guide.TOO_HIGH, 0);
        reading(u, 1000f, 0.4f);
        u.gChevT = 0f;
        shot(r, u, out + "/g19_too_high_split.png");

        // a reference tone that could not play: shown in place of the readout, on either tab
        u = phone("Ambra", "Standard");
        u.message = "Reference tone unavailable";
        shot(r, u, out + "/14_ambra_tone_failed.png");
        u = guide(phone("Ambra", "Standard"), 1, Guide.WAIT, 0);
        u.message = "Reference tone unavailable";
        shot(r, u, out + "/g20_tone_failed.png");

        // the system font at 2x: the settings list follows it fully, the tabs stay capped at 1.3x
        u = sized("Rosa", "Standard", 320, 569, 2f, 2f, 24, 48);
        settings(u);
        shot(r, u, out + "/x1_2x_settings.png");
        u = sized("Rosa", "Standard", 320, 569, 2f, 2f, 24, 48);
        settings(u);
        u.page.scroll = 1e9f;
        shot(r, u, out + "/x2_2x_settings_end.png");
        u = guide(sized("Aurum", "Standard", 320, 569, 2f, 2f, 24, 48), 2, Guide.LOOSEN, Guide.OFF);
        reading(u, 35f, 0.5f);
        shot(r, u, out + "/x3_2x_guide.png");
        u = sized("Celeste", "Half step down", 320, 569, 2f, 2f, 24, 48);
        live(u, 'A', 2, "2", -14f, "String 5", "Tune up", "102.97 Hz");
        u.sTargetT[1] = 1f;
        shot(r, u, out + "/x4_2x_live.png");

        // the system's high-contrast setting
        u = phone("Celeste", "Standard");
        contrasted(u);
        settings(u);
        shot(r, u, out + "/c1_contrast_settings.png");
        u = phone("Aurum", "Standard");
        contrasted(u);
        live(u, 'A', 0, "2", 0.4f, "String 5", "In tune", "110.03 Hz");
        u.tuneT = 1f; u.hintAccent = true; u.sTargetT[1] = 1f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        shot(r, u, out + "/c2_contrast_in_tune.png");
        u = phone("Salvia", "Standard");
        contrasted(u);
        guide(u, 3, Guide.TIGHTEN, Guide.OFF);
        u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        reading(u, -42f, 0.5f);
        shot(r, u, out + "/c3_contrast_guide.png");

        if (args.length > 2) motion(r, args[2]);
    }

    /** The guided page at {@code step} of the Ui's tuning, saying what the app says for {@code status}. */
    static Ui guide(Ui u, int step, int status, int arg) {
        Tuning t = Tuning.ALL[Tuning.indexOf(u.tuningName)];
        int n = t.count(), lo = 999, hi = -999;
        for (int m : t.strings) { lo = Math.min(lo, m); hi = Math.max(hi, m); }
        for (int i = 0; i < n; i++) u.gThick[i] = (hi - t.strings[i]) / (float) (hi - lo);
        boolean done = status == Guide.DONE;
        int tuned = done ? (1 << n) - 1 : 0;                // the summary after a full round
        u.guideT = 1f;
        u.guideDone = done;
        u.gDoneT = done ? 1f : 0f;
        u.gStep = step;
        u.gCursor = step;
        u.gCaption = Guide.caption(t, step, done);
        u.gTitle = Guide.title(t, step, done, tuned);
        u.gSub = Guide.sub(t, step, done, tuned, t.flats(false));
        u.gWord = Guide.word(status);
        u.gDetail = Guide.detail(status, arg, t, step, u.gAuto);
        u.gWordAccent = status == Guide.TUNED;
        return u;
    }

    /** {status, arg, step} of the longest reason the guide gives on {@code t}: the first to wrap. */
    static int[] longest(Tuning t) {
        int[] best = null;
        int bestLen = -1;
        for (int step = 0; step < t.count(); step++) {
            for (int st = 0; st <= Guide.TOO_HIGH; st++) {                 // TOO_HIGH is the last status
                int args = st == Guide.WRONG_STRING ? t.count() : Guide.STALE + Guide.HIGH + 1;
                for (int a = 0; a < args; a++) {
                    if (st == Guide.WRONG_STRING && a == step) continue;
                    int len = Guide.detail(st, a, t, step, false).length();
                    if (len > bestLen) {
                        bestLen = len;
                        best = new int[]{st, a, step};
                    }
                }
            }
        }
        return best;
    }

    static void reading(Ui u, float cents, float time) {
        u.noteT = 1f; u.liveT = 1f;
        u.bead = Renderer.guideBeadOf(cents);
        u.gVib = 1f;
        u.gSag = Math.max(0f, Math.min(1f, -cents / 150f));
        u.gChevT = Math.abs(cents) > 3f ? 1f : 0f;
        u.time = time;
    }

    /**
     * A scripted run of the guided page for review of its motion: the pluck demonstration, a slack
     * low E tightened up to pitch, "Tuned", and the move to the next string. PNG frames at 30 fps.
     */
    static void motion(Renderer r, String dir) throws Exception {
        new File(dir).mkdirs();
        int fps = 30;
        double total = 9.6;
        int frames = (int) (total * fps);
        Ui u = guide(sized("Aurum", "Standard", 412, 914, 1.5f, 1f, 32, 24), 0, Guide.WAIT, 0);
        Tuning std = Tuning.ALL[Tuning.indexOf(u.tuningName)];
        Tracker tr = new Tracker();                          // the guide's words follow its readings
        tr.tuning = std;
        tr.tolerance = 3.0;
        Guide g = new Guide();
        float bead = 0f, beadV = 0f, vib = 0f, sag = 0f, chev = 0f, noteT = 0f, tuneT = 0f, cursor = 0f, tuned1 = 0f;
        String word = u.gWord, detail = u.gDetail;
        float wordT = 1f, titleT = 1f;
        int step = 0;
        float dt = 1f / fps;
        for (int f = 0; f < frames; f++) {
            float t = f * dt;
            // timeline
            boolean waiting = t < 2.8f;
            float cents;
            if (t < 2.8f) cents = Float.NaN;
            else if (t < 6.2f) cents = (float) (-170.0 + 170.0 * smooth((t - 3.0) / 3.0)) ;
            else cents = 0.4f;
            boolean live = t >= 2.8f && t < 7.4f;
            boolean tunedNow = t >= 6.45f && t < 7.5f;
            if (t >= 7.5f && step == 0) {
                step = 1;
                u.gTitlePrev = u.gTitle; u.gSubPrev = u.gSub;
                u.gTitle = Guide.title(std, 1, false, 1); u.gSub = Guide.sub(std, 1, false, 1, false);
                u.gCaption = Guide.caption(std, 1, false);
                titleT = 0f;
            }
            tr.hasNote = tr.live = live && step == 0;
            tr.cents = Float.isNaN(cents) ? 0.0 : cents;
            g.update(tr, step, false, false, false, tunedNow, false, false, false);
            String w = Guide.word(g.status), d = Guide.detail(g.status, g.arg, std, step, true);
            if (!w.equals(word) || !d.equals(detail)) {
                u.gWordPrev = word; u.gDetailPrev = detail; word = w; detail = d; wordT = 0f;
            }
            u.gWord = word; u.gDetail = detail; u.gWordAccent = g.status == Guide.TUNED;
            wordT = Math.min(1f, wordT + dt / 0.25f);
            titleT = Math.min(1f, titleT + dt / 0.35f);
            u.gWordT = wordT; u.gTitleT = titleT;

            boolean hasNote = live || tunedNow;
            noteT = approach(noteT, hasNote && step == 0 ? 1f : 0f, dt, 0.12f);
            tuneT = approach(tuneT, tunedNow ? 1f : 0f, dt, 0.07f);
            float target = Float.isNaN(cents) ? 0f : Renderer.guideBeadOf(cents);
            for (float q = 0; q < dt; q += 0.004f) {
                float h = Math.min(0.004f, dt - q);
                float acc = 18f * 18f * (target - bead) - 2f * 0.82f * 18f * beadV;
                beadV += acc * h;
                bead += beadV * h;
            }
            vib = approach(vib, live && step == 0 ? 1f : 0f, dt, live ? 0.05f : 0.4f);
            sag = approach(sag, hasNote && !Float.isNaN(cents) ? Math.max(0f, Math.min(1f, -cents / 150f)) : 0f, dt, 0.15f);
            chev = approach(chev, live && !tunedNow && !Float.isNaN(cents) && Math.abs(cents) > 3f ? 1f : 0f, dt, 0.12f);
            cursor = approach(cursor, step, dt, 0.09f);
            if (t >= 6.45f) tuned1 = Math.min(1f, tuned1 + dt / 0.35f);
            u.gStep = step; u.gCursor = cursor;
            u.sTunedT[0] = tuned1;
            u.noteT = noteT; u.liveT = live ? 1f : 0f; u.tuneT = tuneT; u.bead = bead;
            u.gVib = vib; u.gSag = sag; u.gChevT = chev;
            u.gPluckT = waiting ? t % 2.8f : (step == 1 && t >= 8.0f ? (t - 8.0f) % 2.8f : -1f);
            u.pulseT = t >= 6.45f && t < 7.15f ? (t - 6.45f) / 0.7f : -1f;
            u.time = t;
            shot(r, u, String.format(java.util.Locale.US, "%s/f%04d.png", dir, f));
        }
    }

    static float approach(float v, float target, float dt, float tau) {
        float n = target + (v - target) * (float) Math.exp(-dt / tau);
        return Math.abs(n - target) < 0.002f ? target : n;
    }

    static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    static void live(Ui u, char letter, int acc, String oct, float cents, String caption, String hint, String freq) {
        u.letter = letter; u.accidental = acc; u.octave = oct;
        u.cents = (cents >= 0.05f ? "+" : cents <= -0.05f ? "−" : "") + String.format(java.util.Locale.US, Math.abs(cents) < 10 ? "%.1f ¢" : "%.0f ¢", Math.abs(cents));
        u.caption = caption; u.hint = hint; u.freq = freq;
        u.noteT = 1f; u.liveT = 1f;
        u.bead = Renderer.beadOf(cents);
    }

    static void contrasted(Ui u) {
        Palette[] t = new Palette[u.themes.length];
        for (int i = 0; i < t.length; i++) t[i] = u.themes[i].contrasted();
        u.themes = t;
        u.pal = t[u.themeIdx];
    }

    static void settings(Ui u) {
        u.pageT = 1f;
        u.a4Label = "A4 = 440 Hz";
        u.tolOpts = new String[]{"±1 ¢", "±2 ¢", "±3 ¢", "±5 ¢"};
        u.tolIdx = 1; u.tolPos = 1f;
        u.haptics = true; u.hapticsT = 1f; u.keepOn = true; u.keepOnT = 1f;
    }

    static Ui phone(String theme, String tuning) {
        return sized(theme, tuning, 412, 914, DENSITY, 1f, 32, 24);
    }

    static Ui sized(String theme, String tuning, int wDp, int hDp, float density, float font, int topDp, int bottomDp) {
        Ui u = new Ui();
        u.textScale(density, font);
        u.width = Math.round(wDp * density);
        u.height = Math.round(hDp * density);
        u.insetT = Math.round(topDp * density);
        u.insetB = Math.round(bottomDp * density);
        for (int i = 0; i < THEMES.length; i++) if (THEMES[i].name.equals(theme)) { u.pal = THEMES[i]; u.themeIdx = i; }
        u.themes = THEMES;
        Tuning t = Tuning.ALL[Tuning.indexOf(tuning)];
        u.tuningName = t.name;
        u.stringsT = t.chromatic() ? 0f : 1f;
        u.stringCount = t.count();
        for (int i = 0; i < t.count(); i++) {
            boolean flats = t.flats(false);
            u.sLetter[i] = Tuning.letter(t.strings[i], flats);
            u.sAcc[i] = Tuning.accidental(t.strings[i], flats);
        }
        return u;
    }

    /** Side insets in dp: a camera cutout, or the navigation bar on the side in landscape. */
    static Ui sides(Ui u, int leftDp, int rightDp) {
        u.insetL = Math.round(leftDp * u.dp);
        u.insetR = Math.round(rightDp * u.dp);
        return u;
    }

    static void shot(Renderer r, Ui u, String path) throws Exception {
        BufferedImage img = new BufferedImage(u.width, u.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.draw(new Canvas(g, u.width, u.height), u);
        // -Dhits=true outlines the hit rectangles, for checking overlap and reach
        if (Boolean.getBoolean("hits")) {
            g.setColor(new java.awt.Color(255, 0, 0, 90));
            for (int i = 0; i < u.hits.count; i++) {
                android.graphics.RectF h = u.hits.hits[i].rect;
                g.drawRect(Math.round(h.left), Math.round(h.top), Math.round(h.right - h.left), Math.round(h.bottom - h.top));
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }
}
