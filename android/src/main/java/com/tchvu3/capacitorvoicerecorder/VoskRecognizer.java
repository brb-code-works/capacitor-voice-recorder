package com.tchvu3.capacitorvoicerecorder;

import android.content.Context;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

/**
 * On-device recognition with a constrained vocabulary.
 *
 * Models live in the APP's assets (model-en-us, model-es), not in this plugin:
 * the plugin runs in the app's context and reads them by name, so the shell
 * decides which languages ship.
 *
 * Only FINAL results are passed on. Partials are the recogniser's guesses
 * mid-word -- "short skip" before it settles on "short" -- and acting on one
 * fires the wrong command.
 */
public class VoskRecognizer {

    public interface Listener {
        void onText(String text);
        void onError(String message);
    }

    public interface StartCallback {
        void onStarted();
        void onError(String message);
    }

    private static final float SAMPLE_RATE = 16000.0f;

    private final Context context;
    // Loaded once per language and kept. Unpacking takes seconds, and a shared
    // device switches language whenever a different picker logs in.
    private final Map<String, Model> models = new HashMap<>();
    private SpeechService speechService;
    // Bumped by every stop(). A model still unpacking when stop() is called
    // must not start listening when it finishes.
    private int session = 0;

    public VoskRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRunning() {
        return speechService != null;
    }

    /**
     * grammar is a JSON array of phrases, sent as UTF-8 and never ASCII-escaped:
     * an escaped accent makes the word look absent from the model, and every
     * command containing it is silently unhearable.
     */
    public void start(String modelName, String grammar, Listener listener, StartCallback cb) {
        stop();
        final int mine = session;
        Model cached = models.get(modelName);
        if (cached != null) {
            begin(cached, grammar, listener, cb, mine);
            return;
        }
        StorageService.unpack(context, modelName, modelName,
            (model) -> {
                models.put(modelName, model);
                begin(model, grammar, listener, cb, mine);
            },
            (e) -> cb.onError("model " + modelName + ": " + e.getMessage()));
    }

    private void begin(Model model, String grammar, Listener listener, StartCallback cb, int mine) {
        if (mine != session) return; // stopped while the model was loading
        try {
            Recognizer rec = new Recognizer(model, SAMPLE_RATE, grammar);
            speechService = new SpeechService(rec, SAMPLE_RATE);
            speechService.startListening(new RecognitionListener() {
                @Override public void onPartialResult(String hypothesis) { }
                @Override public void onResult(String hypothesis) { emit(hypothesis, listener); }
                @Override public void onFinalResult(String hypothesis) { emit(hypothesis, listener); }
                @Override public void onError(Exception e) { listener.onError(String.valueOf(e.getMessage())); }
                @Override public void onTimeout() { }
            });
            cb.onStarted();
        } catch (IOException e) {
            speechService = null;
            cb.onError(String.valueOf(e.getMessage()));
        }
    }

    public void stop() {
        session++;
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
    }

    private static void emit(String hypothesis, Listener listener) {
        try {
            String text = new JSONObject(hypothesis).optString("text", "");
            // [unk] means "not one of mine". Alone it is nothing to act on; mixed
            // in ("[unk] nine four five" from an "uh") it is noise around a
            // real answer.
            text = text.replace("[unk]", " ").trim().replaceAll("\\s+", " ");
            if (!text.isEmpty()) listener.onText(text);
        } catch (Exception ignored) { }
    }
}
