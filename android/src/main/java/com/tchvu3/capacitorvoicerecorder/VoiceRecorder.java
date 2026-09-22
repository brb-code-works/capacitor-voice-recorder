package com.tchvu3.capacitorvoicerecorder;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Base64;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@CapacitorPlugin(
    name = "VoiceRecorder",
    permissions = { @Permission(alias = VoiceRecorder.RECORD_AUDIO_ALIAS, strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class VoiceRecorder extends Plugin {

    static final String RECORD_AUDIO_ALIAS = "voice recording";
    private CustomMediaRecorder mediaRecorder;
    private PcmStreamRecorder pcmStreamRecorder;
    private ScoAudioManager scoManager;
    private VoskRecognizer voskRecognizer;

    private static final int AMPLITUDE_POLL_MS = 120;
    private static final long MAX_INLINE_FILE_BYTES = 10L * 1024 * 1024;
    private Handler amplitudeHandler;
    private final Runnable amplitudePoller = new Runnable() {
        @Override
        public void run() {
            CustomMediaRecorder recorder = mediaRecorder;
            if (recorder == null) return;
            int amp = recorder.getMaxAmplitude();
            JSObject data = new JSObject();
            data.put("value", amp / 32767.0);
            notifyListeners("amplitude", data);
            if (amplitudeHandler != null) {
                amplitudeHandler.postDelayed(this, AMPLITUDE_POLL_MS);
            }
        }
    };

    @Override
    public void load() {
        super.load();
        scoManager = new ScoAudioManager(getContext());
        voskRecognizer = new VoskRecognizer(getContext());
    }

    private void emitScoState(String state) {
        JSObject data = new JSObject();
        data.put("state", state);
        notifyListeners("scoStateChange", data);
    }

    private void startAmplitudePolling() {
        stopAmplitudePolling();
        amplitudeHandler = new Handler(Looper.getMainLooper());
        amplitudeHandler.postDelayed(amplitudePoller, AMPLITUDE_POLL_MS);
    }

    private void stopAmplitudePolling() {
        if (amplitudeHandler != null) {
            amplitudeHandler.removeCallbacks(amplitudePoller);
            amplitudeHandler = null;
        }
    }

    @PluginMethod
    public void canDeviceVoiceRecord(PluginCall call) {
        if (CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    @PluginMethod
    public void requestAudioRecordingPermission(PluginCall call) {
        if (doesUserGaveAudioRecordingPermission()) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            requestPermissionForAlias(RECORD_AUDIO_ALIAS, call, "recordAudioPermissionCallback");
        }
    }

    @PermissionCallback
    private void recordAudioPermissionCallback(PluginCall call) {
        this.hasAudioRecordingPermission(call);
    }

    @PluginMethod
    public void hasAudioRecordingPermission(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()));
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (!CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE);
            return;
        }

        if (!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION);
            return;
        }

        if (this.isMicrophoneOccupied()) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }

        if (mediaRecorder != null) {
            call.reject(Messages.ALREADY_RECORDING);
            return;
        }

        if (pcmStreamRecorder != null && pcmStreamRecorder.isRunning()) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }

        try {
            String directory = call.getString("directory");
            String subDirectory = call.getString("subDirectory");
            RecordOptions options = new RecordOptions(directory, subDirectory);
            mediaRecorder = new CustomMediaRecorder(getContext(), options);

            // Set up interruption callbacks
            mediaRecorder.setOnInterruptionBegan(() -> {
                notifyListeners("voiceRecordingInterrupted", null);
            });

            mediaRecorder.setOnInterruptionEnded(() -> {
                notifyListeners("voiceRecordingInterruptionEnded", null);
            });

            mediaRecorder.startRecording();
            startAmplitudePolling();
            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception exp) {
            mediaRecorder = null;
            call.reject(Messages.FAILED_TO_RECORD, exp);
        }
    }

    @PluginMethod
    public void startStreaming(PluginCall call) {
        if (!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION);
            return;
        }
        if (mediaRecorder != null) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }
        if (pcmStreamRecorder != null && pcmStreamRecorder.isRunning()) {
            call.resolve(ResponseGenerator.successResponse());
            return;
        }
        if (voskRecognizer != null && voskRecognizer.isRunning()) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }
        boolean voiceComm = !"mic".equals(call.getString("audioSource", "voice_communication"));
        boolean useSco = Boolean.TRUE.equals(call.getBoolean("useSco", false));

        if (!useSco) {
            beginPcmCapture(call, voiceComm);
            return;
        }

        scoManager.start(new ScoAudioManager.Callback() {
            @Override
            public void onScoConnected() {
                emitScoState("connected");
                if (pcmStreamRecorder != null && pcmStreamRecorder.isRunning()) {
                    call.resolve(ResponseGenerator.successResponse());
                    return;
                }
                beginPcmCapture(call, true); // SCO up -> VOICE_COMMUNICATION
            }

            @Override
            public void onScoFailed() {
                emitScoState("failed");
                JSObject ret = new JSObject();
                ret.put("value", false);
                ret.put("reason", "sco_failed");
                call.resolve(ret); // resolve, not reject — JS owns the mode switch
            }

            @Override
            public void onScoDisconnected() {
                stopPcmInternal(); // headset mic is gone; no silent fallback
                emitScoState("disconnected");
            }
        });
    }

    private void beginPcmCapture(PluginCall call, boolean voiceComm) {
        try {
            pcmStreamRecorder = new PcmStreamRecorder((pcm, peak) -> {
                JSObject data = new JSObject();
                data.put("data", Base64.encodeToString(pcm, Base64.NO_WRAP));
                data.put("amplitude", peak);
                data.put("sampleRate", PcmStreamRecorder.SAMPLE_RATE);
                notifyListeners("pcmFrame", data);
            });
            pcmStreamRecorder.start(voiceComm);
            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception exp) {
            pcmStreamRecorder = null;
            call.reject(Messages.FAILED_TO_RECORD, exp);
        }
    }

    private void stopPcmInternal() {
        if (pcmStreamRecorder != null) {
            pcmStreamRecorder.stop();
            pcmStreamRecorder = null;
        }
    }

    @PluginMethod
    public void stopStreaming(PluginCall call) {
        stopPcmInternal();
        if (scoManager != null) {
            scoManager.stop();
        }
        call.resolve(ResponseGenerator.successResponse());
    }

    /**
     * On-device recognition: the same shape as startStreaming, but it emits
     * recognised text (recognitionResult) instead of PCM frames. SCO first,
     * then the recogniser -- Vosk's SpeechService opens its AudioRecord in its
     * constructor, so building it before SCO connects records from the device
     * mic.
     *
     * Options: model ("model-en-us" | "model-es"), grammar (JSON array of
     * phrases, UTF-8), useSco.
     */
    @PluginMethod
    public void startRecognition(PluginCall call) {
        if (!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION);
            return;
        }
        if (mediaRecorder != null || (pcmStreamRecorder != null && pcmStreamRecorder.isRunning())) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }
        String model = call.getString("model", "model-en-us");
        String grammar = call.getString("grammar");
        if (grammar == null || grammar.isEmpty()) {
            call.reject("grammar is required");
            return;
        }
        boolean useSco = Boolean.TRUE.equals(call.getBoolean("useSco", false));

        if (!useSco) {
            beginRecognition(call, model, grammar);
            return;
        }
        scoManager.start(new ScoAudioManager.Callback() {
            @Override
            public void onScoConnected() {
                emitScoState("connected");
                if (voskRecognizer.isRunning()) {
                    call.resolve(ResponseGenerator.successResponse());
                    return;
                }
                beginRecognition(call, model, grammar);
            }

            @Override
            public void onScoFailed() {
                emitScoState("failed");
                JSObject ret = new JSObject();
                ret.put("value", false);
                ret.put("reason", "sco_failed");
                call.resolve(ret); // resolve, not reject -- JS owns the mode switch
            }

            @Override
            public void onScoDisconnected() {
                voskRecognizer.stop(); // headset mic is gone; no silent fallback
                emitScoState("disconnected");
            }
        });
    }

    private void beginRecognition(PluginCall call, String model, String grammar) {
        voskRecognizer.start(model, grammar,
            new VoskRecognizer.Listener() {
                @Override
                public void onText(String text) {
                    JSObject data = new JSObject();
                    data.put("text", text);
                    notifyListeners("recognitionResult", data);
                }

                @Override
                public void onError(String message) {
                    JSObject data = new JSObject();
                    data.put("error", message);
                    notifyListeners("recognitionError", data);
                }
            },
            new VoskRecognizer.StartCallback() {
                @Override
                public void onStarted() {
                    call.resolve(ResponseGenerator.successResponse());
                }

                @Override
                public void onError(String message) {
                    call.reject(message);
                }
            });
    }

    @PluginMethod
    public void stopRecognition(PluginCall call) {
        if (voskRecognizer != null) {
            voskRecognizer.stop();
        }
        if (scoManager != null) {
            scoManager.stop();
        }
        call.resolve(ResponseGenerator.successResponse());
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        stopAmplitudePolling();
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }

        try {
            mediaRecorder.stopRecording();
            File recordedFile = mediaRecorder.getOutputFile();
            RecordOptions options = mediaRecorder.getRecordOptions();

            // Guard: an unexpectedly long recording (suspended JS timers in
            // background, lost stop) is read whole-file into memory and
            // base64-encoded below — observed OOM-killing the host app at
            // 135MB/799MB allocations. Reject like a failed fetch; callers
            // already treat that error as skip-and-continue.
            if (options.getDirectory() == null && recordedFile.length() > MAX_INLINE_FILE_BYTES) {
                call.reject(Messages.FAILED_TO_FETCH_RECORDING);
                return;
            }

            String path = null;
            String recordDataBase64 = null;
            if (options.getDirectory() != null) {
                path = recordedFile.getName();
                if (options.getSubDirectory() != null) {
                    path = options.getSubDirectory() + "/" + path;
                }
            } else {
                recordDataBase64 = readRecordedFileAsBase64(recordedFile);
            }

            RecordData recordData = new RecordData(
                recordDataBase64,
                getMsDurationOfAudioFile(recordedFile.getAbsolutePath()),
                "audio/aac",
                path
            );
            if ((recordDataBase64 == null && path == null) || recordData.getMsDuration() < 0) {
                call.reject(Messages.EMPTY_RECORDING);
            } else {
                call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
            }
        } catch (Exception exp) {
            call.reject(Messages.FAILED_TO_FETCH_RECORDING, exp);
        } finally {
            RecordOptions options = mediaRecorder.getRecordOptions();
            if (options.getDirectory() == null) {
                mediaRecorder.deleteOutputFile();
            }

            mediaRecorder = null;
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            call.resolve(ResponseGenerator.fromBoolean(mediaRecorder.pauseRecording()));
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            call.resolve(ResponseGenerator.fromBoolean(mediaRecorder.resumeRecording()));
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        }
    }

    @PluginMethod
    public void getCurrentStatus(PluginCall call) {
        if (mediaRecorder == null) {
            call.resolve(ResponseGenerator.statusResponse(CurrentRecordingStatus.NONE));
        } else {
            call.resolve(ResponseGenerator.statusResponse(mediaRecorder.getCurrentStatus()));
        }
    }

    private boolean doesUserGaveAudioRecordingPermission() {
        return getPermissionState(VoiceRecorder.RECORD_AUDIO_ALIAS).equals(PermissionState.GRANTED);
    }

    private String readRecordedFileAsBase64(File recordedFile) {
        BufferedInputStream bufferedInputStream;
        byte[] bArray = new byte[(int) recordedFile.length()];
        try {
            bufferedInputStream = new BufferedInputStream(new FileInputStream(recordedFile));
            bufferedInputStream.read(bArray);
            bufferedInputStream.close();
        } catch (IOException exp) {
            return null;
        }
        return Base64.encodeToString(bArray, Base64.DEFAULT);
    }

    private int getMsDurationOfAudioFile(String recordedFilePath) {
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(recordedFilePath);
            mediaPlayer.prepare();
            return mediaPlayer.getDuration();
        } catch (Exception ignore) {
            return -1;
        }
    }

    private boolean isMicrophoneOccupied() {
        AudioManager audioManager = (AudioManager) this.getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return true;
        return audioManager.getMode() != AudioManager.MODE_NORMAL;
    }
}
