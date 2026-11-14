package me.saatvik.tuner;

import me.saatvik.hairline.Hits;
import me.saatvik.hairline.Palette;
import me.saatvik.hairline.SettingsPage;

/** Everything the renderer needs for one frame. Written by TunerView, read by Renderer. */
final class Ui {
    static final int MAX_STRINGS = 8;

    float dp = 1f, sp = 1f;
    int width, height, insetL, insetT, insetR, insetB;

    Palette pal = Palette.BUILT_IN[0];
    float pageT;                 // 0 = tuner, 1 = settings

    // dial
    float bead;                  // needle position -1 flat .. +1 sharp: Renderer.beadOf on the dial, guideBeadOf on the guide
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

    // settings page: each row's state; the animated positions of the sliding highlights and switches
    String a4Label = "";
    String[] tolOpts = {};
    int tolIdx, needle, names;   // needle: index into Renderer.NEEDLES (1 = Fast); names: Renderer.NAMES (1 = Flats)
    float tolPos, needlePos, namesPos;
    boolean haptics, keepOn;
    float hapticsT, keepOnT;
    Palette[] themes = {};
    int themeIdx;
    /** Built by Renderer each frame it is drawn; scrolled by TunerView. */
    final SettingsPage page = new SettingsPage();

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

    // interaction: targets, written by Renderer each frame; pressed and focused by TunerView
    final Hits hits = new Hits(48);
}
