package app.tuner.guitar;

import android.graphics.Canvas;
import android.graphics.Typeface;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the production Renderer on the JVM (AWT-backed android.graphics stubs) for visual review:
 * a 412 x 914 dp phone, plus small windows (split screen, narrow phone at large font, landscape).
 */
public final class Preview {
    static final float DENSITY = 2.625f;

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

        u = phone("Rosa", "Standard");
        settings(u);
        shot(r, u, out + "/8_rosa_settings.png");

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

        // keyboard focus ring
        u = phone("Notte", "Standard");
        live(u, 'D', 0, "3", -3f, "String 4", "Tune up", "146.58 Hz");
        u.sTargetT[2] = 1f;
        shot(r, u, null);
        u.focusId = Renderer.H_STRING + 2;
        shot(r, u, out + "/k1_focus_string.png");
        u.focusId = Renderer.H_TUNING_NEXT;
        shot(r, u, out + "/k2_focus_tuning.png");

        u = sized("Aurum", "Standard", 914, 412, 2.625f, 1f, 24, 24);
        live(u, 'D', 0, "3", 4.6f, "String 4", "Tune down", "147.22 Hz");
        u.sTargetT[2] = 1f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        shot(r, u, out + "/l1_landscape.png");

        // ---- guided page ----
        u = phone("Aurum", "Standard");
        u.caption = "Listening"; u.hint = "Play a string";
        shot(r, u, out + "/g0_entry.png");

        u = guide(phone("Aurum", "Standard"), 0, "", "Listening");
        u.gPluckT = 0.55f;
        shot(r, u, out + "/g1_wait_pluck.png");

        u = guide(phone("Celeste", "Standard"), 0, "Tighten", "Much too low. Turn the peg slowly, plucking as you go.");
        reading(u, -180f, 0.37f);
        shot(r, u, out + "/g2_tighten_far.png");

        u = guide(phone("Rosa", "Standard"), 2, "Loosen", "Nearly there. Just a touch looser.");
        u.sTunedT[0] = 1f; u.sTunedT[1] = 1f;
        reading(u, 9f, 0.81f);
        shot(r, u, out + "/g3_loosen_near.png");

        u = guide(phone("Salvia", "Standard"), 3, "Tuned", "");
        u.gWordAccent = true; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f; u.sTunedT[3] = 0.7f;
        reading(u, 0.6f, 0.2f);
        u.tuneT = 1f; u.pulseT = 0.3f; u.gChevT = 0f;
        shot(r, u, out + "/g4_tuned.png");

        u = guide(phone("Ambra", "Standard"), 4, "Other way", "The note is moving away. Turn the peg the other way.");
        for (int i = 0; i < 4; i++) u.sTunedT[i] = 1f;
        reading(u, -64f, 1.1f);
        u.gShakeT = 0.15f;
        shot(r, u, out + "/g5_other_way.png");

        u = guide(phone("Notte", "Standard"), 5, "", "Listening");
        for (int i = 0; i < 6; i++) u.sTunedT[i] = 1f;
        u.guideDone = true; u.gDoneT = 1f;
        u.gTitle = "All strings tuned"; u.gCaption = "Standard tuning";
        u.gSub = "Tuning one string pulls on the others a little. Go round once more to check.";
        u.gWord = ""; u.gDetail = "";
        shot(r, u, out + "/g6_done.png");

        u = guide(phone("Salvia", "Standard"), 0, "", "");
        u.message = "Microphone access needed"; u.messageAction = "Allow";
        shot(r, u, out + "/g7_permission.png");

        u = guide(sized("Aurum", "Standard", 914, 412, 2.625f, 1f, 24, 24), 1, "Tighten", "Too low. Keep turning slowly.");
        u.sTunedT[0] = 1f;
        reading(u, -42f, 0.5f);
        shot(r, u, out + "/g8_landscape.png");

        u = guide(sized("Celeste", "Standard", 360, 370, 2.625f, 1f, 24, 0), 1, "Tighten", "Too low. Keep turning slowly.");
        reading(u, -42f, 0.5f);
        shot(r, u, out + "/g9_split.png");

        u = guide(sized("Rosa", "Standard", 320, 569, 2f, 1.3f, 24, 48), 2, "Loosen", "Too high. Go a little below, then tighten up to it.");
        reading(u, 35f, 0.5f);
        shot(r, u, out + "/g10_narrow.png");

        u = guide(phone("Celeste", "Ukulele"), 0, "Almost", "Right there. Let it ring.");
        reading(u, 1.5f, 0.5f);
        shot(r, u, out + "/g11_ukulele.png");

        u = guide(phone("Notte", "Standard"), 1, "", "Listening");
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

        u = guide(phone("Ambra", "Standard"), 2, "Tuned", "Tap Next for the next string.");
        u.gAuto = false; u.gWordAccent = true; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        reading(u, 0.5f, 0.3f);
        u.tuneT = 1f; u.gChevT = 0f;
        shot(r, u, out + "/g13_manual_tuned.png");

        u = guide(phone("Celeste", "Standard"), 0, "One string", "That was several strings at once. Pluck only the top string.");
        shot(r, u, out + "/g14_strum.png");

        u = guide(phone("Aurum", "Standard"), 3, "", "Not hearing it yet. Hold the phone near the guitar and pluck firmly.");
        u.gPluckT = 0.4f; u.sTunedT[0] = 1f; u.sTunedT[1] = 1f; u.sTunedT[2] = 1f;
        shot(r, u, out + "/g15_unheard.png");

        u = guide(phone("Salvia", "Standard"), 0, "Wrong string?", "That sounds like the second string from the top. Pluck the top string, or tap that one.");
        u.gAuto = false;
        reading(u, 500f, 0.2f);
        shot(r, u, out + "/g16_wrong_string.png");

        if (args.length > 2) motion(r, args[2]);
    }

    static final String[] TITLES = {"Top string", "Second from the top", "Third from the top", "Fourth from the top",
            "Fifth from the top", "Bottom string"};
    static final String[] SUBS = {"The thickest one, nearest your face. Its note is E.", "Pluck it and let it ring. Its note is A.",
            "Pluck it and let it ring. Its note is D.", "Pluck it and let it ring. Its note is G.",
            "Pluck it and let it ring. Its note is B.", "The thinnest one, nearest the floor. Its note is E."};

    static Ui guide(Ui u, int step, String word, String detail) {
        Tuning t = Tuning.ALL[Tuning.indexOf(u.tuningName)];
        int n = t.count(), lo = 999, hi = -999;
        for (int m : t.strings) { lo = Math.min(lo, m); hi = Math.max(hi, m); }
        for (int i = 0; i < n; i++) u.gThick[i] = (hi - t.strings[i]) / (float) (hi - lo);
        u.guideT = 1f;
        u.gStep = step;
        u.gCursor = step;
        u.gCaption = "Step " + (step + 1) + " of " + n + " · " + t.name;
        if (n == 6) {
            u.gTitle = TITLES[step];
            u.gSub = SUBS[step];
        } else {
            u.gTitle = step == 0 ? "Top string" : "Second from the top";
            u.gSub = "The one nearest your face. Its note is G.";
        }
        u.gWord = word;
        u.gDetail = detail;
        return u;
    }

    static void reading(Ui u, float cents, float time) {
        u.noteT = 1f; u.liveT = 1f;
        u.bead = (float) Math.tanh(cents / 90.0);
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
        Ui u = guide(sized("Aurum", "Standard", 412, 914, 1.5f, 1f, 32, 24), 0, "", "Listening");
        float bead = 0f, beadV = 0f, vib = 0f, sag = 0f, chev = 0f, noteT = 0f, tuneT = 0f, cursor = 0f, tuned1 = 0f;
        String word = "", detail = "Listening";
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
                u.gTitle = TITLES[1]; u.gSub = SUBS[1];
                u.gCaption = "Step 2 of 6 · Standard";
                titleT = 0f;
            }
            String w, d;
            boolean accent = false;
            if (waiting || step == 1) { w = ""; d = "Listening"; }
            else if (tunedNow) { w = "Tuned"; d = ""; accent = true; }
            else if (cents < -100) { w = "Tighten"; d = "Much too low. Turn the peg slowly, plucking as you go."; }
            else if (cents < -15) { w = "Tighten"; d = "Too low. Keep turning slowly."; }
            else if (cents < -3) { w = "Tighten"; d = "Nearly there. Just a touch more."; }
            else { w = "Almost"; d = "Right there. Let it ring."; }
            if (!w.equals(word) || !d.equals(detail)) {
                u.gWordPrev = word; u.gDetailPrev = detail; word = w; detail = d; wordT = 0f;
            }
            u.gWord = word; u.gDetail = detail; u.gWordAccent = accent;
            wordT = Math.min(1f, wordT + dt / 0.25f);
            titleT = Math.min(1f, titleT + dt / 0.35f);
            u.gWordT = wordT; u.gTitleT = titleT;

            boolean hasNote = live || tunedNow;
            noteT = approach(noteT, hasNote && step == 0 ? 1f : 0f, dt, 0.12f);
            tuneT = approach(tuneT, tunedNow ? 1f : 0f, dt, 0.07f);
            float target = Float.isNaN(cents) ? 0f : (float) Math.tanh(cents / 90.0);
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

    static void settings(Ui u) {
        u.pageT = 1f;
        u.a4Label = "A4 = 440 Hz"; u.tolLabel = "± 2 ¢"; u.needleLabel = "Smooth"; u.namesLabel = "Sharps";
        u.hapticsT = 1f; u.keepOnT = 1f;
    }

    static Ui phone(String theme, String tuning) {
        return sized(theme, tuning, 412, 914, DENSITY, 1f, 32, 24);
    }

    static Ui sized(String theme, String tuning, int wDp, int hDp, float density, float font, int topDp, int bottomDp) {
        Ui u = new Ui();
        u.dp = density;
        u.sp = density * font;
        u.width = Math.round(wDp * density);
        u.height = Math.round(hDp * density);
        u.insetT = Math.round(topDp * density);
        u.insetB = Math.round(bottomDp * density);
        for (Palette p : Palette.BUILT_IN) if (p.name.equals(theme)) u.pal = p;
        u.themeName = theme;
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

    static void shot(Renderer r, Ui u, String path) throws Exception {
        BufferedImage img = new BufferedImage(u.width, u.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.draw(new Canvas(g, u.width, u.height), u);
        if (u.settingsScroll > u.settingsMaxScroll) {
            u.settingsScroll = u.settingsMaxScroll;
            r.draw(new Canvas(g, u.width, u.height), u);
        }
        if (Boolean.getBoolean("hits")) g.setColor(new java.awt.Color(255, 0, 0, 90));
        for (int i = 0; Boolean.getBoolean("hits") && i < u.hitCount; i++) {
            android.graphics.RectF h = u.hits[i].rect;
            g.drawRect(Math.round(h.left), Math.round(h.top), Math.round(h.right - h.left), Math.round(h.bottom - h.top));
        }
        g.dispose();
        if (path != null) ImageIO.write(img, "png", new File(path));
    }
}
