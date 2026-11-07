package app.tuner.guitar;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

/**
 * Microphone capture on its own thread, feeding {@link PitchEngine}. VOICE_RECOGNITION is the source
 * the Android CDD requires to run without noise suppression or automatic gain control and with a
 * flat response from 100 Hz to 4 kHz; plain MIC is the fallback.
 *
 * <p>Every start opens a session with its own id and stop flag. A capture thread that outlives
 * its session (opening a microphone can block longer than {@link #stop} waits) can therefore
 * never rejoin a later one, and whatever it still reports carries a stale id the receiver drops.
 * A new session's thread waits for the previous thread to release the microphone first.
 */
final class AudioInput {

    interface Sink {
        /** UI thread, inside {@link #start}: a session is about to begin; time restarts at zero. */
        void onCaptureStart(int session);

        /** Capture thread: one analysed hop. */
        void onFrame(PitchEngine e, int session);

        /** Capture thread: the microphone could not be opened or stopped delivering. */
        void onFailure(int session);
    }

    private static final int[] RATES = {48000, 44100, 22050, 16000};
    private static final int[] SOURCES = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
    private static final int MAX_EMPTY_READS = 100;        // x 10 ms: the stream is stuck

    private static final class Session {
        final int id;
        volatile boolean run = true;

        Session(int id) {
            this.id = id;
        }
    }

    private final Sink sink;
    private Session current;                               // UI thread only
    private Thread last;                                   // newest capture thread, maybe winding down
    private int ids;
    private volatile double minHz = PitchEngine.F_MIN;

    /** Lowest pitch a single string of the selected tuning can sound (any thread). */
    void setMinHz(double hz) {
        minHz = hz;
    }

    AudioInput(Sink sink) {
        this.sink = sink;
    }

    boolean active() {
        return current != null;
    }

    boolean isCurrent(int session) {
        return current != null && current.id == session;
    }

    void start() {
        if (current != null) return;
        final Session s = new Session(++ids);
        final Thread prev = last;
        current = s;
        sink.onCaptureStart(s.id);
        last = new Thread(new Runnable() {
            @Override
            public void run() {
                capture(s, prev);
            }
        }, "tuner-mic");
        last.start();
    }

    /** Ends the session and waits briefly so the microphone is released before a restart. */
    void stop() {
        Session s = current;
        current = null;
        if (s == null) return;
        s.run = false;
        Thread t = last;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void capture(Session s, Thread prev) {
        if (prev != null) {
            try {
                prev.join(3000);
            } catch (InterruptedException e) {
                return;                                    // stopped while waiting
            }
        }
        if (!s.run) return;
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = open();
        if (rec == null) {
            if (s.run) sink.onFailure(s.id);
            return;
        }
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            rec.release();
            if (s.run) sink.onFailure(s.id);
            return;
        }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release();
            if (s.run) sink.onFailure(s.id);
            return;
        }
        PitchEngine engine = new PitchEngine(rec.getSampleRate());
        short[] buf = new short[512];
        boolean failed = false;
        int empty = 0;
        while (s.run) {
            int n = rec.read(buf, 0, buf.length);
            if (n < 0) {
                failed = true;
                break;
            }
            if (n == 0) {
                // A blocking read should not come back empty; if it keeps doing so, back off
                // rather than spin, and give up after a second.
                if (++empty > MAX_EMPTY_READS) {
                    failed = true;
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            empty = 0;
            engine.minHz = minHz;
            int off = 0;
            while (off < n) {
                off += engine.feed(buf, off, n - off);
                if (engine.ready && s.run) sink.onFrame(engine, s.id);
            }
        }
        try {
            rec.stop();
        } catch (IllegalStateException ignored) {
        }
        rec.release();
        if (failed && s.run) sink.onFailure(s.id);
    }

    private static AudioRecord open() {
        for (int source : SOURCES) {
            for (int rate : RATES) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioRecord r = null;
                try {
                    r = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min, rate / 5 * 2));         // 200 ms of headroom
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                } catch (RuntimeException ignored) {
                    // unsupported combination or permission revoked: try the next
                }
                if (r != null) r.release();
            }
        }
        return null;
    }
}
