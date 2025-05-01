package com.cloudwebrtc.webrtc;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.cloudwebrtc.webrtc.audio.AudioSwitchManager;
import com.cloudwebrtc.webrtc.audio.AudioUtils;
import com.cloudwebrtc.webrtc.audio.LocalAudioTrack;
import com.cloudwebrtc.webrtc.record.AudioChannel;
import com.cloudwebrtc.webrtc.record.AudioSamplesInterceptor;
import com.cloudwebrtc.webrtc.record.MediaRecorderImpl;
import com.cloudwebrtc.webrtc.record.OutputAudioSamplesInterceptor;
import com.cloudwebrtc.webrtc.utils.Callback;
import com.cloudwebrtc.webrtc.utils.ConstraintsArray;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;
import com.cloudwebrtc.webrtc.utils.EglUtils;
import com.cloudwebrtc.webrtc.utils.MediaConstraintsUtils;
import com.cloudwebrtc.webrtc.utils.ObjectType;
import com.cloudwebrtc.webrtc.utils.PermissionUtils;
import com.cloudwebrtc.webrtc.video.LocalVideoTrack;
import com.cloudwebrtc.webrtc.video.VideoCapturerInfo;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Capturer;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera1Helper;
import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Enumerator;
import org.webrtc.Camera2Helper;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.Size;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.MethodChannel.Result;

public class GetUserMediaImpl {
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int DEFAULT_FPS = 30;

    private static final String PERMISSION_AUDIO = Manifest.permission.RECORD_AUDIO;
    private static final String PERMISSION_VIDEO = Manifest.permission.CAMERA;
    private static final String PERMISSION_SCREEN = "android.permission.MediaProjection";
    private static final int CAPTURE_PERMISSION_REQUEST_CODE = 1;
    private static final String GRANT_RESULTS = "GRANT_RESULT";
    private static final String PERMISSIONS = "PERMISSION";
    private static final String PROJECTION_DATA = "PROJECTION_DATA";
    private static final String RESULT_RECEIVER = "RESULT_RECEIVER";
    private static final String REQUEST_CODE = "REQUEST_CODE";

    static final String TAG = FlutterWebRTCPlugin.TAG;

    private final Map<String, VideoCapturerInfoEx> mVideoCapturers = new HashMap<>();
    private final Map<String, SurfaceTextureHelper> mSurfaceTextureHelpers = new HashMap<>();
    private final StateProvider stateProvider;
    private final Context applicationContext;

    static final int minAPILevel = Build.VERSION_CODES.LOLLIPOP;

    final AudioSamplesInterceptor inputSamplesInterceptor = new AudioSamplesInterceptor();
    private OutputAudioSamplesInterceptor outputSamplesInterceptor = null;
    JavaAudioDeviceModule audioDeviceModule;
    private final SparseArray<MediaRecorderImpl> mediaRecorders = new SparseArray<>();
    private AudioDeviceInfo preferredInput = null;
    private boolean isTorchOn;
    private Intent mediaProjectionData = null;

    public void screenRequestPermissions(ResultReceiver resultReceiver) {
        mediaProjectionData = null;
        final Activity activity = stateProvider.getActivity();
        if (activity == null) {
            return;
        }

        Bundle args = new Bundle();
        args.putParcelable(RESULT_RECEIVER, resultReceiver);
        args.putInt(REQUEST_CODE, CAPTURE_PERMISSION_REQUEST_CODE);

        ScreenRequestPermissionsFragment fragment = new ScreenRequestPermissionsFragment();
        fragment.setArguments(args);

        FragmentTransaction transaction =
                activity.getFragmentManager()
                        .beginTransaction()
                        .add(fragment, fragment.getClass().getName());

        try {
            transaction.commit();
        } catch (IllegalStateException ise) {
            Log.e(TAG, "Failed to commit fragment transaction", ise);
        }
    }

    public void requestCapturePermission(final Result result) {
        screenRequestPermissions(
                new ResultReceiver(new Handler(Looper.getMainLooper())) {
                    @Override
                    protected void onReceiveResult(int requestCode, Bundle resultData) {
                        int resultCode = resultData.getInt(GRANT_RESULTS);
                        if (resultCode == Activity.RESULT_OK) {
                            mediaProjectionData = resultData.getParcelable(PROJECTION_DATA);
                            result.success(true);
                        } else {
                            result.success(false);
                        }
                    }
                });
    }

    public static class ScreenRequestPermissionsFragment extends Fragment {
        private ResultReceiver resultReceiver = null;
        private int requestCode = 0;
        private int resultCode = 0;

        private void checkSelfPermissions(boolean requestPermissions) {
            if (resultCode != Activity.RESULT_OK) {
                Activity activity = this.getActivity();
                Bundle args = getArguments();
                resultReceiver = args.getParcelable(RESULT_RECEIVER);
                requestCode = args.getInt(REQUEST_CODE);
                requestStart(activity, requestCode);
            }
        }

        public void requestStart(Activity activity, int requestCode) {
            if (android.os.Build.VERSION.SDK_INT < minAPILevel) {
                Log.w(TAG, "Can't run requestStart() due to a low API level. API level 21 or higher is required.");
                return;
            } else {
                MediaProjectionManager mediaProjectionManager =
                        (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                this.startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), requestCode);
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            this.resultCode = resultCode;
            if (resultCode != Activity.RESULT_OK) {
                finish();
                Bundle resultData = new Bundle();
                resultData.putString(PERMISSIONS, PERMISSION_SCREEN);
                resultData.putInt(GRANT_RESULTS, resultCode);
                resultReceiver.send(requestCode, resultData);
                return;
            }
            Bundle resultData = new Bundle();
            resultData.putString(PERMISSIONS, PERMISSION_SCREEN);
            resultData.putInt(GRANT_RESULTS, resultCode);
            resultData.putParcelable(PROJECTION_DATA, data);
            resultReceiver.send(requestCode, resultData);
            finish();
        }

        private void finish() {
            Activity activity = getActivity();
            if (activity != null) {
                activity.getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            checkSelfPermissions(true);
        }
    }

    GetUserMediaImpl(StateProvider stateProvider, Context applicationContext) {
        this.stateProvider = stateProvider;
        this.applicationContext = applicationContext;
    }

    static private void resultError(String method, String error, Result result) {
        String errorMsg = method + "(): " + error;
        result.error(method, errorMsg, null);
        Log.d(TAG, errorMsg);
    }

    private void addDefaultAudioConstraints(MediaConstraints audioConstraints) {
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("echoCancellation", "true"));
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation2", "true"));
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"));
    }

    private Pair<String, VideoCapturer> createVideoCapturer(
            CameraEnumerator enumerator, boolean isFacing, String sourceId, CameraEventsHandler cameraEventsHandler) {
        VideoCapturer videoCapturer;
        final String[] deviceNames = enumerator.getDeviceNames();
        if (sourceId != null && !sourceId.equals("")) {
            for (String name : deviceNames) {
                if (name.equals(sourceId)) {
                    videoCapturer = enumerator.createCapturer(name, cameraEventsHandler);
                    if (videoCapturer != null) {
                        Log.d(TAG, "create user specified camera " + name + " succeeded");
                        return new Pair<>(name, videoCapturer);
                    } else {
                        Log.d(TAG, "create user specified camera " + name + " failed");
                        break;
                    }
                }
            }
        }

        String facingStr = isFacing ? "front" : "back";
        for (String name : deviceNames) {
            if (enumerator.isFrontFacing(name) == isFacing) {
                videoCapturer = enumerator.createCapturer(name, cameraEventsHandler);
                if (videoCapturer != null) {
                    Log.d(TAG, "Create " + facingStr + " camera " + name + " succeeded");
                    return new Pair<>(name, videoCapturer);
                } else {
                    Log.e(TAG, "Create " + facingStr + " camera " + name + " failed");
                }
            }
        }

        if (deviceNames.length > 0) {
            videoCapturer = enumerator.createCapturer(deviceNames[0], cameraEventsHandler);
            Log.d(TAG, "Falling back to the first available camera");
            return new Pair<>(deviceNames[0], videoCapturer);
        }

        return null;
    }

    private String getFacingMode(ConstraintsMap mediaConstraints) {
        return mediaConstraints == null ? null : mediaConstraints.getString("facingMode");
    }

    private String getSourceIdConstraint(ConstraintsMap mediaConstraints) {
        if (mediaConstraints != null && mediaConstraints.hasKey("deviceId")) {
            return mediaConstraints.getString("deviceId");
        }
        if (mediaConstraints != null && mediaConstraints.hasKey("optional") && mediaConstraints.getType("optional") == ObjectType.Array) {
            ConstraintsArray optional = mediaConstraints.getArray("optional");
            for (int i = 0, size = optional.size(); i < size; i++) {
                if (optional.getType(i) == ObjectType.Map) {
                    ConstraintsMap option = optional.getMap(i);
                    if (option.hasKey("sourceId") && option.getType("sourceId") == ObjectType.String) {
                        return option.getString("sourceId");
                    }
                }
            }
        }
        return null;
    }

    private ConstraintsMap getUserAudio(ConstraintsMap constraints, MediaStream stream) {
        AudioSwitchManager.instance.start();
        MediaConstraints audioConstraints = new MediaConstraints();
        String deviceId = null;
        if (constraints.getType("audio") == ObjectType.Boolean) {
            addDefaultAudioConstraints(audioConstraints);
        } else {
            audioConstraints = MediaConstraintsUtils.parseMediaConstraints(constraints.getMap("audio"));
            deviceId = getSourceIdConstraint(constraints.getMap("audio"));
        }

        Log.i(TAG, "getUserMedia(audio): " + audioConstraints);

        String trackId = stateProvider.getNextTrackUUID();
        PeerConnectionFactory pcFactory = stateProvider.getPeerConnectionFactory();
        AudioSource audioSource = pcFactory.createAudioSource(audioConstraints);

        if (deviceId != null) {
            try {
                if (VERSION.SDK_INT >= VERSION_CODES.M) {
                    setPreferredInputDevice(deviceId);
                }
            } catch (Exception e) {
                Log.e(TAG, "setPreferredInputDevice failed", e);
            }
        }

        AudioTrack track = pcFactory.createAudioTrack(trackId, audioSource);
        stream.addTrack(track);

        stateProvider.putLocalTrack(track.id(), new LocalAudioTrack(track));

        ConstraintsMap trackParams = new ConstraintsMap();
        trackParams.putBoolean("enabled", track.enabled());
        trackParams.putString("id", track.id());
        trackParams.putString("kind", "audio");
        trackParams.putString("label", track.id());
        trackParams.putString("readyState", track.state().toString());
        trackParams.putBoolean("remote", false);

        if (deviceId == null && VERSION.SDK_INT >= VERSION_CODES.M) {
            deviceId = "" + getPreferredInputDevice(preferredInput);
        }

        ConstraintsMap settings = new ConstraintsMap();
        settings.putString("deviceId", deviceId);
        settings.putString("kind", "audioinput");
        settings.putBoolean("autoGainControl", true);
        settings.putBoolean("echoCancellation", true);
        settings.putBoolean("noiseSuppression", true);
        settings.putInt("channelCount", 1);
        settings.putInt("latency", 0);
        trackParams.putMap("settings", settings.toMap());

        return trackParams;
    }

    void getUserMedia(final ConstraintsMap constraints, final Result result, final MediaStream mediaStream) {
        final ArrayList<String> requestPermissions = new ArrayList<>();

        if (constraints.hasKey("audio")) {
            switch (constraints.getType("audio")) {
                case Boolean:
                    if (constraints.getBoolean("audio")) {
                        requestPermissions.add(PERMISSION_AUDIO);
                    }
                    break;
                case Map:
                    requestPermissions.add(PERMISSION_AUDIO);
                    break;
                default:
                    break;
            }
        }

        if (constraints.hasKey("video")) {
            switch (constraints.getType("video")) {
                case Boolean:
                    if (constraints.getBoolean("video")) {
                        requestPermissions.add(PERMISSION_VIDEO);
                    }
                    break;
                case Map:
                    requestPermissions.add(PERMISSION_VIDEO);
                    break;
                default:
                    break;
            }
        }

        if (requestPermissions.isEmpty()) {
            resultError("getUserMedia", "TypeError, constraints requests no media types", result);
            return;
        }

        if (VERSION.SDK_INT < VERSION_CODES.M) {
            getUserMedia(constraints, result, mediaStream, requestPermissions);
            return;
        }

        requestPermissions(
                requestPermissions,
                new Callback() {
                    @Override
                    public void invoke(Object... args) {
                        List<String> grantedPermissions = (List<String>) args[0];
                        getUserMedia(constraints, result, mediaStream, grantedPermissions);
                    }
                },
                new Callback() {
                    @Override
                    public void invoke(Object... args) {
                        resultError("getUserMedia", "DOMException, NotAllowedError", result);
                    }
                });
    }

    void getDisplayMedia(final ConstraintsMap constraints, final Result result, final MediaStream mediaStream) {
        if (mediaProjectionData == null) {
            screenRequestPermissions(
                    new ResultReceiver(new Handler(Looper.getMainLooper())) {
                        @Override
                        protected void onReceiveResult(int requestCode, Bundle resultData) {
                            Intent mediaProjectionData = resultData.getParcelable(PROJECTION_DATA);
                            int resultCode = resultData.getInt(GRANT_RESULTS);

                            if (resultCode != Activity.RESULT_OK) {
                                resultError("screenRequestPermissions", "User didn't give permission to capture the screen.", result);
                                return;
                            }
                            getDisplayMedia(constraints, result, mediaStream, mediaProjectionData);
                        }
                    });
        } else {
            getDisplayMedia(constraints, result, mediaStream, mediaProjectionData);
        }
    }

    private void getDisplayMedia(final ConstraintsMap constraints, final Result result, final MediaStream mediaStream, final Intent mediaProjectionData) {
        PeerConnectionFactory pcFactory = stateProvider.getPeerConnectionFactory();

        // Video Capture
        VideoTrack displayTrack = null;
        VideoCapturer videoCapturer = new OrientationAwareScreenCapturer(
                mediaProjectionData,
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.d(TAG, "MediaProjection stopped");
                    }
                });
        if (videoCapturer == null) {
            resultError("screenRequestPermissions", "GetDisplayMediaFailed, User revoked permission to capture the screen.", result);
            return;
        }

        VideoSource videoSource = pcFactory.createVideoSource(true);
        String threadName = Thread.currentThread().getName() + "_texture_screen_thread";
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create(threadName, EglUtils.getRootEglBaseContext());
        videoCapturer.initialize(surfaceTextureHelper, applicationContext, videoSource.getCapturerObserver());

        WindowManager wm = (WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);

        VideoCapturerInfoEx info = new VideoCapturerInfoEx();
        info.width = size.x;
        info.height = size.y;
        info.fps = DEFAULT_FPS;
        info.isScreenCapture = true;
        info.capturer = videoCapturer;

        videoCapturer.startCapture(info.width, info.height, info.fps);
        Log.d(TAG, "OrientationAwareScreenCapturer.startCapture: " + info.width + "x" + info.height + "@" + info.fps);

        String videoTrackId = stateProvider.getNextTrackUUID();
        mVideoCapturers.put(videoTrackId, info);
        mSurfaceTextureHelpers.put(videoTrackId, surfaceTextureHelper);

        displayTrack = pcFactory.createVideoTrack(videoTrackId, videoSource);

        // Audio Capture
        AudioTrack audioTrack = null;
        boolean audioRequested = constraints.hasKey("audio") && (constraints.getType("audio") == ObjectType.Boolean ? constraints.getBoolean("audio") : true);
        if (audioRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaProjection projection = (MediaProjection) mediaProjectionData.getParcelableExtra("android.media.projection.extra.MediaProjection");
            if (projection != null) {
                audioTrack = createScreenAudioTrack(projection);
            } else {
                Log.e(TAG, "MediaProjection extra not found in Intent");
            }
        }

        ConstraintsArray audioTracks = new ConstraintsArray();
        ConstraintsArray videoTracks = new ConstraintsArray();
        ConstraintsMap successResult = new ConstraintsMap();

        if (displayTrack != null) {
            String id = displayTrack.id();
            LocalVideoTrack displayLocalVideoTrack = new LocalVideoTrack(displayTrack);
            videoSource.setVideoProcessor(displayLocalVideoTrack);
            stateProvider.putLocalTrack(id, displayLocalVideoTrack);

            ConstraintsMap track_ = new ConstraintsMap();
            track_.putBoolean("enabled", displayTrack.enabled());
            track_.putString("id", id);
            track_.putString("kind", "video");
            track_.putString("label", "screen");
            track_.putString("readyState", displayTrack.state().toString());
            track_.putBoolean("remote", false);

            ConstraintsMap settings = new ConstraintsMap();
            settings.putInt("width", info.width);
            settings.putInt("height", info.height);
            settings.putInt("frameRate", info.fps);
            track_.putMap("settings", settings.toMap());

            videoTracks.pushMap(track_);
            mediaStream.addTrack(displayTrack);
        }

        if (audioTrack != null) {
            String id = audioTrack.id();
            stateProvider.putLocalTrack(id, new LocalAudioTrack(audioTrack));

            ConstraintsMap track_ = new ConstraintsMap();
            track_.putBoolean("enabled", audioTrack.enabled());
            track_.putString("id", id);
            track_.putString("kind", "audio");
            track_.putString("label", "screen-audio");
            track_.putString("readyState", audioTrack.state().toString());
            track_.putBoolean("remote", false);

            ConstraintsMap settings = new ConstraintsMap();
            settings.putInt("channelCount", 2);
            settings.putInt("sampleRate", 48000);
            track_.putMap("settings", settings.toMap());

            audioTracks.pushMap(track_);
            mediaStream.addTrack(audioTrack);
        }

        String streamId = mediaStream.getId();
        Log.d(TAG, "MediaStream id: " + streamId);
        stateProvider.putLocalStream(streamId, mediaStream);
        successResult.putString("streamId", streamId);
        successResult.putArray("audioTracks", audioTracks.toArrayList());
        successResult.putArray("videoTracks", videoTracks.toArrayList());
        result.success(successResult.toMap());
    }

    private AudioTrack createScreenAudioTrack(MediaProjection mediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Screen audio capture requires Android Q or higher");
            return null;
        }

        try {
            // Configure audio capture with MediaProjection
            AudioPlaybackCaptureConfiguration audioConfig = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(android.media.AudioManager.USAGE_MEDIA)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int bufferSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(audioConfig)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            audioRecord.startRecording();

            // Create WebRTC AudioTrack
            PeerConnectionFactory pcFactory = stateProvider.getPeerConnectionFactory();
            MediaConstraints audioConstraints = new MediaConstraints();
            addDefaultAudioConstraints(audioConstraints);
            AudioSource audioSource = pcFactory.createAudioSource(audioConstraints);
            String audioTrackId = stateProvider.getNextTrackUUID();
            AudioTrack audioTrack = pcFactory.createAudioTrack(audioTrackId, audioSource);

            // Feed AudioRecord data into WebRTC (placeholder for native integration)
            new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                while (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        Log.d(TAG, "Audio data read: " + bytesRead + " bytes");
                        // TODO: Feed buffer into audioSource (requires WebRTC native customization)
                    }
                }
                audioRecord.stop();
                audioRecord.release();
            }).start();

            return audioTrack;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create screen audio track", e);
            return null;
        }
    }

    private void getUserMedia(
            ConstraintsMap constraints,
            Result result,
            MediaStream mediaStream,
            List<String> grantedPermissions) {
        ConstraintsMap[] trackParams = new ConstraintsMap[2];

        if ((grantedPermissions.contains(PERMISSION_AUDIO)
                && (trackParams[0] = getUserAudio(constraints, mediaStream)) == null)
                || (grantedPermissions.contains(PERMISSION_VIDEO)
                && (trackParams[1] = getUserVideo(constraints, mediaStream)) == null)) {
            for (MediaStreamTrack track : mediaStream.audioTracks) {
                if (track != null) {
                    track.dispose();
                }
            }
            for (MediaStreamTrack track : mediaStream.videoTracks) {
                if (track != null) {
                    track.dispose();
                }
            }
            resultError("getUserMedia", "Failed to create new track.", result);
            return;
        }

        ConstraintsArray audioTracks = new ConstraintsArray();
        ConstraintsArray videoTracks = new ConstraintsArray();
        ConstraintsMap successResult = new ConstraintsMap();

        for (ConstraintsMap trackParam : trackParams) {
            if (trackParam == null) {
                continue;
            }
            if (trackParam.getString("kind").equals("audio")) {
                audioTracks.pushMap(trackParam);
            } else {
                videoTracks.pushMap(trackParam);
            }
        }

        String streamId = mediaStream.getId();
        Log.d(TAG, "MediaStream id: " + streamId);
        stateProvider.putLocalStream(streamId, mediaStream);

        successResult.putString("streamId", streamId);
        successResult.putArray("audioTracks", audioTracks.toArrayList());
        successResult.putArray("videoTracks", videoTracks.toArrayList());
        result.success(successResult.toMap());
    }

    private boolean isFacing = true;

    @Nullable
    private Integer getConstrainInt(@Nullable ConstraintsMap constraintsMap, String key) {
        if (constraintsMap == null) {
            return null;
        }

        if (constraintsMap.getType(key) == ObjectType.Number) {
            try {
                return constraintsMap.getInt(key);
            } catch (Exception e) {
                return (int) Math.round(constraintsMap.getDouble(key));
            }
        }

        if (constraintsMap.getType(key) == ObjectType.String) {
            try {
                return Integer.parseInt(constraintsMap.getString(key));
            } catch (Exception e) {
                return (int) Math.round(Double.parseDouble(constraintsMap.getString(key)));
            }
        }

        if (constraintsMap.getType(key) == ObjectType.Map) {
            ConstraintsMap innerMap = constraintsMap.getMap(key);
            if (innerMap.getType("ideal") == ObjectType.Number) {
                return innerMap.getInt("ideal");
            }
        }

        return null;
    }

    private ConstraintsMap getUserVideo(ConstraintsMap constraints, MediaStream mediaStream) {
        ConstraintsMap videoConstraintsMap = null;
        ConstraintsMap videoConstraintsMandatory = null;
        if (constraints.getType("video") == ObjectType.Map) {
            videoConstraintsMap = constraints.getMap("video");
            if (videoConstraintsMap.hasKey("mandatory") && videoConstraintsMap.getType("mandatory") == ObjectType.Map) {
                videoConstraintsMandatory = videoConstraintsMap.getMap("mandatory");
            }
        }

        Log.i(TAG, "getUserMedia(video): " + videoConstraintsMap);

        CameraEnumerator cameraEnumerator;
        if (Camera2Enumerator.isSupported(applicationContext)) {
            Log.d(TAG, "Creating video capturer using Camera2 API.");
            cameraEnumerator = new Camera2Enumerator(applicationContext);
        } else {
            Log.d(TAG, "Creating video capturer using Camera1 API.");
            cameraEnumerator = new Camera1Enumerator(false);
        }

        String facingMode = getFacingMode(videoConstraintsMap);
        isFacing = facingMode == null || !facingMode.equals("environment");
        String deviceId = getSourceIdConstraint(videoConstraintsMap);
        CameraEventsHandler cameraEventsHandler = new CameraEventsHandler();
        Pair<String, VideoCapturer> result = createVideoCapturer(cameraEnumerator, isFacing, deviceId, cameraEventsHandler);

        if (result == null) {
            return null;
        }

        deviceId = result.first;
        VideoCapturer videoCapturer = result.second;

        if (facingMode == null && cameraEnumerator.isFrontFacing(deviceId)) {
            facingMode = "user";
        } else if (facingMode == null && cameraEnumerator.isBackFacing(deviceId)) {
            facingMode = "environment";
        }

        PeerConnectionFactory pcFactory = stateProvider.getPeerConnectionFactory();
        VideoSource videoSource = pcFactory.createVideoSource(false);
        String threadName = Thread.currentThread().getName() + "_texture_camera_thread";
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create(threadName, EglUtils.getRootEglBaseContext());

        if (surfaceTextureHelper == null) {
            Log.e(TAG, "surfaceTextureHelper is null");
            return null;
        }

        videoCapturer.initialize(surfaceTextureHelper, applicationContext, videoSource.getCapturerObserver());

        VideoCapturerInfoEx info = new VideoCapturerInfoEx();

        Integer videoWidth = getConstrainInt(videoConstraintsMap, "width");
        int targetWidth = videoWidth != null
                ? videoWidth
                : videoConstraintsMandatory != null && videoConstraintsMandatory.hasKey("minWidth")
                ? videoConstraintsMandatory.getInt("minWidth")
                : DEFAULT_WIDTH;

        Integer videoHeight = getConstrainInt(videoConstraintsMap, "height");
        int targetHeight = videoHeight != null
                ? videoHeight
                : videoConstraintsMandatory != null && videoConstraintsMandatory.hasKey("minHeight")
                ? videoConstraintsMandatory.getInt("minHeight")
                : DEFAULT_HEIGHT;

        Integer videoFrameRate = getConstrainInt(videoConstraintsMap, "frameRate");
        int targetFps = videoFrameRate != null
                ? videoFrameRate
                : videoConstraintsMandatory != null && videoConstraintsMandatory.hasKey("minFrameRate")
                ? videoConstraintsMandatory.getInt("minFrameRate")
                : DEFAULT_FPS;

        info.width = targetWidth;
        info.height = targetHeight;
        info.fps = targetFps;
        info.capturer = videoCapturer;
        info.cameraName = deviceId;

        Size actualSize = null;
        if (videoCapturer instanceof Camera1Capturer) {
            int cameraId = Camera1Helper.getCameraId(deviceId);
            actualSize = Camera1Helper.findClosestCaptureFormat(cameraId, targetWidth, targetHeight);
        } else if (videoCapturer instanceof Camera2Capturer) {
            CameraManager cameraManager = (CameraManager) applicationContext.getSystemService(Context.CAMERA_SERVICE);
            actualSize = Camera2Helper.findClosestCaptureFormat(cameraManager, deviceId, targetWidth, targetHeight);
        }

        if (actualSize != null) {
            info.width = actualSize.width;
            info.height = actualSize.height;
        }

        info.cameraEventsHandler = cameraEventsHandler;
        videoCapturer.startCapture(targetWidth, targetHeight, targetFps);

        cameraEventsHandler.waitForCameraOpen();

        String trackId = stateProvider.getNextTrackUUID();
        mVideoCapturers.put(trackId, info);
        mSurfaceTextureHelpers.put(trackId, surfaceTextureHelper);

        Log.d(TAG, "Target: " + targetWidth + "x" + targetHeight + "@" + targetFps + ", Actual: " + info.width + "x" + info.height + "@" + info.fps);

        VideoTrack track = pcFactory.createVideoTrack(trackId, videoSource);
        mediaStream.addTrack(track);

        LocalVideoTrack localVideoTrack = new LocalVideoTrack(track);
        videoSource.setVideoProcessor(localVideoTrack);

        stateProvider.putLocalTrack(track.id(), localVideoTrack);

        ConstraintsMap trackParams = new ConstraintsMap();

        trackParams.putBoolean("enabled", track.enabled());
        trackParams.putString("id", track.id());
        trackParams.putString("kind", "video");
        trackParams.putString("label", track.id());
        trackParams.putString("readyState", track.state().toString());
        trackParams.putBoolean("remote", false);

        ConstraintsMap settings = new ConstraintsMap();
        settings.putString("deviceId", deviceId);
        settings.putString("kind", "videoinput");
        settings.putInt("width", info.width);
        settings.putInt("height", info.height);
        settings.putInt("frameRate", info.fps);
        if (facingMode != null) settings.putString("facingMode", facingMode);
        trackParams.putMap("settings", settings.toMap());

        return trackParams;
    }

    void removeVideoCapturer(String id) {
        VideoCapturerInfoEx info = mVideoCapturers.get(id);
        if (info != null) {
            try {
                info.capturer.stopCapture();
                if (info.cameraEventsHandler != null) {
                    info.cameraEventsHandler.waitForCameraClosed();
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "removeVideoCapturer() Failed to stop video capturer", e);
            } finally {
                info.capturer.dispose();
                mVideoCapturers.remove(id);
                SurfaceTextureHelper helper = mSurfaceTextureHelpers.get(id);
                if (helper != null) {
                    helper.stopListening();
                    helper.dispose();
                    mSurfaceTextureHelpers.remove(id);
                }
            }
        }
    }

    @RequiresApi(api = VERSION_CODES.M)
    private void requestPermissions(
            final ArrayList<String> permissions,
            final Callback successCallback,
            final Callback errorCallback) {
        PermissionUtils.Callback callback =
                (permissions_, grantResults) -> {
                    List<String> grantedPermissions = new ArrayList<>();
                    List<String> deniedPermissions = new ArrayList<>();

                    for (int i = 0; i < permissions_.length; ++i) {
                        String permission = permissions_[i];
                        int grantResult = grantResults[i];

                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            grantedPermissions.add(permission);
                        } else {
                            deniedPermissions.add(permission);
                        }
                    }

                    for (String p : permissions) {
                        if (!grantedPermissions.contains(p)) {
                            errorCallback.invoke(deniedPermissions);
                            return;
                        }
                    }
                    successCallback.invoke(grantedPermissions);
                };

        final Activity activity = stateProvider.getActivity();
        final Context context = stateProvider.getApplicationContext();
        PermissionUtils.requestPermissions(
                context,
                activity,
                permissions.toArray(new String[permissions.size()]), callback);
    }

    void switchCamera(String id, Result result) {
        VideoCapturer videoCapturer = mVideoCapturers.get(id).capturer;
        if (videoCapturer == null) {
            resultError("switchCamera", "Video capturer not found for id: " + id, result);
            return;
        }

        CameraEnumerator cameraEnumerator;
        if (Camera2Enumerator.isSupported(applicationContext)) {
            Log.d(TAG, "Creating video capturer using Camera2 API.");
            cameraEnumerator = new Camera2Enumerator(applicationContext);
        } else {
            Log.d(TAG, "Creating video capturer using Camera1 API.");
            cameraEnumerator = new Camera1Enumerator(false);
        }

        final String[] deviceNames = cameraEnumerator.getDeviceNames();
        for (String name : deviceNames) {
            if (cameraEnumerator.isFrontFacing(name) == !isFacing) {
                CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) videoCapturer;
                cameraVideoCapturer.switchCamera(
                        new CameraVideoCapturer.CameraSwitchHandler() {
                            @Override
                            public void onCameraSwitchDone(boolean b) {
                                isFacing = !isFacing;
                                result.success(b);
                            }

                            @Override
                            public void onCameraSwitchError(String s) {
                                resultError("switchCamera", "Switching camera failed: " + id, result);
                            }
                        }, name);
                return;
            }
        }
        resultError("switchCamera", "Switching camera failed: " + id, result);
    }

    void startRecordingToFile(
            String path, Integer id, @Nullable VideoTrack videoTrack, @Nullable AudioChannel audioChannel)
            throws Exception {
        AudioSamplesInterceptor interceptor = null;
        if (audioChannel == AudioChannel.INPUT) {
            interceptor = inputSamplesInterceptor;
        } else if (audioChannel == AudioChannel.OUTPUT) {
            if (outputSamplesInterceptor == null) {
                outputSamplesInterceptor = new OutputAudioSamplesInterceptor(audioDeviceModule);
            }
            interceptor = outputSamplesInterceptor;
        }
        MediaRecorderImpl mediaRecorder = new MediaRecorderImpl(id, videoTrack, interceptor);
        mediaRecorder.startRecording(new File(path));
        mediaRecorders.append(id, mediaRecorder);
    }

    void stopRecording(Integer id) {
        MediaRecorderImpl mediaRecorder = mediaRecorders.get(id);
        if (mediaRecorder != null) {
            mediaRecorder.stopRecording();
            mediaRecorders.remove(id);
            File file = mediaRecorder.getRecordFile();
            if (file != null) {
                ContentValues values = new ContentValues(3);
                values.put(MediaStore.Video.Media.TITLE, file.getName());
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());
                applicationContext.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            }
        }
    }

    public void reStartCamera(IsCameraEnabled getCameraId) {
        for (Map.Entry<String, VideoCapturerInfoEx> item : mVideoCapturers.entrySet()) {
            if (!item.getValue().isScreenCapture && getCameraId.isEnabled(item.getKey())) {
                item.getValue().capturer.startCapture(item.getValue().width, item.getValue().height, item.getValue().fps);
            }
        }
    }

    public interface IsCameraEnabled {
        boolean isEnabled(String id);
    }

    public static class VideoCapturerInfoEx extends VideoCapturerInfo {
        public CameraEventsHandler cameraEventsHandler;
    }

    public VideoCapturerInfoEx getCapturerInfo(String trackId) {
        return mVideoCapturers.get(trackId);
    }

    @RequiresApi(api = VERSION_CODES.M)
    void setPreferredInputDevice(String deviceId) {
        android.media.AudioManager audioManager = ((android.media.AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE));
        final AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);
        if (devices.length > 0) {
            for (AudioDeviceInfo device : devices) {
                if (deviceId.equals(AudioUtils.getAudioDeviceId(device))) {
                    preferredInput = device;
                    audioDeviceModule.setPreferredInputDevice(preferredInput);
                    return;
                }
            }
        }
    }

    @RequiresApi(api = VERSION_CODES.M)
    int getPreferredInputDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            return -1;
        }
        android.media.AudioManager audioManager = ((android.media.AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE));
        final AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);
        for (int i = 0; i < devices.length; i++) {
            if (devices[i].getId() == deviceInfo.getId()) {
                return i;
            }
        }
        return -1;
    }
}
