package me.saatvik.tuner;

/**
 * Streaming pitch detector for guitar strings. No Android dependencies: fed PCM samples, it
 * produces one analysis per hop.
 *
 * Per hop (W/4 samples over a W-sample window, W ~ 85 ms):
 * 1. Band limit while streaming: 2nd-order Butterworth high-pass 25 Hz and low-pass 3 kHz.
 * 2. Normalized square difference function (McLeod) n(t) = 2 r(t) / m(t), with r from one packed
 *    complex FFT that also yields a Hann-windowed spectrum of the same frame.
 * 3. Period = first key maximum of n reaching 0.9 x the highest one.
 * 4. Octave check: a weak fundamental (phone microphones roll off below ~150 Hz) can make the
 *    half-period peak win. If the spectrum carries the odd harmonics of f/2 (or the non-multiples
 *    of 3 for f/3) and n confirms the longer period, the lower fundamental is taken.
 * 5. Refinement: the peak of n at m periods (m up to 8, lag <= W/2) is interpolated and divided
 *    by m, dividing the lag error by m.
 * 6. Voicing: level above an adaptive noise floor and clarity (peak height of n) above a
 *    threshold with hysteresis.
 * 7. Two strings ringing together (the last one still sounding while the next is plucked):
 *    their common period is often the most periodic lag (E2 + A2 repeat every 36 ms, A0), or
 *    lands on another string (B3 + E4 repeat at E2's period). Such a lag has neither its 1st
 *    nor its 2nd harmonic in the spectrum, which a real note always has. The notes are then
 *    the lowest harmonics of the composite that start a series of their own; the one followed
 *    is the one that just started (spectral growth since the onset), else the strongest.
 *    Its period is measured after cancelling the other note with a comb filter
 *    y(n) = x(n) - x(n - T_other), which leaves it alone and periodic. A string plucked on the
 *    harmonics of a ringing one (high E over low E) is found by the same growth test and
 *    measured after a comb sum that keeps only the harmonics the two share.
 * 8. Onsets: a jump of 6 dB in the energy of the newest hop over the hops two and three back, or
 *    a spectral flux over 0.22 (new partials while another string rings add only ~3 dB).
 * 9. Strums and chords. A single string turns periodic within a few hops of its onset; several
 *    at once are not periodic within the lag range, but their spectrum is all peaks (flatness
 *    under 0.14; claps, rustle and speech are flat, 0.2 - 0.6, and never count). The sound is
 *    polyphonic, and no pitch is reported until the next onset, when after the onset
 *    - at most one of hops 2..6 is voiced while at least two are loud and tonal, or
 *    - from hop 7 to 30, four loud tonal hops in a row are unvoiced (a slow strum), or
 *    - four hops within 24 of the onset look like three notes or more, or like a period below
 *      the lowest string (a chord whose notes share a period).
 *    From 150 ms after the onset a long window (up to 0.75 s) is handed over for
 *    {@link StrumAnalyzer}.
 */
final class PitchEngine {
    static final double F_MIN = 25.0, F_MAX = 1500.0;
    private static final double K_PICK = 0.90;
    private static final double CLARITY_ON = 0.80, CLARITY_HOLD = 0.68;
    private static final double SUB_RATIO = 0.06;
    private static final double SPEC_MAX = 1800.0;
    private static final double GATE_DB = 8.0, ABS_MIN_DB = -85.0, SILENT_DB = -110.0;
    private static final double ONSET_DB = 6.0, FLUX_ON = 0.22;
    private static final int POLY_HOPS = 6;                    // decide on hops 2..6 after an onset
    private static final int CHORD_HOPS = 24;                  // chord-like hops count until here

    final int fs, W, hop, N;
    private final int minLag, maxLag, nLen;

    private final Biquad hp, lp;
    private final double[] ring;
    private int pos, sinceHop;
    private long count;

    private final double[] frame, hann, re, im, px, spec, nsdf, prevSpec, specBefore, comb;
    private boolean haveBefore;
    private double focusHz = Double.NaN;            // the note followed while two ring together
    /** Positive change of the magnitude spectrum since the last frame over the current one: ~0 for a ringing note. */
    private double flux;
    /** Spectral flatness 60 Hz - 2 kHz: ~0 for strings (peaks), ~0.5 for noise, claps, rustle. */
    private double flatness;
    /** Three notes or more in this frame, or a period below the lowest string. */
    private boolean chordLike;
    /**
     * Lowest pitch a single string can sound, set from the selected tuning (lowest string, 600
     * cents of slack); {@link #F_MIN} for chromatic. The capture thread copies it from
     * {@link AudioInput} before every read.
     */
    double minHz = F_MIN;
    private double otherHz = Double.NaN;
    private final double[] pkF = new double[96], pkP = new double[96];
    private final Fft fft;

    // ---- results of the last analysis (read after feed() reports a frame) ---------------------
    /** Detected fundamental, Hz, valid when {@link #voiced}; otherwise the raw estimate or NaN. */
    double freq = Double.NaN;
    /** Height of the chosen NSDF peak, 0..1: how periodic the frame is. */
    double clarity;
    double levelDb = -120.0, floorDb = Double.NaN;
    boolean voiced;
    /** Seconds of digital silence (the system muted capture, or another app holds the microphone). */
    double silentSec;
    /** Audio time at the end of the analysed window, seconds since start. */
    double time;
    /** Diagnostics: 1 = octave check lowered the estimate, 0 otherwise. */
    int octaveFix;
    /** A pluck (or strum) began in the newest hop. */
    boolean onset;
    /** Several notes at once since the last onset; {@link #voiced} stays false meanwhile. */
    boolean poly;
    /** Set on the one frame that completes a strum window: a fresh array, length {@link #strumN}. */
    float[] strum;

    final int strumN;
    private final float[] hist;
    private int histPos;
    private final double[] hopDb = {-200.0, -200.0, -200.0};
    private int sinceOnset = 1 << 20, onsetVoiced, loudUnvoiced, onsetChords, onsetTonal;
    private long onsetSample, polySample;
    private boolean strumPending;

    PitchEngine(int sampleRate) {
        fs = sampleRate;
        int w = 1024;
        while (w < sampleRate * 0.08) w <<= 1;                   // 48 kHz and 44.1 kHz -> 4096
        W = w;
        hop = W / 4;
        N = 2 * W;
        maxLag = (int) Math.min(Math.floor(fs / F_MIN), W / 2.0 - 2);
        minLag = (int) Math.max(2, Math.floor(fs / F_MAX));
        nLen = W / 2 + 24;
        hp = new Biquad(true, 25.0, fs);
        lp = new Biquad(false, Math.min(3000.0, fs * 0.4), fs);
        ring = new double[W];
        frame = new double[W];
        hann = new double[W];
        for (int i = 0; i < W; i++) hann[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / W);
        re = new double[N];
        im = new double[N];
        px = new double[N / 2 + 1];
        spec = new double[N / 2 + 1];
        nsdf = new double[nLen];
        prevSpec = new double[N / 2 + 1];
        specBefore = new double[N / 2 + 1];
        comb = new double[W];
        fft = new Fft(N);
        // the longest power of two up to 0.75 s: 48 kHz 32768 (0.68 s, 1.5 Hz bins), 44.1 kHz
        // 32768, 22.05 kHz 16384, 16 kHz 8192 (0.51 s; a longer one asks too much of a strum)
        int sn = 8192;
        while (sn * 2 <= fs * 0.75) sn <<= 1;
        strumN = sn;
        hist = new float[2 * strumN];
    }

    /** Lowest frequency the window can hold two periods of. */
    double minFreq() {
        return fs / (double) maxLag;
    }

    /**
     * Feeds 16-bit PCM from {@code off}. Stops right after a sample that completes a hop and
     * returns the number of samples consumed; {@link #ready} then tells whether a frame was analysed.
     */
    int feed(short[] pcm, int off, int n) {
        ready = false;
        for (int i = 0; i < n; i++) {
            if (push(pcm[off + i] * (1.0 / 32768.0))) {
                ready = true;
                return i + 1;
            }
        }
        return n;
    }

    /** Whether the last {@link #feed} call completed a hop and analysed a frame. */
    boolean ready;

    private boolean push(double x) {
        double y = lp.run(hp.run(x));
        ring[pos] = y;
        pos = (pos + 1) & (W - 1);
        hist[histPos] = (float) y;
        histPos = (histPos + 1) & (hist.length - 1);
        count++;
        if (count < W) return false;
        if (++sinceHop < hop && count > W) return false;
        sinceHop = 0;
        analyze();
        return true;
    }

    // ---- analysis ----------------------------------------------------------------------------

    private void analyze() {
        time = count / (double) fs;
        double hopSec = hop / (double) fs;
        double ss = 0.0, cs = 0.0, minHop = Double.MAX_VALUE, newest = 0.0;
        for (int i = 0; i < W; i++) {
            double v = ring[(pos + i) & (W - 1)];
            frame[i] = v;
            ss += v * v;
            cs += v * v;
            if ((i + 1) % hop == 0) {
                minHop = Math.min(minHop, cs / hop);
                newest = cs / hop;
                cs = 0.0;
            }
        }
        levelDb = 10.0 * Math.log10(ss / W + 1e-30);
        silentSec = levelDb < SILENT_DB ? silentSec + hopSec : 0.0;

        octaveFix = 0;
        chordLike = false;
        otherHz = Double.NaN;
        double lag = ss > 1e-18 ? period(ss) : -1.0;
        flux = spectralFlux();
        flatness = flatness();
        // Below the lowest string nothing can sound alone: a period down there is shared by
        // several notes (E major strummed repeats at E1).
        if (lag > 0 && (fs / lag < minHz || polyphonic(fs / lag, otherHz))) chordLike = true;
        if (chordLike) {
            lag = -1.0;                                  // no single pitch to report
            clarity = 0.0;
        }
        if (lag > 0) {
            freq = fs / lag;
        } else {
            freq = Double.NaN;
            clarity = 0.0;
        }

        double gate = Math.max(ABS_MIN_DB, (Double.isNaN(floorDb) ? -70.0 : floorDb) + GATE_DB);
        boolean loud = levelDb > gate;
        double need = voiced ? CLARITY_HOLD : CLARITY_ON;
        voiced = loud && lag > 0 && clarity >= need && freq >= F_MIN && freq <= F_MAX;
        onsetsAndStrums(10.0 * Math.log10(newest + 1e-30), gate);
        if (onset) {
            System.arraycopy(prevSpec, 0, specBefore, 0, prevSpec.length);   // what rang before the pluck
            haveBefore = true;
            focusHz = Double.NaN;
        } else if (voiced) {
            focusHz = freq;
        } else if (!loud) {
            focusHz = Double.NaN;
        }
        System.arraycopy(spec, 0, prevSpec, 0, spec.length);

        // Noise floor: follows quiet frames down quickly, creeps up slowly; nearly frozen while
        // a note sounds so a long sustain does not close its own gate. A window holding any
        // digital silence (start of capture, input muted by the system) says nothing about the
        // room and is skipped: following it down would leave the gate open for minutes.
        if (10.0 * Math.log10(minHop + 1e-30) >= SILENT_DB) {
            if (Double.isNaN(floorDb)) {
                floorDb = Math.min(levelDb, -70.0);
            } else if (levelDb < floorDb) {
                floorDb += (levelDb - floorDb) * 0.3;
            } else {
                double rise = (voiced ? 0.3 : 3.0) * hopSec;
                floorDb += Math.min(levelDb - floorDb, rise);
            }
            floorDb = Math.max(floorDb, ABS_MIN_DB - GATE_DB);
        }
    }

    private void onsetsAndStrums(double newDb, double gate) {
        strum = null;
        // a jump in energy, or new partials (a string plucked while another rings: +3 dB at most)
        onset = newDb > gate + 3.0 && sinceOnset >= 4
                && (newDb - Math.max(hopDb[1], hopDb[2]) >= ONSET_DB || flux >= FLUX_ON);
        hopDb[2] = hopDb[1];
        hopDb[1] = hopDb[0];
        hopDb[0] = newDb;
        if (onset) {
            sinceOnset = 0;
            onsetSample = count - hop;
            onsetVoiced = loudUnvoiced = onsetChords = onsetTonal = 0;
            poly = strumPending = false;
            return;
        }
        if (sinceOnset < (1 << 20)) sinceOnset++;
        // Several strings are not periodic, but their spectrum is all peaks (flatness under
        // ~0.14). Claps, rustle and the consonants of speech are not periodic either, but flat
        // (0.2 - 0.6): they never count.
        boolean loud = levelDb > gate + 6.0, tonal = flatness < 0.14;
        if (sinceOnset >= 2 && sinceOnset <= POLY_HOPS) {
            if (voiced) onsetVoiced++;
            else if (loud && tonal) onsetTonal++;
        }
        loudUnvoiced = !voiced && loud && tonal ? loudUnvoiced + 1 : 0;
        if (chordLike && flatness < 0.25 && sinceOnset >= 2 && sinceOnset <= CHORD_HOPS) onsetChords++;
        if (!poly && loud && (sinceOnset == POLY_HOPS && onsetVoiced <= 1 && onsetTonal >= 2
                // a slow strum: one string alone at first, periodic, then the others join
                || sinceOnset > POLY_HOPS && sinceOnset <= 30 && loudUnvoiced >= 4
                // a chord whose notes share a period (D A D G A D) never settles on one string; only
                // soon after its pluck: later the count is stale (a swell of noise would complete it)
                || sinceOnset <= CHORD_HOPS && onsetChords >= 4)) {
            poly = true;
            strumPending = true;
            polySample = count;
        }
        if (poly) {
            voiced = false;
            if (levelDb < gate) poly = strumPending = false;            // rang out
        }
        // past the attack, and past the last string of a slow strum
        long start = Math.max(onsetSample + (long) (fs * 0.15), polySample - 2L * hop);
        if (strumPending && count >= start + strumN) {
            strumPending = false;
            float[] w = new float[strumN];
            int back = (int) (count - start);                           // samples since start
            int from = (histPos - back) & (hist.length - 1);
            for (int i = 0; i < strumN; i++) w[i] = hist[(from + i) & (hist.length - 1)];
            strum = w;
        }
    }

    /** Returns the refined period in samples, or -1. Sets {@link #clarity}. */
    private double period(double ss) {
        // Packed FFT: real part = frame (for r), imaginary part = Hann-windowed frame (for spec).
        for (int i = 0; i < W; i++) {
            re[i] = frame[i];
            im[i] = frame[i] * hann[i];
        }
        for (int i = W; i < N; i++) {
            re[i] = 0.0;
            im[i] = 0.0;
        }
        fft.transform(re, im);
        int mask = N - 1;
        for (int k = 0; k <= N / 2; k++) {
            int j = (N - k) & mask;
            double a = re[k], b = im[k], c = re[j], d = im[j];
            double sr = a + c, si = b - d, dr = a - c, di = b + d;
            px[k] = (sr * sr + si * si) * 0.25;
            spec[k] = (dr * dr + di * di) * 0.25;
        }
        // The power spectrum is real and even, so its transform is N x the autocorrelation. A real
        // input needs only a half-size complex FFT: even samples as the real part, odd as the
        // imaginary part, then split into the two real transforms and recombined.
        int half = N / 2;
        for (int i = 0; i < half; i++) {
            int a = 2 * i, b = a + 1;
            re[i] = px[a <= half ? a : N - a];
            im[i] = px[b <= half ? b : N - b];
        }
        fft.transform(re, im, half);
        double invN = 1.0 / N;
        double m = 2.0 * ss;
        for (int t = 0; t < nLen; t++) {
            int j = (half - t) & (half - 1);
            double dr = re[t] - re[j], di = im[t] + im[j];
            double r = 0.5 * (re[t] + re[j]) + fft.cos[t] * 0.5 * di - fft.sin[t] * 0.5 * dr;
            if (t > 0) m -= frame[t - 1] * frame[t - 1] + frame[W - t] * frame[W - t];
            nsdf[t] = m > 1e-20 ? 2.0 * r * invN / m : 0.0;
        }

        // key maxima: highest point of each positive lobe after the first zero crossing
        int t = 1;
        while (t < maxLag && nsdf[t] > 0.0) t++;
        double best = 0.0;
        int nKey = 0;
        while (t < maxLag && nKey < keyLag.length) {
            while (t < maxLag && nsdf[t] <= 0.0) t++;
            if (t >= maxLag) break;
            int bi = t;
            double bv = nsdf[t];
            while (t < maxLag && nsdf[t] > 0.0) {
                if (nsdf[t] > bv) {
                    bv = nsdf[t];
                    bi = t;
                }
                t++;
            }
            if (bi >= maxLag - 1) break;                  // lobe cut off by the lag range
            if (bi >= minLag) {
                keyLag[nKey] = bi;
                keyVal[nKey] = bv;
                nKey++;
                if (bv > best) best = bv;
            }
        }
        if (nKey == 0 || best < 0.3) {
            clarity = best;
            return -1.0;
        }
        int pick = 0;
        for (int i = 0; i < nKey; i++) {
            if (keyVal[i] >= K_PICK * best) {
                pick = i;
                break;
            }
        }
        double p = interp(keyLag[pick]);
        clarity = Math.min(1.0, peakValue);

        // octave check, f/2 then f/3
        for (int d = 2; d <= 3; d++) {
            double g = fs / (p * d);
            if (g < F_MIN || p * d > maxLag) continue;
            double r = subharmonicRatio(g, d);
            if (r < SUB_RATIO) continue;
            int at = localMax(p * d, 2 + d);
            if (at < 0 || nsdf[at] < 0.6 || nsdf[at] < 0.8 * clarity) continue;
            double lp2 = interp(at);
            p = lp2 / d;
            clarity = Math.min(1.0, Math.max(clarity, peakValue));
            octaveFix = 1;
            break;
        }

        double resolved = resolveComposite(p);
        if (resolved != 0.0) return resolved;

        // multi-period refinement
        int mm = (int) Math.min(8.0, Math.floor((W / 2.0) / p));
        if (mm >= 2) {
            int at = localMax(p * mm, 2 + mm / 2);
            if (at > 0 && nsdf[at] >= 0.5) {
                double lm = interp(at);
                double pr = lm / mm;
                if (Math.abs(pr - p) < 0.02 * p) p = pr;
            }
        }
        return p;
    }

    /**
     * Positive change of the magnitude spectrum since the last frame, over the current magnitude
     * (60 Hz - 3 kHz): about 0 for a ringing note, large when new partials appear.
     */
    private double spectralFlux() {
        double binPerHz = N / (double) fs;
        int lo = (int) (60.0 * binPerHz), hi = Math.min(N / 2, (int) (3000.0 * binPerHz));
        double up = 0.0, all = 0.0;
        for (int k = lo; k <= hi; k++) {
            double m = Math.sqrt(spec[k]), d = m - Math.sqrt(prevSpec[k]);
            if (d > 0.0) up += d;
            all += m;
        }
        return all > 0.0 ? up / all : 0.0;
    }

    private double flatness() {
        double binPerHz = N / (double) fs;
        int lo = (int) (60.0 * binPerHz), hi = Math.min(N / 2, (int) (2000.0 * binPerHz));
        double logSum = 0.0, sum = 0.0;
        int n = 0;
        for (int k = lo; k <= hi; k++) {
            double v = spec[k] + 1e-30;
            logSum += Math.log(v);
            sum += v;
            n++;
        }
        return n > 0 && sum > 0 ? Math.exp(logSum / n) / (sum / n) : 1.0;
    }

    private final double[] harm = new double[17], harmBefore = new double[17];

    /**
     * Returns 0 when lag p is a real note's period. Otherwise p is a common period of several
     * notes: returns the period of the note to follow, measured with the others cancelled, or
     * -1 when no note stands out.
     */
    private double resolveComposite(double p) {
        double f = fs / p;
        int kc = (int) Math.min(16.0, Math.floor(Math.min(SPEC_MAX, fs * 0.45) / f));
        if (kc < 4) return 0.0;
        double binPerHz = N / (double) fs;
        double hw = Math.min(N / (double) W, 0.3 * f * binPerHz);
        double max = 0.0;
        for (int k = 1; k <= kc; k++) {
            harm[k] = localPeak(spec, k * f * binPerHz, hw);
            harmBefore[k] = haveBefore ? localPeak(specBefore, k * f * binPerHz, hw) : 0.0;
            max = Math.max(max, harm[k]);
        }
        if (max <= 0.0) return 0.0;
        // Below the lowest string, with no fundamental of its own, the 2nd harmonic is a note too:
        // D2 + A2 ringing (Drop D, DADGAD) repeat every D1 period, which has D2 for its 2nd.
        // Both readings need one of the notes ringing before the latest pluck, on this period's
        // harmonics: a single string (a slack one too) plucked from silence is one note.
        boolean before = false;
        for (int k = 2; k <= kc && !before; k++) before = harmBefore[k] >= 0.01 * max;      // -20 dB
        boolean below = before && f < minHz && harm[1] < 0.02 * max;
        // Two notes a fifth apart, D3 + A3, repeat every D2 period with no D2 in them: only
        // multiples of 2 and 3 sound. A real note with a weak fundamental has its 5th and 7th too.
        boolean fifth = before && !below && kc >= 7 && harm[1] < 0.02 * max && harm[2] >= 0.05 * max && harm[3] >= 0.05 * max;
        for (int k = 5; fifth && k <= kc; k++) {
            if (k % 2 != 0 && k % 3 != 0 && harm[k] >= 0.005 * max) fifth = false;     // -23 dB
        }
        // the note followed so far sits at f itself: its own tail with dull upper partials (a
        // pluck at a fifth of the string, a steep microphone roll-off), not two notes
        if (fifth && !Double.isNaN(focusHz) && Math.abs(Tuning.cents(focusHz, f)) < 60.0) fifth = false;
        if (!below && !fifth && (harm[1] >= 0.02 * max || harm[2] >= 0.02 * max)) return upperNote(p, f, kc, max);
        int kFrom = harm[2] >= 0.02 * max ? 2 : 3;

        // notes: harmonics of the composite that start a series of their own
        int[] cand = candBuf;
        int nc = 0;
        for (int k = kFrom; k <= kc && nc < cand.length; k++) {
            if (harm[k] < 0.05 * max) continue;
            boolean multiple = false;
            for (int i = 0; i < nc; i++) multiple |= k % cand[i] == 0;
            if (!multiple) cand[nc++] = k;
        }
        // a note whose own fundamental is too weak to show (E2 on a phone) appears as two of its
        // harmonics, k = 6 and 9: they share the divisor 3, which is the note
        for (boolean merged = true; merged && nc >= 2; ) {
            merged = false;
            for (int i = 0; i < nc && !merged; i++) {
                for (int j = i + 1; j < nc && !merged; j++) {
                    int g = gcd(cand[i], cand[j]);
                    if (g < 3) continue;
                    cand[i] = g;
                    cand[j] = cand[--nc];
                    merged = true;
                }
            }
        }
        java.util.Arrays.sort(cand, 0, nc);
        if (nc == 0) return -1.0;
        if (nc == 1) return p / cand[0];                // one note seen through a subharmonic lag
        if (nc >= 3) {
            chordLike = true;                           // three notes or more: a chord
            return -1.0;
        }

        int pick = -1, other = -1;
        double best = -1.0;
        if (!Double.isNaN(focusHz)) {
            for (int i = 0; i < nc; i++) {
                double c = Math.abs(Tuning.cents(cand[i] * f, focusHz));
                if (c < 60.0 && (pick < 0 || c < best)) {
                    pick = i;
                    best = c;
                }
            }
        }
        if (pick < 0) {
            // just after a pluck: the note whose partials grew; otherwise the strongest
            boolean recent = haveBefore && sinceOnset < 12;
            best = -1.0;
            for (int i = 0; i < nc; i++) {
                double v = 0.0;
                for (int j = cand[i]; j <= kc; j += cand[i]) v += recent ? Math.max(0.0, harm[j] - harmBefore[j]) : harm[j];
                if (v > best) {
                    best = v;
                    pick = i;
                }
            }
        }
        double sb = -1.0;
        for (int i = 0; i < nc; i++) {
            if (i == pick) continue;
            double v = 0.0;
            for (int j = cand[i]; j <= kc; j += cand[i]) v += harm[j];
            if (v > sb) {
                sb = v;
                other = i;
            }
        }
        double lagC = p / cand[pick], lagO = p / cand[other];
        otherHz = fs / lagO;
        double lag = cancelAndMeasure(lagC, lagO);
        return lag > 0.0 ? lag : -1.0;
    }

    private final int[] candBuf = new int[8];

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * True when the spectrum holds more than the note at f (and the second note at f2, if one
     * rings): at least a fifth of the peak power off both harmonic series, after also allowing
     * one more series from the strongest unexplained peak. One or two strings: false; a chord: true.
     */
    private boolean polyphonic(double f, double f2) {
        double binPerHz = N / (double) fs;
        int lo = Math.max(2, (int) (60.0 * binPerHz)), hi = Math.min(N / 2 - 1, (int) (2000.0 * binPerHz));
        double max = 0.0;
        for (int k = lo; k <= hi; k++) max = Math.max(max, spec[k]);
        if (max <= 0.0) return false;
        int np = 0;
        double total = 0.0;
        for (int k = lo; k <= hi && np < pkF.length; k++) {
            double v = spec[k];
            if (v < 1e-2 * max || v <= spec[k - 1] || v < spec[k + 1]) continue;     // -20 dB: clear of window sidelobes
            double d = vertex(Math.log(spec[k - 1] + 1e-300), Math.log(v), Math.log(spec[k + 1] + 1e-300));
            pkF[np] = (k + d) / binPerHz;
            pkP[np] = v;
            total += v;
            np++;
        }
        if (np < 3) return false;
        double off = 0.0, strongest = 0.0, uf = 0.0;
        for (int i = 0; i < np; i++) {
            if (onSeries(pkF[i], f) || !Double.isNaN(f2) && onSeries(pkF[i], f2)) continue;
            off += pkP[i];
            if (pkP[i] > strongest) {
                strongest = pkP[i];
                uf = pkF[i];
            }
        }
        if (off <= 0.12 * total) return false;
        if (!Double.isNaN(f2)) return true;
        // a second string ringing, whose fundamental (or 2nd, 3rd harmonic) is the strongest stray
        // peak; a fundamental below the lowest string is no string (55 Hz, or lower on a bass)
        for (int d = 1; d <= 3; d++) {
            double g = uf / d;
            if (g < Math.min(55.0, minHz)) break;
            double rest = 0.0;
            for (int i = 0; i < np; i++) {
                if (!onSeries(pkF[i], f) && !onSeries(pkF[i], g)) rest += pkP[i];
            }
            if (rest <= 0.08 * total) return false;
        }
        return true;
    }

    /** Peak at x lies on the harmonic series of f (within 40 cents, a little more up high). */
    private static boolean onSeries(double x, double f) {
        double k = Math.round(x / f);
        if (k < 1.0) return false;
        double c = Math.abs(Tuning.cents(x, k * f));
        return c < 40.0 + 2.0 * k;
    }

    /**
     * A real note at f, but maybe with a newer one on its harmonics: high E plucked while low E
     * rings has the same period. Just after a pluck, growth confined to every d-th harmonic is a
     * new note at d*f; it stays followed (via {@link #focusHz}) until the next pluck.
     */
    private double upperNote(double p, double f, int kc, double max) {
        int d = 0;
        if (!Double.isNaN(focusHz)) {
            double r = focusHz / f;
            int ri = (int) Math.round(r);
            if (ri >= 2 && ri <= 4 && Math.abs(Tuning.cents(r, ri)) < 30.0) d = ri;
        }
        if (d == 0 && haveBefore && sinceOnset >= 1 && sinceOnset < 12) {
            double total = 0.0;
            for (int k = 1; k <= kc; k++) total += harm[k];
            for (int cd = 4; cd >= 2 && d == 0; cd--) {             // largest first: 4f growth is also 2f growth
                double in = 0.0, out = 0.0, outNow = 0.0, outBefore = 0.0;
                for (int k = 1; k <= kc; k++) {
                    double g = Math.max(0.0, harm[k] - harmBefore[k]);
                    if (k % cd == 0) {
                        in += g;
                    } else {
                        out += g;
                        outNow += harm[k];
                        outBefore += harmBefore[k];
                    }
                }
                // ... and the lower note was already ringing (not a fresh note with a weak fundamental)
                if (in > 4.0 * out && in > 0.3 * total && outBefore > 0.3 * outNow) d = cd;
            }
        }
        if (d == 0) return 0.0;
        int at = localMax(p / d, 3);
        if (at < 0 || nsdf[at] < 0.5) return 0.0;
        double lag = interp(at);
        double fine = combSumAndMeasure(p, d, lag);
        if (fine > 0.0) {
            lag = fine;
        } else {
            clarity = Math.min(1.0, Math.max(clarity, peakValue));
        }
        return lag;
    }

    /**
     * Period near lagC of the new note at d times the ringing one (period p), with the ringing
     * note's other harmonics removed: the sum of d copies delayed by p/d passes only harmonics
     * of p that are multiples of d. Sets {@link #clarity}.
     */
    private double combSumAndMeasure(double p, int d, double lagC) {
        double step = p / d;
        int j0 = (int) Math.ceil((d - 1) * step) + 1, len = W - j0;
        if (len < 3 * lagC) return -1.0;
        for (int j = 0; j < len; j++) {
            int n = j + j0;
            double v = frame[n];
            for (int t = 1; t < d; t++) {
                double at = n - t * step;
                int i = (int) Math.floor(at);
                double fr = at - i;
                v += (1.0 - fr) * frame[i] + fr * frame[i + 1];
            }
            comb[j] = v;
        }
        return measureComb(len, lagC);
    }

    /**
     * Period near lagC of what remains after removing everything periodic at lagO, by the NSDF
     * of y(n) = x(n) - x(n - lagO) evaluated directly at a few lags. Sets {@link #clarity}.
     */
    private double cancelAndMeasure(double lagC, double lagO) {
        int io = (int) Math.floor(lagO);
        double d = lagO - io;
        int j0 = io + 1, len = W - j0;
        if (len < 3 * lagC) return -1.0;
        for (int j = 0; j < len; j++) {
            int n = j + j0;
            comb[j] = frame[n] - ((1.0 - d) * frame[n - io] + d * frame[n - io - 1]);
        }
        return measureComb(len, lagC);
    }

    /** Period near lagC of comb[0..len), by the direct NSDF at a multiple of it. Sets {@link #clarity}. */
    private double measureComb(int len, double lagC) {
        // the longest multiple of the period that leaves half the frame overlapping
        int m = (int) Math.max(1.0, Math.min(8.0, Math.floor(len / 2.0 / lagC)));
        double at = lagC * m;
        int c = (int) Math.round(at);
        int bi = -1;
        double bv = -2.0;
        for (int t = c - 2 - m / 2; t <= c + 2 + m / 2; t++) {
            double v = directNsdf(comb, len, t);
            if (v > bv) {
                bv = v;
                bi = t;
            }
        }
        if (bi < 2) return -1.0;
        double a = directNsdf(comb, len, bi - 1), b = bv, e = directNsdf(comb, len, bi + 1);
        double off = vertex(a, b, e);
        double one = directNsdf(comb, len, (int) Math.round(lagC));
        clarity = Math.min(1.0, Math.max(one, peak(a, b, e, off)));
        double lag = (bi + off) / m;
        return Math.abs(lag - lagC) < 0.03 * lagC ? lag : -1.0;
    }

    private static double directNsdf(double[] y, int len, int t) {
        if (t <= 0 || t >= len) return 0.0;
        double r = 0.0, m = 0.0;
        for (int j = 0; j + t < len; j++) {
            double a = y[j], b = y[j + t];
            r += a * b;
            m += a * a + b * b;
        }
        return m > 1e-20 ? 2.0 * r / m : 0.0;
    }

    private final int[] keyLag = new int[256];
    private final double[] keyVal = new double[256];
    private double peakValue;

    /** Index of the largest interior local maximum of n within +-h of lag c, or -1. */
    private int localMax(double c, int h) {
        int ci = (int) Math.round(c);
        int lo = Math.max(1, ci - h), hi = Math.min(nLen - 2, ci + h);
        int bi = -1;
        double bv = -2.0;
        for (int i = lo; i <= hi; i++) {
            if (nsdf[i] > bv && nsdf[i] >= nsdf[i - 1] && nsdf[i] >= nsdf[i + 1]) {
                bv = nsdf[i];
                bi = i;
            }
        }
        return bi;
    }

    /** Parabolic interpolation around index i; sets {@link #peakValue}. */
    private double interp(int i) {
        double a = nsdf[i - 1], b = nsdf[i], c = nsdf[i + 1];
        double d = vertex(a, b, c);
        peakValue = peak(a, b, c, d);
        return i + d;
    }

    /**
     * Offset in samples or bins (-0.5..0.5) of the vertex of the parabola through three equally
     * spaced points, 0 unless the middle one is a strict maximum.
     */
    static double vertex(double a, double b, double c) {
        double den = a - 2.0 * b + c;
        return den < 0.0 ? Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / den)) : 0.0;
    }

    /** Height of that parabola at offset d from the middle point. */
    static double peak(double a, double b, double c, double d) {
        return b - 0.25 * (a - c) * d;
    }

    /**
     * Power at the harmonics of g that are not multiples of d, over the power at those that are
     * (the harmonics of d*g). Near zero when d*g is the true fundamental.
     */
    private double subharmonicRatio(double g, int d) {
        double binPerHz = N / (double) fs;
        double hw = Math.min(N / (double) W, 0.3 * g * binPerHz);
        int kMax = (int) (Math.min(SPEC_MAX, fs * 0.45) / g);
        double on = 0.0, off = 0.0;
        for (int k = 1; k <= kMax; k++) {
            double pw = peakPower(spec, k * g * binPerHz, hw);
            if (k % d == 0) on += pw;
            else off += pw;
        }
        return on > 0.0 ? off / on : 0.0;
    }

    /**
     * Power of a spectral peak within +-hw bins of c, or 0 when the largest value there is only
     * the skirt of a peak outside the range (the window's main lobe is +-4 bins wide).
     */
    private double localPeak(double[] sp, double c, double hw) {
        int lo = Math.max(2, (int) Math.floor(c - hw)), hi = Math.min(N / 2 - 1, (int) Math.ceil(c + hw));
        int bi = -1;
        double best = 0.0;
        for (int i = lo; i <= hi; i++) {
            if (sp[i] > best) {
                best = sp[i];
                bi = i;
            }
        }
        if (bi < 0 || sp[bi] < sp[bi - 1] || sp[bi] < sp[bi + 1]) return 0.0;
        return best;
    }

    private double peakPower(double[] sp, double c, double hw) {
        int lo = Math.max(1, (int) Math.floor(c - hw)), hi = Math.min(N / 2, (int) Math.ceil(c + hw));
        double best = 0.0;
        for (int i = lo; i <= hi; i++) if (sp[i] > best) best = sp[i];
        return best;
    }

    /** In-place radix-2 forward FFT with the twiddle factors of one size, shared by the pitch and strum analysis. */
    static final class Fft {
        private final int n;
        /** cos and sin of 2 pi k / n, k < n / 2. */
        final double[] cos, sin;

        Fft(int n) {
            this.n = n;
            cos = new double[n / 2];
            sin = new double[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = Math.cos(2.0 * Math.PI * i / n);
                sin[i] = Math.sin(2.0 * Math.PI * i / n);
            }
        }

        void transform(double[] xr, double[] xi) {
            transform(xr, xi, n);
        }

        /** Transforms the first {@code size} points, a power of two dividing n. */
        void transform(double[] xr, double[] xi, int size) {
            int shift = 32 - Integer.numberOfTrailingZeros(size);
            for (int i = 0; i < size; i++) {
                int j = Integer.reverse(i) >>> shift;
                if (j > i) {
                    double tr = xr[i];
                    xr[i] = xr[j];
                    xr[j] = tr;
                    double ti = xi[i];
                    xi[i] = xi[j];
                    xi[j] = ti;
                }
            }
            for (int len = 2; len <= size; len <<= 1) {
                int half = len >> 1, step = n / len;
                for (int i = 0; i < size; i += len) {
                    for (int j = 0, k = 0; j < half; j++, k += step) {
                        double wr = cos[k], wi = -sin[k];
                        int a = i + j, b = a + half;
                        double tr = xr[b] * wr - xi[b] * wi;
                        double ti = xr[b] * wi + xi[b] * wr;
                        xr[b] = xr[a] - tr;
                        xi[b] = xi[a] - ti;
                        xr[a] += tr;
                        xi[a] += ti;
                    }
                }
            }
        }
    }

    /** 2nd-order Butterworth section (RBJ), direct form I in double precision. */
    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        Biquad(boolean high, double fc, double fs) {
            double w = 2.0 * Math.PI * fc / fs, cs = Math.cos(w), al = Math.sin(w) / (2.0 * Math.sqrt(0.5));
            double a0 = 1.0 + al;
            if (high) {
                b0 = (1.0 + cs) / 2.0 / a0;
                b1 = -(1.0 + cs) / a0;
            } else {
                b0 = (1.0 - cs) / 2.0 / a0;
                b1 = (1.0 - cs) / a0;
            }
            b2 = b0;
            a1 = -2.0 * cs / a0;
            a2 = (1.0 - al) / a0;
        }

        double run(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            if (Math.abs(y) < 1e-25) y = 0.0;           // no denormals in silence
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }

        void clear() {
            x1 = x2 = y1 = y2 = 0.0;
        }
    }
}
