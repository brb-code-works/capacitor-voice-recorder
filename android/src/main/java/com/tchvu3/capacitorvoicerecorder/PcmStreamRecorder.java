package com.tchvu3.capacitorvoicerecorder;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.Arrays;

/**
 * Continuous raw-PCM capture via AudioRecord. Unlike the MediaRecorder
 * path (one encoded file per start/stop cycle), this keeps the mic open
 * and emits fixed-size PCM frames, so utterance boundaries are decided
 * in JS with zero capture gaps. 16kHz mono PCM16 == Whisper's native
 * input format; frames are WAV-wrapped client-side before upload.
 */
public class PcmStreamRecorder {

    public interface FrameListener {
        void onFrame(byte[] pcm, double peak);
    }

    public static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 2048; // ~128ms @ 16kHz

    private AudioRecord audioRecord;
    private Thread readThread;
    private volatile boolean running = false;
    private final FrameListener listener;

    public PcmStreamRecorder(FrameListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start(boolean useVoiceComm) {
        if (running) return;
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, FRAME_SAMPLES * 2 * 4);
        // VOICE_COMMUNICATION is the SCO-routed source (Bluetooth headset
        // experiment); MIC is the fallback if device audio sounds worse.
        int source = useVoiceComm
                ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.MIC;
        audioRecord = new AudioRecord(source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            throw new IllegalStateException("AudioRecord init failed (source=" + source + ")");
        }
        audioRecord.startRecording();
        running = true;
        readThread = new Thread(this::readLoop, "pcm-stream-reader");
        readThread.start();
    }

    private void readLoop() {
        byte[] frame = new byte[FRAME_SAMPLES * 2];
        while (running) {
            int off = 0;
            while (running && off < frame.length) {
                int n = audioRecord.read(frame, off, frame.length - off);
                if (n <= 0) break;
                off += n;
            }
            if (!running || off == 0) break;
            byte[] out = (off == frame.length) ? frame.clone() : Arrays.copyOf(frame, off);
            double peak = 0;
            for (int i = 0; i + 1 < off; i += 2) {
                int s = (short) ((frame[i] & 0xFF) | (frame[i + 1] << 8));
                double a = Math.abs(s) / 32768.0;
                if (a > peak) peak = a;
            }
            FrameListener l = listener;
            if (l != null) l.onFrame(out, peak);
        }
    }

    public synchronized void stop() {
        running = false;
        if (readThread != null) {
            try { readThread.join(500); } catch (InterruptedException ignored) {}
            readThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
