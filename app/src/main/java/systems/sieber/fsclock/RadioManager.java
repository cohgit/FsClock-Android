package systems.sieber.fsclock;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RadioManager implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnInfoListener {

    private static final String TAG = "RadioManager";

    public static class RadioStation {
        private final String name;
        private final String frequency;
        private final String streamUrl;

        public RadioStation(String name, String frequency, String streamUrl) {
            this.name = name;
            this.frequency = frequency;
            this.streamUrl = streamUrl;
        }

        public String getName() {
            return name;
        }

        public String getFrequency() {
            return frequency;
        }

        public String getStreamUrl() {
            return streamUrl;
        }
    }

    public interface OnRadioStateListener {
        void onStateChanged(boolean isPlaying, boolean isBuffering, RadioStation currentStation);
        void onError(String errorMessage);
    }

    private final Context context;
    private final AudioManager audioManager;
    private MediaPlayer mediaPlayer;
    private final List<RadioStation> stations = new ArrayList<>();
    private int currentStationIndex = 0;

    private boolean isPlaying = false;
    private boolean isBuffering = false;
    private OnRadioStateListener listener;

    private AudioFocusRequest audioFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (isPlaying) {
                        pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (mediaPlayer != null && isPlaying) {
                        mediaPlayer.setVolume(0.2f, 0.2f);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                        if (!isPlaying) {
                            play();
                        }
                    }
                    break;
            }
        }
    };

    public RadioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        initDefaultStations();
    }

    private void initDefaultStations() {
        stations.add(new RadioStation("Swiss Classic", "91.5 FM", "http://stream.srg-ssr.ch/m/rsc_de/mp3_128"));
        stations.add(new RadioStation("Swiss Jazz", "98.2 FM", "http://stream.srg-ssr.ch/m/rsj/mp3_128"));
        stations.add(new RadioStation("Swiss Pop", "102.4 FM", "http://stream.srg-ssr.ch/m/rsp/mp3_128"));
        stations.add(new RadioStation("FIP Lounge", "105.1 FM", "https://icecast.radiofrance.fr/fip-midfi.mp3"));
        stations.add(new RadioStation("Chill Radio", "88.7 FM", "https://stream.zeno.fm/f3wvbbqmdg8uv"));
    }

    public void setListener(OnRadioStateListener listener) {
        this.listener = listener;
    }

    public List<RadioStation> getStations() {
        return stations;
    }

    public int getCurrentStationIndex() {
        return currentStationIndex;
    }

    public RadioStation getCurrentStation() {
        if (stations.isEmpty()) return null;
        return stations.get(currentStationIndex);
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isBuffering() {
        return isBuffering;
    }

    public void setStationIndex(int index) {
        if (index >= 0 && index < stations.size()) {
            boolean wasPlaying = isPlaying;
            if (isPlaying) {
                stop();
            }
            currentStationIndex = index;
            notifyStateChanged();
            if (wasPlaying) {
                play();
            }
        }
    }

    public void nextStation() {
        if (stations.isEmpty()) return;
        int nextIndex = (currentStationIndex + 1) % stations.size();
        setStationIndex(nextIndex);
    }

    public void previousStation() {
        if (stations.isEmpty()) return;
        int prevIndex = (currentStationIndex - 1 + stations.size()) % stations.size();
        setStationIndex(prevIndex);
    }

    public void togglePlay() {
        if (isPlaying || isBuffering) {
            stop();
        } else {
            play();
        }
    }

    public void play() {
        if (stations.isEmpty()) return;

        if (requestAudioFocus()) {
            initMediaPlayer();
            RadioStation station = getCurrentStation();
            if (station == null) return;

            try {
                isBuffering = true;
                isPlaying = false;
                notifyStateChanged();

                mediaPlayer.reset();
                mediaPlayer.setDataSource(station.getStreamUrl());
                mediaPlayer.prepareAsync();
            } catch (IOException e) {
                Log.e(TAG, "Error setting data source for radio stream", e);
                isBuffering = false;
                isPlaying = false;
                notifyStateChanged();
                if (listener != null) {
                    listener.onError("Cannot load radio stream");
                }
            }
        }
    }

    public void pause() {
        stop();
    }

    public void stop() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping radio player", e);
            }
        }
        isPlaying = false;
        isBuffering = false;
        abandonAudioFocus();
        notifyStateChanged();
    }

    public void release() {
        stop();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void initMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                );
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnInfoListener(this);
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
            int res = audioManager.requestAudioFocus(audioFocusRequest);
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            int res = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        isBuffering = false;
        isPlaying = true;
        mp.start();
        notifyStateChanged();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: " + what + ", extra: " + extra);
        isBuffering = false;
        isPlaying = false;
        notifyStateChanged();
        if (listener != null) {
            listener.onError("Radio playback error (" + what + ")");
        }
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        isPlaying = false;
        isBuffering = false;
        notifyStateChanged();
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
            isBuffering = true;
            notifyStateChanged();
        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
            isBuffering = false;
            notifyStateChanged();
        }
        return false;
    }

    private void notifyStateChanged() {
        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onStateChanged(isPlaying, isBuffering, getCurrentStation());
                    }
                }
            });
        }
    }
}
