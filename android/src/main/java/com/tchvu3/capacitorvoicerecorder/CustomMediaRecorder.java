package com.tchvu3.capacitorvoicerecorder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomMediaRecorder implements AudioManager.OnAudioFocusChangeListener {

    private final Context context;
    private final RecordOptions options;
    private MediaRecorder mediaRecorder;
    private File outputFile;
    private CurrentRecordingStatus currentRecordingStatus = CurrentRecordingStatus.NONE;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Runnable onInterruptionBegan;
    private Runnable onInterruptionEnded;

    public CustomMediaRecorder(Context context, RecordOptions options) throws IOException {
        this.context = context;
        this.options = options;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        generateMediaRecorder();
    }

    public void setOnInterruptionBegan(Runnable callback) {
        this.onInterruptionBegan = callback;
    }

    public void setOnInterruptionEnded(Runnable callback) {
        this.onInterruptionEnded = callback;
    }

    private void generateMediaRecorder() throws IOException {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(96000);
        mediaRecorder.setAudioSamplingRate(44100);
        setRecorderOutputFile();
        mediaRecorder.prepare();
    }

    private void setRecorderOutputFile() throws IOException {
        File outputDir = context.getCacheDir();
        String directory = options.getDirectory();
        String subDirectory = options.getSubDirectory();

        if (directory != null) {
            outputDir = this.getDirectory(directory);
            if (subDirectory != null) {
                Pattern pattern = Pattern.compile("^/?(.+[^/])/?$");
                Matcher matcher = pattern.matcher(subDirectory);
                if (matcher.matches()) {
                    options.setSubDirectory(matcher.group(1));
                    outputDir = new File(outputDir, matcher.group(1));
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }
                }
            }
        }

        outputFile = File.createTempFile(String.format("recording-%d", System.currentTimeMillis()), ".aac", outputDir);

        if (directory == null) {
            outputFile.deleteOnExit();
        }

        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
    }

    private File getDirectory(String directory) {
        return switch (directory) {
            case "DOCUMENTS" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            case "DATA", "LIBRARY" -> context.getFilesDir();
            case "CACHE" -> context.getCacheDir();
            case "EXTERNAL" -> context.getExternalFilesDir(null);
            case "EXTERNAL_STORAGE" -> Environment.getExternalStorageDirectory();
            default -> null;
        };
    }

    public void startRecording() {
        // Request audio focus before starting recording
        requestAudioFocus();

        mediaRecorder.start();
        currentRecordingStatus = CurrentRecordingStatus.RECORDING;
    }

    public void stopRecording() {
        mediaRecorder.stop();
        mediaRecorder.release();

        // Abandon audio focus when stopping recording
        abandonAudioFocus();

        currentRecordingStatus = CurrentRecordingStatus.NONE;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public RecordOptions getRecordOptions() {
        return options;
    }

    public boolean pauseRecording() throws NotSupportedOsVersion {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion();
        }

        if (currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
            mediaRecorder.pause();
            currentRecordingStatus = CurrentRecordingStatus.PAUSED;
            return true;
        } else {
            return false;
        }
    }

    public boolean resumeRecording() throws NotSupportedOsVersion {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion();
        }

        if (currentRecordingStatus == CurrentRecordingStatus.PAUSED || currentRecordingStatus == CurrentRecordingStatus.INTERRUPTED) {
            // Re-request audio focus before resuming to ensure we have proper audio access
            requestAudioFocus();

            mediaRecorder.resume();
            currentRecordingStatus = CurrentRecordingStatus.RECORDING;
            return true;
        } else {
            return false;
        }
    }

    public CurrentRecordingStatus getCurrentStatus() {
        return currentRecordingStatus;
    }

    /**
     * Peak amplitude since the last call (0..32767). Safe to call in any
     * state: returns 0 unless actively recording (getMaxAmplitude on a
     * released MediaRecorder throws).
     */
    public int getMaxAmplitude() {
        if (currentRecordingStatus != CurrentRecordingStatus.RECORDING) return 0;
        try {
            return mediaRecorder.getMaxAmplitude();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean deleteOutputFile() {
        return outputFile.delete();
    }

    public static boolean canPhoneCreateMediaRecorder(Context context) {
        return true;
    }

    private static boolean canPhoneCreateMediaRecorderWhileHavingPermission(Context context) {
        CustomMediaRecorder tempMediaRecorder = null;
        try {
            tempMediaRecorder = new CustomMediaRecorder(context, new RecordOptions(null, null));
            tempMediaRecorder.startRecording();
            tempMediaRecorder.stopRecording();
            return true;
        } catch (Exception exp) {
            return exp.getMessage().startsWith("stop failed");
        } finally {
            if (tempMediaRecorder != null) tempMediaRecorder.deleteOutputFile();
        }
    }

    private void requestAudioFocus() {
        if (audioManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use AudioFocusRequest for API 26+
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(this)
                .build();

            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            // Use legacy API for older versions
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost audio focus - pause recording and set state to INTERRUPTED
                if (currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            mediaRecorder.pause();
                            currentRecordingStatus = CurrentRecordingStatus.INTERRUPTED;
                            if (onInterruptionBegan != null) {
                                onInterruptionBegan.run();
                            }
                        }
                    } catch (Exception e) {
                        // Ignore exceptions during pause
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Regained audio focus - keep state as INTERRUPTED, let user decide
                if (currentRecordingStatus == CurrentRecordingStatus.INTERRUPTED) {
                    if (onInterruptionEnded != null) {
                        onInterruptionEnded.run();
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Another app needs audio briefly, but we can continue at lower volume
                // For voice recording, we'll treat this the same as AUDIOFOCUS_LOSS_TRANSIENT
                if (currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            mediaRecorder.pause();
                            currentRecordingStatus = CurrentRecordingStatus.INTERRUPTED;
                            if (onInterruptionBegan != null) {
                                onInterruptionBegan.run();
                            }
                        }
                    } catch (Exception e) {
                        // Ignore exceptions during pause
                    }
                }
                break;
        }
    }
}
