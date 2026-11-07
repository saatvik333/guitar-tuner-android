package app.tuner.guitar;

import android.graphics.RectF;

/** Everything the renderer needs for one frame. Written by TunerView, read by Renderer. */
final class Ui {
    static final int MAX_STRINGS = 8;

    float dp = 1f, sp = 1f;
    int width, height, insetL, insetT, insetR, insetB;

    Palette pal = Palette.BUILT_IN[0];
    String themeName = "";
    float pageT;                 // 0 = tuner, 1 = settings

    // dial
    float bead;                  // needle position, tanh(cents / 58): -1 flat .. +1 sharp
    float noteT;                 // 1 = a note is shown
    float liveT;                 // 1 = pitch is being heard now, 0 = last reading held
    float tuneT;                 // in-tune highlight
    float pulseT = -1f;          // ring pulse on reaching pitch, -1 = idle

    // readout inside the dial
    String caption = "";
    char letter;                 // 0 = no note: a dash is drawn
    int accidental;              // 0 natural, 1 sharp, 2 flat
    String octave = "";
    String cents = "";
    String hint = "";            // "Tune down", "In tune"
    String freq = "";            // "83.02 Hz"
    boolean hintAccent;

    // microphone message replacing the readout
    String message = "";         // empty = no message
    String messageAction = "";   // pill label, empty = none

    // tuning row and strings
    String tuningName = "";
    float stringsT;              // 1 = string row shown, 0 = chromatic
    int stringCount;
    final char[] sLetter = new char[MAX_STRINGS];
    final int[] sAcc = new int[MAX_STRINGS];
    final float[] sTunedT = new float[MAX_STRINGS];
    final float[] sTargetT = new float[MAX_STRINGS];
    final float[] sLockT = new float[MAX_STRINGS];

    // bottom bar
    boolean toneOn, paused;

    // settings page
    String a4Label = "", tolLabel = "", needleLabel = "", namesLabel = "";
    float hapticsT, keepOnT;
    float settingsScroll;        // px, written by TunerView
    float settingsMaxScroll;     // px, written by Renderer

    // strum check and suggestions on the dial
    boolean readCheck;           // all strings in tune: a check where the note goes
    boolean centsAccent;         // the cents line is a verdict ("All in tune")
    String readPill = "";        // an action on the dial: "Switch to Drop D", "Switch"
    float strumT;                // per-string results under the string row
    final float[] sStrum = new float[MAX_STRINGS];   // cents, NaN = not heard
    float strumOk = 4f;          // within this, a string shows a check

    // guided page (beginners): one string at a time, with motion
    float guideT;                // 0 = hidden, 1 = shown
    boolean guideDone;           // finished: summary instead of a step
    boolean gPills = true;       // the Skip / Auto row fits (set by the renderer)
    boolean gAuto = true;        // strings switch by themselves (after tuning, or when another is plucked)
    int gStep;                   // string being tuned
    float gCursor;               // highlighted row, animated toward gStep
    final float[] gThick = new float[MAX_STRINGS];   // 1 = lowest-pitched (thickest) string
    String gCaption = "", gTitle = "", gSub = "", gWord = "", gDetail = "";
    String gTitlePrev = "", gSubPrev = "", gWordPrev = "", gDetailPrev = "";
    float gTitleT = 1f, gWordT = 1f;   // crossfades, 0 -> 1
    boolean gWordAccent;
    float gVib;                  // the string is sounding: vibration amplitude 0..1
    float gSag;                  // slack: how far flat, 0..1
    float gPluckT = -1f;         // demo pluck, seconds into its loop; -1 = off
    float gChevT;                // direction chevrons on the meter
    float gShakeT = -1f;         // "other way" shake, 0..1; -1 = idle
    float gDoneT;                // completion mark
    float time;                  // seconds, for loops

    // interaction
    int pressedId;
    int focusId;                 // keyboard focus, 0 = none

    static final class Hit {
        int id;
        final RectF rect = new RectF();
        float vx, vy, vr;        // press feedback: circle (vr > 0), pill (vr == 0), none (vr < 0)
        float pw, ph;            // pill half width and height, centred on (vx, vy)
    }

    final Hit[] hits = new Hit[48];
    int hitCount;

    Ui() {
        for (int i = 0; i < hits.length; i++) hits[i] = new Hit();
    }

    void resetHits() {
        hitCount = 0;
    }

    void addHit(int id, float l, float t, float r, float b, float vx, float vy, float vr) {
        if (hitCount >= hits.length) return;
        Hit h = hits[hitCount++];
        h.id = id;
        h.rect.set(l, t, r, b);
        h.vx = vx;
        h.vy = vy;
        h.vr = vr;
        h.pw = h.ph = 0f;
    }

    int hitAt(float x, float y) {
        for (int i = hitCount - 1; i >= 0; i--) {
            if (hits[i].rect.contains(x, y)) return hits[i].id;
        }
        return 0;
    }

    Hit find(int id) {
        for (int i = 0; i < hitCount; i++) if (hits[i].id == id) return hits[i];
        return null;
    }
}
