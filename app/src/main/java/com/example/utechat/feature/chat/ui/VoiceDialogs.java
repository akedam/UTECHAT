package com.example.utechat.feature.chat.ui;

import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.utechat.R;
import com.example.utechat.app.MediaRepositoryProvider;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.repository.MediaRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Locale;

/** One recorder or player tied to the chat screen. Closing the dialog releases the microphone. */
public final class VoiceDialogs {
    private static final int MAX_LENGTH_MS = 60_000;
    private final ChatActivity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private MediaRepository.Transfer download;
    private File recording;
    private long startedAt;
    private long duration;

    VoiceDialogs(ChatActivity host) {
        this.host = host;
    }

    public static String formatDuration(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    void record() {
        close();
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * host.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);
        TextView label = new TextView(host);
        label.setText(R.string.voice_record_limit);
        content.addView(label);
        Button listen = new Button(host);
        listen.setText(R.string.voice_listen);
        listen.setVisibility(android.view.View.GONE);
        content.addView(listen);

        AlertDialog panel = new MaterialAlertDialogBuilder(host).setTitle(R.string.voice_message)
                .setView(content).setNegativeButton(R.string.friends_cancel_button, null)
                .setNeutralButton(R.string.voice_record, null).setPositiveButton(R.string.voice_send, null).create();
        dialog = panel;
        panel.setOnDismissListener(unused -> cleanup(panel));
        panel.show();
        Button record = panel.getButton(AlertDialog.BUTTON_NEUTRAL);
        Button send = panel.getButton(AlertDialog.BUTTON_POSITIVE);
        send.setEnabled(false);

        Runnable stop = () -> {
            if (recorder == null) return;
            duration = SystemClock.elapsedRealtime() - startedAt;
            handler.removeCallbacksAndMessages(null);
            MediaRecorder active = recorder;
            recorder = null;
            boolean valid = false;
            try {
                active.stop();
                valid = duration >= 1000 && recording != null && recording.length() > 0;
            } catch (RuntimeException ignored) {
                // A very short or interrupted recording may not have samples.
            } finally {
                active.release();
            }
            record.setText(R.string.voice_record);
            if (!valid) {
                deleteRecording();
                label.setText(R.string.voice_record_error);
                return;
            }
            duration = Math.min(duration, MAX_LENGTH_MS);
            label.setText(formatDuration(duration));
            listen.setVisibility(android.view.View.VISIBLE);
            send.setEnabled(true);
        };

        record.setOnClickListener(view -> {
            if (recorder != null) {
                stop.run();
                return;
            }
            releasePlayer();
            listen.setText(R.string.voice_listen);
            listen.setVisibility(android.view.View.GONE);
            send.setEnabled(false);
            deleteRecording();
            try {
                recording = File.createTempFile("voice-", ".m4a", host.getCacheDir());
                recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(host) : new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioChannels(1);
                recorder.setAudioSamplingRate(16000);
                recorder.setAudioEncodingBitRate(48000);
                recorder.setOutputFile(recording.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                startedAt = SystemClock.elapsedRealtime();
                record.setText(R.string.voice_stop_recording);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (recorder == null || !panel.isShowing()) return;
                        long elapsed = SystemClock.elapsedRealtime() - startedAt;
                        if (elapsed >= MAX_LENGTH_MS) stop.run();
                        else {
                            label.setText(host.getString(R.string.voice_recording, formatDuration(elapsed)));
                            handler.postDelayed(this, 200);
                        }
                    }
                });
            } catch (Exception error) {
                releaseRecorder();
                deleteRecording();
                label.setText(R.string.voice_record_error);
            }
        });
        listen.setOnClickListener(view -> playFile(recording, listen, R.string.voice_listen));
        send.setOnClickListener(view -> {
            if (recording == null || !host.sendRecordedVoice(recording, duration)) return;
            recording = null;
            panel.dismiss();
        });
    }

    void play(Message message) {
        close();
        if (message.getMediaUrl() == null) return;
        AlertDialog panel = new MaterialAlertDialogBuilder(host).setTitle(R.string.voice_message)
                .setMessage(host.getString(R.string.voice_loading))
                .setNegativeButton(R.string.community_close, null)
                .setPositiveButton(R.string.voice_play, null).create();
        dialog = panel;
        panel.setOnDismissListener(unused -> cleanup(panel));
        panel.show();
        Button play = panel.getButton(AlertDialog.BUTTON_POSITIVE);
        play.setEnabled(false);
        download = MediaRepositoryProvider.get(host).downloadVoice(message.getMediaUrl(),
                new MediaRepository.DownloadListener() {
                    @Override public void onSuccess(File file) {
                        if (dialog != panel || !panel.isShowing()) return;
                        panel.setMessage(formatDuration(message.getDurationMs()));
                        play.setEnabled(true);
                        play.setOnClickListener(view -> playFile(file, play, R.string.voice_play));
                        playFile(file, play, R.string.voice_play);
                    }
                    @Override public void onError(String error) {
                        if (dialog == panel && panel.isShowing()) panel.setMessage(error);
                    }
                });
    }

    private void playFile(File file, Button button, int playLabel) {
        if (file == null) return;
        if (player != null) {
            if (player.isPlaying()) {
                player.pause();
                button.setText(playLabel);
            } else {
                player.start();
                button.setText(R.string.voice_pause);
            }
            return;
        }
        button.setEnabled(false);
        try {
            MediaPlayer pending = new MediaPlayer();
            player = pending;
            pending.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            pending.setDataSource(file.getAbsolutePath());
            pending.setOnPreparedListener(ready -> {
                if (player != ready) return;
                button.setEnabled(true);
                button.setText(R.string.voice_pause);
                ready.start();
            });
            pending.setOnCompletionListener(done -> {
                if (player != done) return;
                releasePlayer();
                button.setText(playLabel);
            });
            pending.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) playbackError(button, playLabel);
                return true;
            });
            pending.prepareAsync();
        } catch (Exception error) {
            playbackError(button, playLabel);
        }
    }

    private void playbackError(Button button, int playLabel) {
        releasePlayer();
        button.setEnabled(true);
        button.setText(playLabel);
        Toast.makeText(host, R.string.voice_play_error, Toast.LENGTH_SHORT).show();
    }

    void close() {
        if (dialog != null) {
            AlertDialog previous = dialog;
            previous.dismiss();
            cleanup(previous);
        }
    }

    private void cleanup(AlertDialog panel) {
        if (dialog != panel) return;
        dialog = null;
        handler.removeCallbacksAndMessages(null);
        if (download != null) download.cancel();
        download = null;
        releasePlayer();
        releaseRecorder();
        deleteRecording();
    }

    private void releaseRecorder() {
        if (recorder != null) recorder.release();
        recorder = null;
    }

    private void releasePlayer() {
        if (player != null) player.release();
        player = null;
    }

    private void deleteRecording() {
        if (recording != null) recording.delete();
        recording = null;
    }
}
