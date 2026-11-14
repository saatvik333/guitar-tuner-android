package me.saatvik.tuner;

/**
 * Turns the detector's per-hop readings into what the tuner shows: the target (nearest note, or
 * the nearest / locked string of a tuning), a smoothed deviation in cents, and the in-tune state.
 * No Android dependencies; time is audio time in seconds.
 */
final class Tracker {
    static final int EV_IN_TUNE = 1, EV_TARGET = 2;

    private static final double STALE = 0.25;          // s without a voiced frame: no longer live
    private static final double GAP = 0.30;            // s of silence before a note counts as new
    private static final double DWELL = 0.25;          // s inside the range before "in tune"
    private static final double STRING_KEEP = 35.0;    // cents a new string must be closer by
    private static final double NOTE_KEEP = 0.10;      // semitones beyond +-0.5 before a note changes
    private static final double ATTACK = 0.10;         // s after a pluck: pitch still settling
    private static final double NEW_PLUCK = 0.15;      // s after a pluck: string chosen afresh

    // settings
    double a4 = 440.0;
    Tuning tuning = Tuning.ALL[0];
    int locked = -1;
    double tolerance = 2.0;
    boolean fast;
    boolean foldOctaves = true;                        // a string an octave off reads as the string

    // output
    boolean live, hasNote, inTune;
    int midi = -1, string = -1;
    double cents, hz;
    int tunedMask;
    /** Audio time of the latest pluck (onset), and of the latest frame. */
    double lastOnset = -1e9, now = -1e9;
    /** Median of the last three raw readings, Hz: the plucked pitch, before any smoothing. */
    double rawHz = Double.NaN;

    private int events;
    private double lastVoiced = -1e9, lastT = -1e9, stableSince = -1.0, offSince = -1.0;
    private int pendMidi = -1, pendString = -1, pendCount;
    private boolean wasFolded;                         // last frame was read an octave down
    private final double[] hist = new double[3], rawHist = new double[3];
    private int rawN;
    private int histN;
    private boolean smoothed;
    private double prevMed, slope;                     // one-euro filter state

    int takeEvents() {
        int e = events;
        events = 0;
        return e;
    }

    /** Forget the current target, e.g. after a settings change or while a reference tone plays. */
    void reset(boolean clearTuned) {
        live = hasNote = inTune = false;
        midi = string = -1;
        cents = 0.0;
        hz = 0.0;
        pendMidi = pendString = -1;
        pendCount = 0;
        wasFolded = false;
        histN = 0;
        smoothed = false;
        stableSince = offSince = -1.0;
        lastVoiced = -1e9;
        rawN = 0;
        rawHz = Double.NaN;
        if (clearTuned) tunedMask = 0;
    }

    void update(double t, boolean voiced, double f) {
        update(t, voiced, f, false);
    }

    /** {@code onset}: the detector heard a pluck in this frame. */
    void update(double t, boolean voiced, double f, boolean onset) {
        double dt = Math.min(0.1, Math.max(0.001, t - lastT));
        lastT = t;
        now = t;
        if (onset) {
            lastOnset = t;
            rawN = 0;
        }
        if (!voiced || Double.isNaN(f) || f <= 0.0) {
            if (live && t - lastVoiced > STALE) {
                live = false;
                inTune = false;
                stableSince = offSince = -1.0;
            }
            return;
        }
        boolean fresh = t - lastVoiced > GAP;
        boolean pluck = t - lastOnset < NEW_PLUCK;          // a new pluck: no bias toward the last target
        lastVoiced = t;
        if (rawN < 3) rawN++;
        rawHist[2] = rawHist[1];
        rawHist[1] = rawHist[0];
        rawHist[0] = f;
        rawHz = rawN < 3 ? f : median(rawHist[0], rawHist[1], rawHist[2]);

        // ---- target ----
        int tMidi, tString = -1;
        double fRef = f;                                    // f, or f/2 when an octave error is assumed
        boolean folded = false;                             // fRef is not what was measured
        if (tuning.chromatic()) {
            double mf = Tuning.midiOf(f, a4);
            tMidi = (int) Math.round(mf);
            if (!fresh && !pluck && midi >= 0 && Math.abs(mf - midi) < 0.5 + NOTE_KEEP) tMidi = midi;
        } else if (locked >= 0 && locked < tuning.count()) {
            tString = locked;
            tMidi = tuning.strings[locked];
        } else {
            int best = nearestString(f);
            if (Math.abs(centsTo(f, best)) > 150.0 && !fresh && !pluck && live && string >= 0 && string < tuning.count()) {
                // Mid-note jump to the octave above the string being followed: the detector lost
                // a weak fundamental. Only within a note: a fresh reading an octave above a
                // string is far more often a higher string tuned a whole step or two flat.
                int low = nearestString(f / 2.0);
                if (low == string && Math.abs(centsTo(f / 2.0, low)) < 60.0) {
                    best = low;
                    fRef = f / 2.0;
                    folded = true;
                }
            }
            tString = best;
            if (!fresh && !pluck && string >= 0 && string < tuning.count() && string != best) {
                double fOld = wasFolded ? f / 2.0 : f;     // the old string, read as it was read
                double cc = Math.abs(centsTo(fOld, string)), cn = Math.abs(centsTo(fRef, best));
                if (cc <= cn + STRING_KEEP) {
                    tString = string;
                    fRef = fOld;
                    folded = wasFolded;
                }
            }
            tMidi = tuning.strings[tString];
        }

        boolean change = tMidi != midi || tString != string;
        if (change && hasNote && !fresh && live) {
            // debounce: a different target must hold for two consecutive frames
            if (tMidi == pendMidi && tString == pendString) {
                pendCount++;
            } else {
                pendMidi = tMidi;
                pendString = tString;
                pendCount = 1;
            }
            if (pendCount < 2) {
                tMidi = midi;
                tString = string;
                change = false;
                fRef = f;                                  // measure against the old target
                folded = false;
            }
        } else if (!change) {
            pendMidi = pendString = -1;                    // the two frames must be consecutive
            pendCount = 0;
        }
        wasFolded = folded;
        if (change) {
            midi = tMidi;
            string = tString;
            pendCount = 0;
            pendMidi = pendString = -1;
            histN = 0;
            smoothed = false;
            inTune = false;
            stableSince = offSince = -1.0;
            events |= EV_TARGET;
        } else if (fresh) {
            histN = 0;
            smoothed = false;
        }

        double target = Tuning.freq(midi, a4);
        double raw = Tuning.cents(fRef, target);
        if (string >= 0 && foldOctaves && Math.abs(raw) >= 1100.0) {
            // A string cannot be an octave off. Locked, this is a 12th-fret harmonic and counts;
            // in automatic mode it is a stray frame against the previous string and does not.
            raw -= 1200.0 * Math.round(raw / 1200.0);
            if (locked < 0) folded = true;
        }

        // ---- smoothing: median of three, then a one-euro filter: its cutoff rises with the
        // rate of change, so the needle is calm at rest and keeps up while a peg turns ----
        if (histN < 3) histN++;
        hist[2] = hist[1];
        hist[1] = hist[0];
        hist[0] = raw;
        double med = histN < 3 ? raw : median(hist[0], hist[1], hist[2]);
        if (!smoothed) {
            cents = raw;
            prevMed = raw;
            slope = 0.0;
            smoothed = true;
        } else {
            slope += ((med - prevMed) / dt - slope) * alpha(1.0, dt);
            prevMed = med;
            double cutoff = (fast ? 1.5 : 0.5) + (fast ? 0.2 : 0.1) * Math.abs(slope);
            cents += (med - cents) * alpha(cutoff, dt);
        }
        hz = target * Math.pow(2.0, cents / 1200.0);
        live = true;
        hasNote = true;

        // ---- in tune ----
        double ac = Math.abs(cents);
        double exit = tolerance + Math.max(0.7, tolerance * 0.4);
        if (!inTune) {
            if (ac <= tolerance && !folded && t - lastOnset >= ATTACK) {
                if (stableSince < 0.0) stableSince = t;
                if (t - stableSince >= DWELL) {
                    inTune = true;
                    events |= EV_IN_TUNE;
                    if (string >= 0) tunedMask |= 1 << string;
                }
            } else {
                stableSince = -1.0;
            }
        } else if (ac > exit) {
            inTune = false;
            stableSince = -1.0;
        }
        // a string that drifted (tuning others pulls the neck) loses its mark
        // (only readings that can be this string: another string plucked meanwhile says nothing)
        if (string >= 0 && (tunedMask & (1 << string)) != 0 && ac > exit && ac < 300.0) {
            if (offSince < 0.0) offSince = t;
            if (t - offSince >= 0.4) tunedMask &= ~(1 << string);
        } else {
            offSince = -1.0;
        }
    }

    private int nearestString(double f) {
        int best = 0;
        double bc = Double.MAX_VALUE;
        for (int i = 0; i < tuning.count(); i++) {
            double c = Math.abs(centsTo(f, i));
            if (c < bc) {
                bc = c;
                best = i;
            }
        }
        return best;
    }

    private double centsTo(double f, int idx) {
        return Tuning.cents(f, Tuning.freq(tuning.strings[idx], a4));
    }

    /** Smoothing factor of a first-order low-pass at {@code hz} for a step of {@code dt}. */
    private static double alpha(double hz, double dt) {
        return 1.0 / (1.0 + 1.0 / (2.0 * Math.PI * hz * dt));
    }

    private static double median(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }
}
