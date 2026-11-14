package app.tuner.guitar;

import java.util.Arrays;

/**
 * Polyphonic check of a strum. No Android dependencies.
 *
 * <p>Given 0.51 - 0.74 s of audio taken after the attack of a strum (see
 * {@link PitchEngine#strumN}), it answers three questions:
 * <ol>
 * <li>Is this the open strings, or a fretted chord? Pitch-class profile against the open strings
 *     of every tuning and against major, minor, power and 7th chords.</li>
 * <li>If open strings: where is each string? Every string is searched within its own range
 *     (up to +-250 cents, never overlapping a neighbour's) by harmonic sum over spectral peaks.
 *     Open strings share partials (E2's 3rd is B3, its 4th is E4; A2's 3rd is E4 ...). A partial
 *     is credited to the string for which it is the lowest harmonic, the one that dominates it;
 *     the others skip it. Estimates are refined four times with the collisions recomputed.</li>
 * <li>Does another tuning fit clearly better (the guitar is in Drop D, or a half step down)?</li>
 * </ol>
 * Long window: 32768 samples at 48 kHz (0.68 s, 1.5 Hz bins), log-parabolic peak interpolation.
 * An instance holds the working state of one strum: make a new one for each.
 */
final class StrumAnalyzer {

    static final int KMAX = 8;
    private static final int ITERATIONS = 4;
    private static final int PARTIALS = 16;                    // harmonics of each string tracked
    private static final double FMIN = 55.0, FMAX = 2600.0;

    static final class Result {
        final int count;
        final double[] hz, cents;          // per string, NaN when not heard
        final double[] strength;           // harmonic sum, relative to the strongest string
        int heard;
        /** Notes the open strings of no tuning explain: a fretted chord, or not a guitar. */
        boolean chord;
        double openScore, chordScore;
        /** Index into {@link Tuning#ALL} of a tuning that fits clearly better, or -1. */
        int suggest = -1;

        Result(int n) {
            count = n;
            hz = new double[n];
            cents = new double[n];
            strength = new double[n];
            Arrays.fill(hz, Double.NaN);
            Arrays.fill(cents, Double.NaN);
        }
    }

    // spectral peaks, ascending frequency
    private double[] pf, pa;
    private int np;
    private double binHz, peakMax;

    /**
     * Analyses {@code x} (length a power of two) captured at {@code fs}. The strings are measured
     * against the selected tuning and, to recognise a guitar in another tuning, against every
     * tuning with as many strings.
     */
    Result analyze(float[] x, int fs, Tuning tuning, double a4) {
        int n = tuning.count();
        Result r = new Result(n);
        if (n == 0) {
            r.chord = true;
            return r;
        }
        lf = new double[n * PARTIALS];
        lm = new int[n * PARTIALS];
        ls = new int[n * PARTIALS];
        spectrum(x, fs);
        if (np < 3) {
            r.chord = true;
            return r;
        }
        estimate(r, tuning, a4);

        // the tuning the guitar is in: every string heard but one, all within 25 cents
        // Ties (an octave string is indistinguishable from the 2nd harmonic of the string an
        // octave below) go to the tuning that differs least from the selected one.
        double selWorst = worst(r);
        int best = -1, bestHeard = 0, bestDiff = 99;
        double bestWorst = 25.0;
        for (int ti = 0; ti < Tuning.ALL.length; ti++) {
            Tuning t = Tuning.ALL[ti];
            if (t.chromatic() || t.count() != n) continue;
            Result o = t == tuning ? r : estimate(new Result(n), t, a4);
            double w = worst(o);
            int diff = 0;
            for (int s = 0; s < n; s++) if (t.strings[s] != tuning.strings[s]) diff++;
            if (o.heard < n - 1 || w >= 25.0) continue;
            boolean better = o.heard > bestHeard
                    || o.heard == bestHeard && (diff < bestDiff || diff == bestDiff && w < bestWorst);
            if (better) {
                bestWorst = w;
                bestHeard = o.heard;
                bestDiff = diff;
                best = ti;
            }
        }
        chroma(r, tuning, a4);
        if (best >= 0) {
            Tuning t = Tuning.ALL[best];
            if (t == tuning || selWorst <= 60.0 && r.heard >= bestHeard) return r;   // open strings, this tuning
            // An open tuning is a major chord: far more often that chord, fretted.
            if (!t.openChord) {
                r.suggest = best;
                return r;
            }
        }
        // No tuning fits: a guitar well out of tune, or a fretted chord. Strings sitting a whole
        // number of semitones off while the others are in tune are fretted notes.
        // A single string a whole tone or more off, exactly, with the rest in tune: a fretted
        // note (Em7 is the open strings with the A string at the 2nd fret), not a string that
        // slipped. Telling someone to detune it would do harm; asking for one string does none.
        int shifted = 0, wide = 0;
        for (int s = 0; s < n; s++) {
            double c = Math.abs(r.cents[s]);
            if (c >= 80.0 && Math.abs(c - 100.0 * Math.round(c / 100.0)) < 12.0) {
                shifted++;
                if (c >= 150.0) wide++;
            }
        }
        r.chord = r.heard < n - 1 || shifted >= 2 || (shifted == 1 && (wide == 1 || r.chordScore > r.openScore))
                || r.chordScore > r.openScore + 0.02;
        return r;
    }

    private static double worst(Result r) {
        double w = 0.0;
        for (double c : r.cents) if (!Double.isNaN(c)) w = Math.max(w, Math.abs(c));
        return w;
    }

    // ---- spectrum and peaks ------------------------------------------------------------------

    private void spectrum(float[] x, int fs) {
        int N = x.length;
        double[] re = new double[N], im = new double[N];
        for (int i = 0; i < N; i++) re[i] = x[i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / N));
        new PitchEngine.Fft(N).transform(re, im);
        int half = N / 2;
        double[] mag = new double[half + 1];
        for (int k = 0; k <= half; k++) mag[k] = Math.hypot(re[k], im[k]);
        binHz = fs / (double) N;
        int lo = Math.max(2, (int) (FMIN / binHz)), hi = Math.min(half - 2, (int) (Math.min(FMAX, fs * 0.45) / binHz));

        double max = 0.0;
        for (int k = lo; k <= hi; k++) max = Math.max(max, mag[k]);
        double[] band = Arrays.copyOfRange(mag, lo, hi + 1);
        Arrays.sort(band);
        double noise = band[band.length / 2];
        double thr = Math.max(noise * 4.0, max * 1e-3);           // +12 dB over the median, -60 dB under the top

        pf = new double[(hi - lo + 2) / 2];                       // peaks are never adjacent
        pa = new double[pf.length];
        np = 0;
        peakMax = max;
        for (int k = lo; k <= hi; k++) {
            double m = mag[k];
            if (m <= thr || m <= mag[k - 1] || m < mag[k + 1]) continue;
            double a = Math.log(mag[k - 1] + 1e-30), b = Math.log(m + 1e-30), c = Math.log(mag[k + 1] + 1e-30);
            double d = PitchEngine.vertex(a, b, c);
            pf[np] = (k + d) * binHz;
            pa[np] = Math.exp(PitchEngine.peak(a, b, c, d));
            np++;
        }
    }

    /** Index of the peak nearest to f within +-tol Hz, or -1. */
    private int nearest(double f, double tol) {
        int lo = 0, hi = np - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (pf[mid] < f) lo = mid + 1;
            else hi = mid;
        }
        int best = -1;
        double bd = tol;
        for (int i = Math.max(0, lo - 1); i <= Math.min(np - 1, lo); i++) {
            double d = Math.abs(pf[i] - f);
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // ---- open strings or a chord -------------------------------------------------------------

    private static final double[] HARM_W = {1.0, 0.85, 0.72, 0.61, 0.52, 0.44};

    private void chroma(Result r, Tuning tuning, double a4) {
        double[] chroma = new double[12];
        for (int i = 0; i < np; i++) {
            int pc = Tuning.pitchClass((int) Math.round(Tuning.midiOf(pf[i], a4)));
            chroma[pc] += pa[i];
        }
        double chord = 0.0;
        int[][] shapes = {{0, 4, 7}, {0, 3, 7}, {0, 7}, {0, 4, 7, 10}, {0, 3, 7, 10}};
        for (int root = 0; root < 12; root++) {
            for (int[] sh : shapes) {
                int[] notes = new int[sh.length];
                for (int j = 0; j < sh.length; j++) notes[j] = 48 + root + sh[j];
                chord = Math.max(chord, cosine(chroma, template(notes)));
            }
        }
        r.openScore = cosine(chroma, template(tuning.strings));
        r.chordScore = chord;
    }

    /** Pitch-class profile of a set of notes with their first six harmonics. */
    private static double[] template(int[] notes) {
        double[] t = new double[12];
        for (int note : notes) {
            for (int k = 1; k <= HARM_W.length; k++) {
                int pc = Tuning.pitchClass(note + (int) Math.round(12.0 * Tuning.log2(k)));
                t[pc] += HARM_W[k - 1];
            }
        }
        return t;
    }

    private static double cosine(double[] a, double[] b) {
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return aa > 0 && bb > 0 ? ab / Math.sqrt(aa * bb) : 0.0;
    }

    // ---- per-string estimates ----------------------------------------------------------------

    // partials of all strings at the current estimates, ascending: frequency, harmonic, string
    private double[] lf;
    private int[] lm, ls;
    private int nl;

    private void partials(double[] est) {
        int n = est.length;
        nl = 0;
        for (int t = 0; t < n; t++) {
            for (int m = 1; m <= PARTIALS; m++) {
                lf[nl] = m * est[t];
                lm[nl] = m;
                ls[nl] = t;
                nl++;
            }
        }
        // insertion sort: ~100 entries
        for (int i = 1; i < nl; i++) {
            double f = lf[i];
            int m = lm[i], t = ls[i], j = i - 1;
            while (j >= 0 && lf[j] > f) {
                lf[j + 1] = lf[j];
                lm[j + 1] = lm[j];
                ls[j + 1] = ls[j];
                j--;
            }
            lf[j + 1] = f;
            lm[j + 1] = m;
            ls[j + 1] = t;
        }
    }

    /** Collision window: wide on the first pass, while the others are still at their nominal pitch. */
    private double collideRatio = 1.0;
    private boolean wide;

    /**
     * Other strings' partials within 30 cents of f, as seen by partial k of string s:
     * returns 0 when free, 1 when shared with higher harmonics of others (which are weaker),
     * 2 when claimed by a lower or equal harmonic of another string. {@link #pure} receives the
     * share left to s.
     */
    private int collide(int s, int k, double f) {
        // partials closer than ~2 bins merge into one peak; further apart they are resolved
        double ratio = Math.max(collideRatio, 1.0 + 2.2 * binHz / f);
        if (!wide) ratio = Math.min(ratio, 1.0 + Math.max(2.2 * binHz / f, 0.007));
        double lo = f / ratio, hi = f * ratio;
        int a = 0, b = nl;
        while (a < b) {
            int mid = (a + b) >>> 1;
            if (lf[mid] < lo) a = mid + 1;
            else b = mid;
        }
        int state = 0;
        pure = 1.0;
        for (int i = a; i < nl && lf[i] <= hi; i++) {
            int t = ls[i], m = lm[i];
            if (t == s) continue;
            if (m < k || (m == k && t < s)) return 2;
            state = 1;
            pure *= 1.0 - Math.min(0.9, (double) k / m);
        }
        return state;
    }

    private double pure;

    private Result estimate(Result r, Tuning tuning, double a4) {
        int n = tuning.count();
        double[] exp = new double[n], lo = new double[n], hi = new double[n], est = new double[n], sal = new double[n];
        for (int s = 0; s < n; s++) exp[s] = est[s] = Tuning.freq(tuning.strings[s], a4);
        for (int s = 0; s < n; s++) {
            double gap = 2400.0;
            for (int t = 0; t < n; t++) {
                if (t != s) gap = Math.min(gap, Math.abs(Tuning.cents(exp[t], exp[s])));
            }
            double range = Math.max(60.0, Math.min(250.0, gap / 2.0 - 10.0));
            lo[s] = exp[s] * Math.pow(2.0, -range / 1200.0);
            hi[s] = exp[s] * Math.pow(2.0, range / 1200.0);
        }
        boolean[] found = new boolean[n];
        double[] low = new double[n];
        for (int iter = 0; iter < ITERATIONS; iter++) {
            wide = iter == 0;
            collideRatio = Math.pow(2.0, (wide ? 60.0 : 12.0) / 1200.0);
            partials(est);
            double[] next = est.clone();
            for (int s = 0; s < n; s++) {
                double bestF = Double.NaN, bestS = 0.0;
                for (int i = 0; i < np; i++) {
                    for (int k = 1; k <= KMAX; k++) {
                        double f = pf[i] / k;
                        if (f < lo[s]) break;                              // k only lowers f further
                        if (f > hi[s]) continue;
                        double c = Tuning.cents(f, exp[s]);
                        double v = salience(s, f) / (1.0 + (c / 200.0) * (c / 200.0));   // most strums are near pitch
                        if (v > bestS) {
                            bestS = v;
                            bestF = f;
                        }
                    }
                }
                sal[s] = bestS;
                found[s] = !Double.isNaN(bestF);
                low[s] = found[s] ? ownLow(s, bestF) : 0.0;
                if (found[s]) {
                    double f = refine(s, bestF);
                    next[s] = f >= lo[s] && f <= hi[s] ? f : bestF;
                }
            }
            est = next;
        }
        double top = 0.0;
        for (int s = 0; s < n; s++) top = Math.max(top, sal[s]);
        r.heard = 0;
        for (int s = 0; s < n; s++) {
            r.strength[s] = top > 0 ? sal[s] / top : 0.0;
            // Heard: a clear harmonic sum, or (a string whose octaves belong to other strings,
            // like the low D of Drop D) a clear peak of its own among its first partials.
            if (found[s] && low[s] > 0.0 && (sal[s] >= 0.12 * top || low[s] >= 0.03 * peakMax)) {
                r.hz[s] = est[s];
                r.cents[s] = Tuning.cents(est[s], exp[s]);
                r.heard++;
            }
        }
        return r;
    }

    private double tolHz(double f) {
        return Math.max(binHz, f * 0.007);                          // 12 cents: room for inharmonicity
    }

    /**
     * A string counts as heard only with a peak of its own among its first three partials: a
     * string that is not sounding is otherwise "found" in the octaves of others.
     */
    private double ownLow(int s, double f) {
        double a = 0.0;
        for (int k = 1; k <= 3; k++) {
            int i = nearest(k * f, Math.min(tolHz(k * f), 0.3 * f));
            if (i >= 0 && collide(s, k, pf[i]) != 2) a = Math.max(a, pa[i]);
        }
        return a;
    }

    /** Harmonic sum over the peaks at the partials of f; peaks other strings explain count less. */
    private double salience(int s, double f) {
        double v = 0.0;
        for (int k = 1; k <= KMAX; k++) {
            double fk = k * f;
            if (fk > FMAX) break;
            int i = nearest(fk, Math.min(tolHz(fk), 0.3 * f));
            if (i < 0) continue;
            int c = collide(s, k, pf[i]);
            if (c == 2) continue;
            v += pa[i] / Math.sqrt(k) * (c == 1 ? 0.35 : 1.0);
        }
        return v;
    }

    /** Weighted mean of (partial / k) over the string's own partials near the candidate. */
    private double refine(int s, double f) {
        double sw = 0.0, sf = 0.0;
        for (int k = 1; k <= 6; k++) {
            double fk = k * f;
            if (fk > FMAX) break;
            int i = nearest(fk, Math.min(tolHz(fk), 0.3 * f));
            if (i < 0) continue;
            if (collide(s, k, pf[i]) == 2) continue;
            double w = pa[i] * pure;
            sw += w;
            sf += w * pf[i] / k;
        }
        return sw > 0.0 ? sf / sw : f;
    }
}
