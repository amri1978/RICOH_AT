package fr.aasofts.autotrigger;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedTarget;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import fr.aasofts.autotrigger.env.ImageUtils;
import fr.aasofts.autotrigger.env.Logger;
import fr.aasofts.autotrigger.R; // Explicit import needed for internal Google builds.
import fr.aasofts.autotrigger.task.TakePictureTask;

import static android.os.SystemClock.sleep;

public abstract class CameraActivity extends PluginActivity
    implements Camera.PreviewCallback {
  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;
  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
  private static final String PERMISSION_AUDIO = Manifest.permission.RECORD_AUDIO;


  private Handler handler;
  private HandlerThread handlerThread;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;

  protected int previewWidth = 0;
  protected int previewHeight = 0;

  private Runnable postInferenceCallback;
  private Runnable imageConverter;

  private boolean isInferenceWorking = true;
  private boolean isTakingPicture = false;

  private Handler mCameraActivityHandler=null;

  protected String mObjectNameToFind;
  protected boolean mObjectNameFound = false;

  private Date mCaptureTime;
  private final long mThreashIgnore_msec = 5 * 1000; // Capturing interval [msec]

  private final String mObjectToFind = "smile";

  private boolean isEnded = false;

  private fr.aasofts.autotrigger.task.TakePictureTask.Callback mTakePictureTaskCallback = new TakePictureTask.Callback() {
    @Override
    public void onTakePicture(String fileUrl) {
      LOGGER.d("onTakePicture: " + fileUrl);
      isTakingPicture = false;
      startInference();
    }
  };

  public void notificationCameraClose(){
    sendBroadcast(new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_CLOSE")); // for THETA
  }
  public void notificationCameraOpen(){
    sendBroadcast(new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_OPEN")); // for THETA
  }
  public void notificationSuccess() {
    Intent intent = new Intent("com.theta360.plugin.ACTION_FINISH_PLUGIN");
    intent.putExtra("packageName", getPackageName());
    intent.putExtra("exitStatus", "success");
    sendBroadcast(intent);
    finishAndRemoveTask();
  }
  public void notificationError(String message) {
    Intent intent = new Intent("com.theta360.plugin.ACTION_FINISH_PLUGIN");
    intent.putExtra("packageName", getPackageName());
    intent.putExtra("exitStatus", "failure");
    intent.putExtra("message", message);
    sendBroadcast(intent);
    finishAndRemoveTask();
  }



  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);

    mCameraActivityHandler = new Handler();

    onSetObjectNameToFind(mObjectToFind);

    try {
      SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy/M/dd hh:mm:ss");
      mCaptureTime = simpleDateFormat.parse("2016/10/6 12:00:00"); // initialize to the past
    } catch (ParseException e) {
      e.printStackTrace();
    }

    // Set enable to close by pluginlibrary, If you set false, please call close() after finishing your end processing.
    setAutoClose(false);
    notificationWlanOff(); // for power saving
    notificationCameraClose();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.activity_camera);

    if (hasPermission()) {
      setFragment();
    } else {
      // Set app permission in Settings app, or install from THETA plugin store
      notificationError("Permissions are not granted.");
    }

    // Set a callback when a button operation event is acquired.
    setKeyCallback(new KeyCallback() {
      @Override
      public void onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
          stopInferenceAndCapture();
        }
      }

      @Override
      public void onKeyUp(int keyCode, KeyEvent event) {

        if (keyCode == KeyReceiver.KEYCODE_WLAN_ON_OFF)
        {

        }
      }

      @Override
      public void onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD){
          if(!isTakingPicture) {
            endProcess();
          }
        }
      }
    });


    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE); // for THETA
    am.setParameters("RicUseBFormat=false"); // for THETA

    // Load the labels for the model, but only display those that don't start
    // with an underscore.
    String actualFilename = LABEL_FILENAME.split("file:///android_asset/")[1];
    Log.i(LOG_TAG, "Reading labels from: " + actualFilename);
    BufferedReader br = null;
    try {
      br = new BufferedReader(new InputStreamReader(getAssets().open(actualFilename)));
      String line;
      while ((line = br.readLine()) != null) {
        labels.add(line);
      }
      br.close();
    } catch (IOException e) {
      throw new RuntimeException("Problem reading label file!", e);
    }


    // Set up an object to smooth recognition results to increase accuracy.
    recognizeCommands =
            new RecognizeCommands(
                    labels,
                    AVERAGE_WINDOW_DURATION_MS,
                    DETECTION_THRESHOLD,
                    SUPPRESSION_MS,
                    MINIMUM_COUNT,
                    MINIMUM_TIME_BETWEEN_SAMPLES_MS);

    // Load the TensorFlow model.
    inferenceInterface = new TensorFlowInferenceInterface(getAssets(), MODEL_FILENAME);

    startRecording();
    startRecognition();
  }


  protected void stopInferenceAndCapture() {
    stopInference();
    isTakingPicture = true;
    new fr.aasofts.autotrigger.task.TakePictureTask(mTakePictureTaskCallback).execute();
  }

  protected void startInference() {
    if (isEnded) {
      // now on ending process
    }else{
      notificationCameraClose();
      sleep(400);

      mCameraActivityHandler.post(new Runnable() {
        @Override
        public void run() {
          Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container);
          fragment.onResume();
          isInferenceWorking = true;
        }
      });
    }
  }

  protected void stopInference() {
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container);
    if(isInferenceWorking) {
      isInferenceWorking = false;
      // Stop Preview
      fragment.onPause();
      notificationCameraOpen();
      sleep(600);
    }
  }

  private void endProcess() {
    LOGGER.d("CameraActivity::endProcess(): "+ isEnded);

    if (!isEnded) {
      isEnded = true;
      stopInference();
      close();
    }
  }

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }


  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  /**
   * Callback for android.hardware.Camera API
   */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }
    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 0);
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    yuvBytes[0] = bytes;


    imageConverter =
        new Runnable() {
          @Override
          public void run() {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
          }
        };

    postInferenceCallback =
        new Runnable() {
          @Override
          public void run() {
            camera.addCallbackBuffer(bytes);
            isProcessingFrame = false;
          }
        };
    processImage();

    if( objectNameFound() ) {
      mObjectNameFound = false;
      Date currentTime = Calendar.getInstance().getTime();
      long diff_msec = currentTime.getTime() - mCaptureTime.getTime();
      if (diff_msec > mThreashIgnore_msec){
        stopInferenceAndCapture();
        mCaptureTime = currentTime;
      }
    }
  }


  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);

    notificationLedShow(LedTarget.LED4); // Turn ON Camera LED
    notificationLedHide(LedTarget.LED5);
    notificationLedHide(LedTarget.LED6);
    notificationLedHide(LedTarget.LED7);
    notificationLedHide(LedTarget.LED8);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();
    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    if (!isFinishing()) {
      LOGGER.d("Requesting finish");

      close();
    }
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    if (requestCode == PERMISSIONS_REQUEST) {
      if (grantResults.length > 0
          && grantResults[0] == PackageManager.PERMISSION_GRANTED
          && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
        setFragment();
      } else {
        requestPermission();
      }
    }
    if (requestCode == REQUEST_RECORD_AUDIO
            && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      //startRecording();
      //startRecognition();
    }
  }

 private void requestMicrophonePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(
              new String[]{PERMISSION_AUDIO}, REQUEST_RECORD_AUDIO);
    }
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
          checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED &&
              checkSelfPermission(PERMISSION_AUDIO) == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
          shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
        Toast.makeText(CameraActivity.this,
            "Camera AND storage permission are required for this app", Toast.LENGTH_LONG).show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
    }
  }


  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();
    if (cameraId == null) {
      Toast.makeText(this, "No Camera Detected", Toast.LENGTH_SHORT).show();
      finish();
    }

    Fragment fragment = new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.container, fragment)
        .commit();
  }


  protected void onSetObjectNameToFind(final String name) {
    mObjectNameToFind = name; // TF_OD_API_LABELS_FILE
  }
  protected boolean objectNameFound() {
    return mObjectNameFound;
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }

  protected abstract void processImage();
  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);
  protected abstract int getLayoutId();
  protected abstract Size getDesiredPreviewFrameSize();





// region Audio

  // Constants that control the behavior of the recognition code and model
  // settings. See the audio recognition tutorial for a detailed explanation of
  // all these, but you should customize them to match your training settings if
  // you are running your own model.
  private static final int SAMPLE_RATE = 16000;
  private static final int SAMPLE_DURATION_MS = 1000;
  private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
  private static final long AVERAGE_WINDOW_DURATION_MS = 500;
  private static final float DETECTION_THRESHOLD = 0.70f;
  private static final int SUPPRESSION_MS = 1500;
  private static final int MINIMUM_COUNT = 3;
  private static final long MINIMUM_TIME_BETWEEN_SAMPLES_MS = 30;
  private static final String LABEL_FILENAME = "file:///android_asset/conv_actions_labels.txt";
  private static final String MODEL_FILENAME = "file:///android_asset/conv_actions_frozen.pb";
  private static final String INPUT_DATA_NAME = "decoded_sample_data:0";
  private static final String SAMPLE_RATE_NAME = "decoded_sample_data:1";
  private static final String OUTPUT_SCORES_NAME = "labels_softmax";



  // UI elements.
  private static final int REQUEST_RECORD_AUDIO = 13;
  private static final String LOG_TAG = "Speech";

  // Working variables.
  short[] recordingBuffer = new short[RECORDING_LENGTH];
  int recordingOffset = 0;
  boolean shouldContinue = true;
  private Thread recordingThread;
  boolean shouldContinueRecognition = true;
  private Thread recognitionThread;
  private final ReentrantLock recordingBufferLock = new ReentrantLock();
  private TensorFlowInferenceInterface inferenceInterface;
  private List<String> labels = new ArrayList<String>();
  private RecognizeCommands recognizeCommands = null;





  public synchronized void startRecording() {
    if (recordingThread != null) {
      return;
    }
    shouldContinue = true;
    recordingThread =
            new Thread(
                    new Runnable() {
                      @Override
                      public void run() {
                        record();
                      }
                    });
    recordingThread.start();
  }

  public synchronized void stopRecording() {
    if (recordingThread == null) {
      return;
    }
    shouldContinue = false;
    recordingThread = null;
  }

  private void record() {
    android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

    // Estimate the buffer size we'll need for this device.
    int bufferSize =
            AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      bufferSize = SAMPLE_RATE * 2;
    }
    short[] audioBuffer = new short[bufferSize / 2];

    AudioRecord record =
            new AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
      Log.e(LOG_TAG, "Audio Record can't initialize!");
      return;
    }

    record.startRecording();

    Log.v(LOG_TAG, "Start recording");

    // Loop, gathering audio data and copying it to a round-robin buffer.
    while (shouldContinue) {
      int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
      int maxLength = recordingBuffer.length;
      int newRecordingOffset = recordingOffset + numberRead;
      int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
      int firstCopyLength = numberRead - secondCopyLength;
      // We store off all the data for the recognition thread to access. The ML
      // thread will copy out of this buffer into its own, while holding the
      // lock, so this should be thread safe.
      recordingBufferLock.lock();
      try {
        System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength);
        System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength);
        recordingOffset = newRecordingOffset % maxLength;
      } finally {
        recordingBufferLock.unlock();
      }
    }

    record.stop();
    record.release();
  }

  public synchronized void startRecognition() {
    if (recognitionThread != null) {
      return;
    }
    shouldContinueRecognition = true;
    recognitionThread =
            new Thread(
                    new Runnable() {
                      @Override
                      public void run() {
                        recognize();
                      }
                    });
    recognitionThread.start();
  }

  public synchronized void stopRecognition() {
    if (recognitionThread == null) {
      return;
    }
    shouldContinueRecognition = false;
    recognitionThread = null;
  }

  private void recognize() {
    Log.v(LOG_TAG, "Start recognition");

    short[] inputBuffer = new short[RECORDING_LENGTH];
    float[] floatInputBuffer = new float[RECORDING_LENGTH];
    float[] outputScores = new float[labels.size()];
    String[] outputScoresNames = new String[] {OUTPUT_SCORES_NAME};
    int[] sampleRateList = new int[] {SAMPLE_RATE};

    // Loop, grabbing recorded data and running the recognition model on it.
    while (shouldContinueRecognition) {
      // The recording thread places data in this round-robin buffer, so lock to
      // make sure there's no writing happening and then copy it to our own
      // local version.
      recordingBufferLock.lock();
      try {
        int maxLength = recordingBuffer.length;
        int firstCopyLength = maxLength - recordingOffset;
        int secondCopyLength = recordingOffset;
        System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength);
        System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength);
      } finally {
        recordingBufferLock.unlock();
      }

      // We need to feed in float values between -1.0f and 1.0f, so divide the
      // signed 16-bit inputs.
      for (int i = 0; i < RECORDING_LENGTH; ++i) {
        floatInputBuffer[i] = inputBuffer[i] / 32767.0f;
      }

      // Run the model.
      inferenceInterface.feed(SAMPLE_RATE_NAME, sampleRateList);
      inferenceInterface.feed(INPUT_DATA_NAME, floatInputBuffer, RECORDING_LENGTH, 1);
      inferenceInterface.run(outputScoresNames);
      inferenceInterface.fetch(OUTPUT_SCORES_NAME, outputScores);

      // Use the smoother to figure out if we've had a real recognition event.
      long currentTime = System.currentTimeMillis();
      final RecognizeCommands.RecognitionResult result =
              recognizeCommands.processLatestResults(outputScores, currentTime);

      runOnUiThread(
              new Runnable() {
                @Override
                public void run() {
                  // If we do have a new command, highlight the right list entry.
                  if (result.foundCommand.equals("yes") ) {
                    if (result.isNewCommand)
                    {
                      stopInferenceAndCapture();
                    }

                  }
                }
              });
      try {
        // We don't need to run too frequently, so snooze for a bit.
        Thread.sleep(MINIMUM_TIME_BETWEEN_SAMPLES_MS);
      } catch (InterruptedException e) {
        // Ignore
      }
    }

    Log.v(LOG_TAG, "End recognition");
  }

  // endregion
}
