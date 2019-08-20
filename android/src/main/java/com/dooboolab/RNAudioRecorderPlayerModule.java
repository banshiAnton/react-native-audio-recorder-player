
package com.dooboolab;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.PermissionListener;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Nullable;

public class RNAudioRecorderPlayerModule extends ReactContextBaseJavaModule implements PermissionListener{
  final private static String TAG = "RNAudioRecorderPlayer";
  private String FILE_LOCATION = null;
  private double recordDuration = 0;

  private int subsDurationMillis = 100;

  private final ReactApplicationContext reactContext;
  private MediaRecorder mediaRecorder;
  private MediaPlayer mediaPlayer;

  private Runnable recorderRunnable;
  private TimerTask mTask;
  private Timer mTimer;
  Handler recordHandler = new Handler(Looper.getMainLooper());

  public RNAudioRecorderPlayerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @ReactMethod
  public void startRecorder(final String path, Promise promise) {
    FILE_LOCATION = path.equals("DEFAULT") ? ("/sdcard/" + System.currentTimeMillis() + ".mp4") : path;
    try {
      if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                      (
                              ActivityCompat.checkSelfPermission(reactContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                                      ActivityCompat.checkSelfPermission(reactContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                      )
      ) {
        ActivityCompat.requestPermissions(getCurrentActivity(), new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
        }, 0);
        promise.reject("No permission granted.", "Try again after adding permission.");
        return;
      }
    } catch (NullPointerException ne) {
      Log.w(TAG, ne.toString());
      promise.reject("No permission granted.", "Try again after adding permission.");
      return;
    }

    if (mediaRecorder != null) {
      try {
        mediaRecorder.stop();
        mediaRecorder.release();
        mediaRecorder = null;
      } catch (Exception e) {
        Log.e(TAG, "mediaRecorder reset: " +  e.getMessage());
      }
    }

    mediaRecorder = new MediaRecorder();
    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
    mediaRecorder.setOutputFile(FILE_LOCATION);

    try {
      mediaRecorder.prepare();
      mediaRecorder.start();
      final long systemTime = SystemClock.elapsedRealtime();
      this.recorderRunnable = new Runnable() {
        @Override
        public void run() {
          long time = SystemClock.elapsedRealtime() - systemTime;
          WritableMap obj = Arguments.createMap();
          recordDuration = time;
          obj.putDouble("current_position", time);
          sendEvent(reactContext, "rn-recordback", obj);
          recordHandler.postDelayed(this, subsDurationMillis);
        }
      };
      this.recorderRunnable.run();

      promise.resolve("file://" + FILE_LOCATION);
    } catch (Exception e) {
      Log.e(TAG, "Exception: ", e);
      promise.reject("startRecord", e.getMessage());
    }
  }

  @ReactMethod
  public void stopRecorder(Promise promise) {
    try {
      if (recordHandler != null) {
        recordHandler.removeCallbacks(this.recorderRunnable);
      }

      if (mediaRecorder == null) {
        promise.resolve("[java][native code] StopRecorder failed recorder is null");
        return;
      }
      mediaRecorder.stop();
      mediaRecorder.release();
      mediaRecorder = null;

      File audioFile = new File(FILE_LOCATION);
      String audioFileSize = String.valueOf(audioFile.length());

      WritableMap stopRecordingResultObject = Arguments.createMap();
      stopRecordingResultObject.putString("size", audioFileSize);
      stopRecordingResultObject.putString("path", "file://" + FILE_LOCATION);
      stopRecordingResultObject.putString("type", "audio/mpeg");
      stopRecordingResultObject.putDouble("duration", recordDuration);

      FILE_LOCATION = null;
      recordDuration = 0;

      promise.resolve(stopRecordingResultObject);
    } catch (Exception e) {
      promise.resolve("[java][native code] StopRecorder failed " +  e.getMessage());
    }
  }

  @ReactMethod
  public void setVolume(double volume, Promise promise) {
    if (mediaPlayer == null) {
      Log.e(TAG, "[setVolume] mediaPlayer == null");
      promise.resolve("mediaPlayer == null");
      //promise.reject("setVolume", "player is null.");
      return;
    }
    float mVolume = (float) volume;
    mediaPlayer.setVolume(mVolume, mVolume);

    promise.resolve("set volume");
  }

  @ReactMethod
  public void startPlayer(final String path, final int skeepTo, final Promise promise) {
    if (path == null || path.length() < 1) {
      promise.reject("[startPlayer]", "Path is required");
      return;
    }
    if (mediaPlayer != null) {
//      Boolean isPaused = !mediaPlayer.isPlaying() && mediaPlayer.getCurrentPosition() > 1;
//
//      if (isPaused) {
//        mediaPlayer.start();
//        promise.resolve("player resumed.");
//        return;
//      }
//
//      Log.e(TAG, "Player is already running. Stop it first.");
//      promise.reject("startPlay", "Player is already running. Stop it first.");
//      return;
      mediaPlayer.stop();
      mediaPlayer.release();
    } else {
      mediaPlayer = new MediaPlayer();
    }
    try {
      Log.d(TAG, "mediaplayer path " + path);
      mediaPlayer.setDataSource(path);
      mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(final MediaPlayer mp) {
          Log.d(TAG, "mediaplayer prepared and start");
          if (skeepTo > 0) {
            mp.seekTo(skeepTo);
          }
          mp.start();

          /**
           * Set timer task to send event to RN.
           */
          mTask = new TimerTask() {
            @Override
            public void run() {
              WritableMap obj = Arguments.createMap();
              obj.putInt("duration", mp.getDuration());
              obj.putInt("current_position", mp.getCurrentPosition());
              sendEvent(reactContext, "rn-playback", obj);
            }
          };

          mTimer = new Timer();
          mTimer.schedule(mTask, 0, subsDurationMillis);

          // String resolvedPath = (path.equals("DEFAULT")) ? "file://" + FILE_LOCATION : path;
          promise.resolve(path);
        }
      });
      /**
       * Detect when finish playing.
       */
      mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
          /**
           * Send last event
           */
          WritableMap obj = Arguments.createMap();
          obj.putInt("duration", mp.getDuration());
          obj.putInt("current_position", mp.getDuration());
          sendEvent(reactContext, "rn-playback", obj);

          /**
           * Reset player.
           */
          Log.d(TAG, "Plays completed.");
          mTimer.cancel();
          mp.stop();
          mp.release();
          mediaPlayer = null;
        }
      });
      mediaPlayer.prepare();
    } catch (IOException e) {
      Log.e(TAG, "startPlay() io exception");
      promise.reject("startPlay", e.getMessage());
    } catch (NullPointerException e) {
      Log.e(TAG, "startPlay() null exception");
    }
  }

  @ReactMethod
  public void resumePlayer(Promise promise) {
    if (mediaPlayer == null) {
      Log.e(TAG, "[resumePlayer] mediaPlayer == null");
      promise.resolve("mediaPlayer == null");
      //promise.reject("setVolume", "player is null.");
      return;
    }

    if (mediaPlayer.isPlaying()) {
      promise.reject("resume","mediaPlayer is already running.");
      return;
    }

    try {
      mediaPlayer.seekTo(mediaPlayer.getCurrentPosition());
      mediaPlayer.start();
      promise.resolve("resume player");
    } catch (Exception e) {
      Log.e(TAG, "mediaPlayer resume: " + e.getMessage());
      promise.reject("resume", e.getMessage());
    }

  }

  @ReactMethod
  public void pausePlayer(Promise promise) {
    if (mediaPlayer == null) {
      Log.e(TAG, "[pausePlayer] mediaPlayer == null");
      promise.resolve("mediaPlayer == null");
      //promise.reject("setVolume", "player is null.");
      return;
    }

    try {
      mediaPlayer.pause();
      promise.resolve("pause player");
    } catch (Exception e) {
      Log.e(TAG, "pausePlay exception: " + e.getMessage());
      promise.reject("pausePlay",e.getMessage());
    }
  }

  @ReactMethod
  public void seekToPlayer(int time, Promise promise) {
    if (mediaPlayer == null) {
      Log.e(TAG, "[seekToPlayer] mediaPlayer == null");
      promise.resolve("mediaPlayer == null");
      //promise.reject("setVolume", "player is null.");
      return;
    }

    int millis = time * 1000;

    mediaPlayer.seekTo(millis);
    promise.resolve("pause player");
  }

  private void sendEvent(ReactContext reactContext,
                         String eventName,
                         @Nullable WritableMap params) {
    reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
  }

  @ReactMethod
  public void stopPlayer(Promise promise) {
    if (mTimer != null) {
      mTimer.cancel();
    }

    if (mediaPlayer == null) {
      Log.e(TAG, "[stopPlayer] mediaPlayer == null");
      promise.resolve("mediaPlayer == null");
      //promise.reject("setVolume", "player is null.");
      return;
    }

    try {
      mediaPlayer.release();
      mediaPlayer = null;
      promise.resolve("stopped player");
    } catch (Exception e) {
      Log.e(TAG, "stopPlay exception: " + e.getMessage());
      promise.reject("stopPlay",e.getMessage());
    }
  }

  @ReactMethod
  public void setSubscriptionDuration(double sec, Promise promise) {
    this.subsDurationMillis = (int) (sec * 1000);
    promise.resolve("setSubscriptionDuration: " + this.subsDurationMillis);
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    switch (requestCode) {
      case REQUEST_RECORD_AUDIO_PERMISSION:
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
          return true;
        break;
    }
    return false;
  }
}
