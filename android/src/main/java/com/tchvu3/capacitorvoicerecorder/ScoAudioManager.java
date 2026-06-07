package com.tchvu3.capacitorvoicerecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

public class ScoAudioManager {

    public interface Callback {
        void onScoConnected();
        void onScoFailed();        // initial start: timeout or SCO unavailable
        void onScoDisconnected();  // dropped mid-session (battery, range)
    }

    private static final long SCO_TIMEOUT_MS = 5000;

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = this::onTimeout;

    private Callback callback;
    private BroadcastReceiver receiver;
    private boolean connected = false;

    public ScoAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager =
            (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isConnected() {
        return connected;
    }

    public void start(Callback cb) {
        this.callback = cb;
        if (connected) {
            cb.onScoConnected();
            return;
        }
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            cb.onScoFailed();
            return;
        }
        registerReceiver(); // BEFORE startBluetoothSco — order matters
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        try {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        } catch (Exception e) {
            teardown();
            cb.onScoFailed();
            return;
        }
        handler.postDelayed(timeoutRunnable, SCO_TIMEOUT_MS);
    }

    public void stop() {
        handler.removeCallbacks(timeoutRunnable);
        teardown();
    }

    private void onTimeout() {
        teardown();
        if (callback != null) callback.onScoFailed();
    }

    private void teardown() {
        unregisterReceiver();
        try {
            audioManager.stopBluetoothSco();
        } catch (Exception ignored) {}
        audioManager.setBluetoothScoOn(false);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        connected = false;
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    handler.removeCallbacks(timeoutRunnable);
                    connected = true;
                    if (callback != null) callback.onScoConnected();
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    // Quirk: some devices fire DISCONNECTED right after
                    // registration and during normal teardown. Only treat
                    // as a drop if we were actually connected.
                    if (connected) {
                        connected = false;
                        if (callback != null) callback.onScoDisconnected();
                    }
                }
            }
        };
        context.registerReceiver(receiver,
            new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
    }

    private void unregisterReceiver() {
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception ignored) {}
        receiver = null;
    }
}
