package me.saatvik.tuner;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

import java.util.Arrays;

/**
 * Reference tone. A phone speaker hardly reproduces a low E (82 Hz), so the tone carries eight
 * harmonics: the ear hears the fundamental from them. Phase-continuous, with 20 ms fades and a
 * short glide when the pitch changes, so starting, stopping and switching strings never click.
 * While it plays it holds transient audio focus that lets other apps duck instead of pause.
 */
final class ToneSynth implements Runnable {

    interface Listener {
        /** Tone thread: the output could not be opened or stopped accepting samples. */
        void onToneFailed();
    }

    private static final double[] HARM = {1.0, 0.7, 0.55, 0.42, 0.3, 0.22, 0.15, 0.1};
    private static final double LEVEL = 0.45;
    private static final int BLOCK = 256;
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private final Listener listener;
    private final AudioManager audio;
    private volatile double freq = 440.0;
    private boolean on, running;          // guarded by this

    ToneSynth(Listener listener, AudioManager audio) {
        this.listener = listener;
        this.audio = audio;
    }

    /** True while a tone is wanted; false once stopped or after a failure. */
    synchronized boolean playing() {
        return on;
    }

    synchronized void play(double hz) {
        freq = hz;
        on = true;
        if (!running) {
            running = true;
            new Thread(this, "tuner-tone").start();
        }
    }

    synchronized void stop() {
        on = false;
    }

    private synchronized boolean wanted() {
        return on;
    }

    /** Called by the tone thread once it has faded out; false if play() came in meanwhile. */
    private synchronized boolean finish() {
        if (on) return false;
        running = false;
        return true;
    }

    /**
     * One run's audio focus. Each run owns its handle: a tone started right after another one
     * ended runs on a new thread, and abandoning by a shared handle would drop the new focus.
     */
    private static final class Focus {
        private final AudioManager am;
        private final AudioFocusRequest request;                           // API 26+
        private final AudioManager.OnAudioFocusChangeListener legacy;      // below 26

        Focus(AudioManager am) {
            this.am = am;
            if (Build.VERSION.SDK_INT >= 26) {
                legacy = null;
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(ATTRS)
                        .build();
                am.requestAudioFocus(request);
            } else {
                request = null;
                legacy = new AudioManager.OnAudioFocusChangeListener() {
                    @Override
                    public void onAudioFocusChange(int change) {
                    }
                };
                am.requestAudioFocus(legacy, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        }

        void abandon() {
            if (request != null) am.abandonAudioFocusRequest(request);
            else am.abandonAudioFocus(legacy);
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        int sr = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        if (sr <= 0) sr = 48000;
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min, sr / 20 * 2);
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(ATTRS)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (RuntimeException e) {
            if (track != null) track.release();
            fail();
            return;
        }
        Focus focus = new Focus(audio);

        double norm = 0;
        for (double h : HARM) norm += h;
        short[] buf = new short[BLOCK];
        double phase = 0.0, gain = 0.0, f = freq;
        double kGain = 1.0 - Math.exp(-1.0 / (0.02 * sr / 5.0));     // ~20 ms to settle
        // Fraction of the remaining pitch gap closed per block: the glide time constant is 30 ms.
        double kGlide = 1.0 - Math.pow(Math.exp(-1.0 / (0.03 * sr)), BLOCK);
        double scale = LEVEL * 32767.0 / norm;
        boolean failed = false;
        while (true) {
            boolean want = wanted();
            double target = want ? 1.0 : 0.0, ft = freq;
            // The pitch moves along a straight line in log frequency over the block.
            double ratio = Math.pow(ft / f, kGlide / BLOCK);
            for (int i = 0; i < buf.length; i++) {
                gain += (target - gain) * kGain;
                f *= ratio;
                phase += f / sr;
                if (phase >= 1.0) phase -= Math.floor(phase);
                double w = 2.0 * Math.PI * phase;
                // sin((k+1)w) from the two before it: one sin/cos pair serves every harmonic.
                double two = 2.0 * Math.cos(w), prev = 0.0, cur = Math.sin(w), v = 0.0;
                for (int k = 0; k < HARM.length; k++) {
                    if ((k + 1) * f > sr * 0.45) break;
                    v += HARM[k] * cur;
                    double next = two * cur - prev;
                    prev = cur;
                    cur = next;
                }
                buf[i] = (short) Math.round(v * gain * scale);
            }
            if (track.write(buf, 0, buf.length) < 0) {
                failed = true;                                  // e.g. the audio server restarted
                break;
            }
            if (!want && gain < 1e-4 && finish()) break;
        }
        if (!failed) {
            // Refill the whole buffer with silence: whatever the output still holds when the track
            // is stopped or released (some devices replay it) is then inaudible.
            int frames = track.getBufferSizeInFrames();
            Arrays.fill(buf, (short) 0);
            while (frames > 0) {
                int n = track.write(buf, 0, Math.min(frames, buf.length));
                if (n <= 0) break;
                frames -= n;
            }
        }
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
        focus.abandon();
        if (failed) fail();
    }

    private void fail() {
        synchronized (this) {
            running = false;
            on = false;
        }
        listener.onToneFailed();
    }
}
