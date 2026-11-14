package app.tuner.guitar;

import java.util.Locale;

/**
 * What the guided page says: the step's title and sub-line, and a word with its reason ("Tighten",
 * "Much too low. Turn the peg slowly as you pluck."). No Android dependencies, so the preview
 * renders the sentences the app shows.
 */
final class Guide {
    static final int WAIT = 0, TIGHTEN = 1, LOOSEN = 2, ALMOST = 3, TUNED = 4, WRONG_STRING = 5, WRONG_WAY = 6,
            DONE = 7, STRUM = 8, UNHEARD = 9, PAUSED = 10, TONE = 11, TOO_HIGH = 12;
    /** How far off TIGHTEN and LOOSEN are; HIGH: sharp enough to go below the note first. */
    static final int NEAR = 0, OFF = 1, FAR = 2, HIGH = 3;
    /** Added to the argument when the reading is held, not heard now: pluck again. */
    static final int STALE = 10;

    private static final String[] ORDINAL = {"", "", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth"};
    private static final String[] SHORT_ORDINAL = {"", "", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th"};

    /** Status of the step and its argument: a level, plus STALE; or the other string, for WRONG_STRING. */
    int status = -1, arg;

    /** The words depend on something the status does not show (the Auto switch): recompute them. */
    void reset() {
        status = -1;
    }

    /**
     * Status of string {@code step} of {@code tr.tuning} from the tracker and the page's state;
     * returns true when it changed.
     */
    boolean update(Tracker tr, int step, boolean complete, boolean toneOn, boolean paused, boolean advancing,
                   boolean strummed, boolean unheard, boolean wrongWay) {
        Tuning t = tr.tuning;
        double c = tr.cents;
        boolean marked = (tr.tunedMask & (1 << step)) != 0;
        int st, a = 0;
        if (complete) st = DONE;
        else if (toneOn) st = TONE;                  // the fork also plays while listening is paused
        else if (paused) st = PAUSED;
        else if (advancing) st = TUNED;
        else if (strummed) st = STRUM;
        else if (!tr.hasNote) st = unheard ? UNHEARD : WAIT;
        else {
            int other = -1;
            if (Math.abs(c) > 250.0) {
                for (int j = 0; j < t.count(); j++) {
                    if (j != step && Math.abs(Tuning.cents(tr.hz, Tuning.freq(t.strings[j], tr.a4))) < 35.0) other = j;
                }
            }
            if (other >= 0) {
                st = WRONG_STRING;                                   // manual mode: point it out
                a = other;
            } else if (c > 900.0) {
                // A string cannot be tightened most of an octave: it would have broken. This is a
                // misreading (a harmonic, an octave error), not a reason to say "loosen it now".
                st = TOO_HIGH;
            } else if (wrongWay && tr.live) {
                st = WRONG_WAY;
            } else if (Math.abs(c) <= tr.tolerance || !tr.live && marked) {
                st = tr.inTune || !tr.live && marked ? TUNED : ALMOST;
                if (st == ALMOST && !tr.live) a = STALE;
            } else {
                st = c < 0 ? TIGHTEN : LOOSEN;
                double abs = Math.abs(c);
                a = abs > (c < 0 ? 100.0 : 200.0) ? FAR : abs > 15.0 ? OFF : NEAR;
                if (c > 0 && abs > 100.0 && abs <= 200.0) a = HIGH;
                if (!tr.live) a += STALE;
            }
        }
        if (st == status && a == arg) return false;
        status = st;
        arg = a;
        return true;
    }

    static String word(int status) {
        switch (status) {
            case TUNED: return "Tuned";
            case TONE: return "Listen";
            case STRUM: return "One string";
            case WRONG_STRING: return "Wrong string?";
            case TOO_HIGH: return "Pluck again";
            case WRONG_WAY: return "Other way";
            case ALMOST: return "Almost";
            case TIGHTEN: return "Tighten";
            case LOOSEN: return "Loosen";
            default: return "";
        }
    }

    /** The reason under the word; {@code auto}: strings switch by themselves. */
    static String detail(int status, int arg, Tuning t, int step, boolean auto) {
        boolean stale = arg >= STALE;
        int level = arg % STALE;
        switch (status) {
            case TUNED: return auto ? "" : "Tap Next for the next string.";
            case WAIT: return "Listening";
            case PAUSED: return "Paused. Tap the pause button to listen again.";
            case TONE: return "How the " + position(t, step) + " should sound. Tap the fork to stop.";
            case UNHEARD: return "Not hearing it yet. Hold the phone close and pluck firmly.";
            // a string far too slack (new, unwound) sounds below anything a string should: it reads
            // as several notes too
            case STRUM: return "Pluck only the " + position(t, step) + ". If it is floppy, tighten it first.";
            case WRONG_STRING: return "That is the " + shortPos(t, arg) + ". Pluck the " + shortPos(t, step) + ", or tap that one.";
            case TOO_HIGH: return "Far too high for this string. Pluck it again, mid-string.";
            case WRONG_WAY: return "The note is moving away. Turn the peg the other way.";
            case ALMOST: return stale ? "Pluck again to check it." : "Right there. Let it ring.";
            case TIGHTEN:
                return stale ? "Pluck again while you turn the peg."
                        : level == FAR ? "Much too low. Turn the peg slowly as you pluck."
                        : level == OFF ? "Too low. Keep turning slowly." : "Nearly there. Just a touch more.";
            case LOOSEN:
                return stale ? "Pluck again while you turn the peg."
                        : level == FAR ? "Far too high. Loosen it now, or it could break."
                        : level == HIGH ? "Much too high. Go below the note, then tighten up to it."
                        : level == OFF ? "Too high. Go a little below, then tighten up to it." : "Nearly there. Just a touch looser.";
            default: return "";
        }
    }

    static String title(Tuning t, int step, boolean complete, int tunedMask) {
        int n = t.count();
        if (complete) {
            int done = Integer.bitCount(tunedMask & ((1 << n) - 1));
            return done == n ? "All strings tuned" : done + " of " + n + " strings tuned";
        }
        if (step == 0) return "Top string";
        if (step == n - 1) return "Bottom string";
        return ORDINAL[step + 1] + " from the top";
    }

    static String sub(Tuning t, int step, boolean complete, int tunedMask, boolean flats) {
        int n = t.count();
        if (complete) {
            return Integer.bitCount(tunedMask & ((1 << n) - 1)) == n
                    ? "Tuning one string pulls on the others a little. Go round once more to check."
                    : "Tap a string to go back to it.";
        }
        boolean mono = true;
        for (int i = 1; i < n; i++) mono &= t.strings[i] > t.strings[i - 1];
        String where;
        if (step == 0) where = mono ? "The thickest one, nearest your face. " : "The one nearest your face. ";
        else if (step == n - 1) where = mono ? "The thinnest one, nearest the floor. " : "The one nearest the floor. ";
        else where = "Pluck it and let it ring. ";
        return where + "Its note is " + noteName(t.strings[step], flats) + ".";
    }

    static String caption(Tuning t, int step, boolean complete) {
        return complete ? t.name + " tuning" : "Step " + (step + 1) + " of " + t.count() + " · " + t.name;
    }

    /** "top string", "third string from the top", "bottom string". */
    static String position(Tuning t, int i) {
        if (i == 0) return "top string";
        if (i == t.count() - 1) return "bottom string";
        return ORDINAL[i + 1].toLowerCase(Locale.US) + " string from the top";
    }

    /** "top string", "3rd from the top", "bottom string": for advice lines. */
    static String shortPos(Tuning t, int i) {
        if (i == 0) return "top string";
        if (i == t.count() - 1) return "bottom string";
        return SHORT_ORDINAL[i + 1] + " from the top";
    }

    /** "E", "F sharp", "B flat". */
    static String noteName(int midi, boolean flats) {
        int acc = Tuning.accidental(midi, flats);
        return Tuning.letter(midi, flats) + (acc == 1 ? " sharp" : acc == 2 ? " flat" : "");
    }
}
