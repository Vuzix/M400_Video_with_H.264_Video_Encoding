/*'*****************************************************************************
Copyright (c) 2018, Vuzix Corporation
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

*  Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

*  Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

*  Neither the name of Vuzix Corporation nor the names of
   its contributors may be used to endorse or promote products derived
   from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*'*****************************************************************************/
package vuzix.com.sample.video_encoder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This sample shows how to use the hardware encoder for H.264 video encoding. Using the hardware
 * encoder produces better capture performance, lower CPU load, and less heat generation from the
 * M300.
 *
 * This sample uses the synchronous API
 */
public class MainActivity extends Activity implements RotationListener.rotationCallbackFn {
    private static final String TAG = "MediaCodec_App";
    private Button mRecordButton;
    private TextureView mTextureView;
    private String mCameraId;
    protected CameraDevice mCameraDevice;
    protected CameraCaptureSession mCameraCaptureSessions;
    protected CaptureRequest.Builder mCaptureRequestBuilder;
    private ImageReader mImageReader;
    private RotationListener mRotationListener;

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundPreviewHandler;
    private HandlerThread mBackgroundPreviewThread;

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    // parameters for the encoder
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames
    private int TIMEOUT_USEC = 100;

    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final String ENCODER = "OMX.qcom.video.encoder.avc"; // HW encoder
    //private static final String ENCODER = "OMX.google.h264.encoder";  // SW encoder

    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private MediaCodec.BufferInfo mBufferInfo;
    private ByteBuffer[] encoderInputBuffers;

    private boolean isVideoRecording = false;
    private boolean mMuxerStarted;
    private int mTrackIndex;
    private long mFramesIndex;

    /**
     * Setup the view when created
     * @param savedInstanceState - unused, passed to superclass
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mRecordButton = (Button) findViewById(R.id.btn_record);
        mRecordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRecordOrStopClick();
            }
        });
        mRotationListener = new RotationListener();
    }

    private void onRecordOrStopClick() {
        if(!isVideoRecording) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            recordVideo();
            mRecordButton.setText(R.string.stop);
            isVideoRecording = true;
        }
        else{
            try {
                mCameraCaptureSessions.abortCaptures();
                mCameraCaptureSessions.close();
                createCameraPreview();
                mRecordButton.setText(R.string.record);
                isVideoRecording = false;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Creates required background threads. Called when we resume
     */
    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        mBackgroundPreviewThread = new HandlerThread("Camera Preview");
        mBackgroundPreviewThread.start();
        mBackgroundPreviewHandler = new Handler(mBackgroundPreviewThread.getLooper());
    }

    /**
     * Destroys background threads. Called when we pause
     */
    protected void stopBackgroundThread() {
        mBackgroundPreviewThread.quitSafely();
        mBackgroundThread.quitSafely();

        try {
            mBackgroundPreviewThread.join();
            mBackgroundPreviewThread = null;
            mBackgroundPreviewHandler = null;

            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configure and start the video recorder using the selected encoder
     */
    protected void recordVideo() {
        if(null == mCameraDevice) {
            Log.e(TAG, "mCameraDevice is null");
            return;
        }

        try {
            int encodeWidth =1280;        // record video size
            int encodeHeight =720;       // record video size
            int encodeBitRate = 6000000; // Mbps

            prepareEncoder( encodeWidth, encodeHeight, encodeBitRate);
            List<Surface> outputSurfaces = new ArrayList<Surface>();

            mImageReader = ImageReader.newInstance(encodeWidth, encodeHeight, ImageFormat.YUV_420_888, 1);
            Surface readerSurface = mImageReader.getSurface();
            outputSurfaces.add(readerSurface);

            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(640, 360); // preview size
            Surface previewSurface = new Surface(texture);
            outputSurfaces.add(previewSurface);

            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(mImageReader.getSurface());
            mCaptureRequestBuilder.addTarget(previewSurface);
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {

                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer bufferY = image.getPlanes()[0].getBuffer();
                        ByteBuffer bufferU = image.getPlanes()[1].getBuffer();

                        byte[] bytes = new byte[bufferY.capacity() + bufferU.capacity()];
                        bufferY.get(bytes,0, bufferY.capacity() );
                        bufferU.get(bytes,bufferY.capacity(), bufferU.capacity() );
                        sendToCodec(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void sendToCodec(byte[] bytes) throws IOException {
                    int inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                    long ptsUsec = computePresentationTime(mFramesIndex);

                    if(inputBufIndex >= 0){
                            Log.d(TAG, "send to codec image byte:" + bytes.length);
                            ByteBuffer inputBuf = encoderInputBuffers[inputBufIndex];
                            inputBuf.clear();
                            inputBuf.put(bytes);
                            mEncoder.queueInputBuffer(inputBufIndex, 0, bytes.length, ptsUsec, 0);
                            mFramesIndex++;
                    }
                }
            }, mBackgroundHandler);

            mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCameraCaptureSessions = session;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
                @Override
                public void onClosed(CameraCaptureSession session) {
                    int inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                    long ptsUsec = computePresentationTime(mFramesIndex);
                    mEncoder.queueInputBuffer(inputBufIndex, 0, 0, ptsUsec, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }, mBackgroundPreviewHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
               drainEncoder();
            }
        }).start();
    }

    /**
     * Utility to convert frame index to millisecond timestamp
     * @param frameIndex long index of the frame number
     * @return long corresponding millisecond value
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / FRAME_RATE;
    }

    /**
     * Utility to configure the encoder parameters
     * @param width int width of the video image in pixels
     * @param height int height of the video image in pixels
     * @param bitRate int rate of the encoder in bits per second
     */
    private void prepareEncoder(int width, int height, int bitRate){

        Surface surface = null;

        try {
            mBufferInfo = new MediaCodec.BufferInfo();

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            int colorFormat = selectColorFormat(selectCodec(MIME_TYPE), MIME_TYPE);

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            mEncoder = MediaCodec.createByCodecName(selectCodec(MIME_TYPE).getName());
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoder.start();
            mMuxer = new MediaMuxer(getOutputMediaPath(width, height), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mMuxer.setOrientationHint(getImageRotationDegrees(false));

            encoderInputBuffers = mEncoder.getInputBuffers();

        }catch(IOException e) {
            e.printStackTrace();
        }

        mMuxerStarted = false;
        mTrackIndex = -1;
        mFramesIndex = 0;
    }

    /**
     * Retrieve the buffered video data, write it to the muxer, and release the buffers
     */
    private void drainEncoder(){

        boolean encoderDone = false;
        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();

        while(!encoderDone){

            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC );

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                Log.d(TAG, "no output from encoder available");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
                Log.d(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }

                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;

            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }
                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }
                mEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "end of stream reached");
                    encoderDone = true;
                    break;      // out of while
                }
            }
        }

        releaseEncoder();
    }

    /**
     * Utility to create a unique filename for the captured video
     * @param width - int width of the video
     * @param height - int height of the video
     * @return String filename
     */
    private String getOutputMediaPath(int width, int height){
        File mediaStorageDir = new File(
                Environment.getExternalStorageDirectory(), "video_encoder");

        if (!mediaStorageDir.exists()) {
            // if you cannot make this folder return
            if (!mediaStorageDir.mkdirs()) {
                Log.e(TAG, "can not create the directory");
            }
        }

        String timeStamp = String.valueOf(System.currentTimeMillis());
        String outputPath = (mediaStorageDir.getPath() + File.separator + "VIDEO_" +timeStamp+"_"+width +"x"+height + ".mp4");

        return outputPath;
    }

    /**
     * Utility to converts the mime type string into a codec info class
     * @param mimeType String codec identifier
     * @return MediaCodecInfo matching selected mimeType, or null
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            String codecName = codecInfo.getName();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType) && codecName.equalsIgnoreCase(ENCODER)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * Utility to converts the codec info and mime type string into color format
     * @param codecInfo MediaCodecInfo describing the codec
     * @param mimeType String identifying the mime type
     * @return int representing the color format, 0 on failure
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return 0;   // not reached
    }

    /**
     * Utility to determine if the proposed color format is valid
     * @param colorFormat int candidate for a color format identifier
     * @return true if recognized as a valid format
     */
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return true;
            default:
                return false;
        }
    }

    /**
     * Releases encoder resources.
     */
    private void releaseEncoder() {
        Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }

        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * Starts the live view of the camera
     */
    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(640, 360);// preview size
            Surface surface = new Surface(texture);

            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (null == mCameraDevice) {
                        return;
                    }
                    mCameraCaptureSessions = session;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, mBackgroundPreviewHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Opens the camera for immediate preview
     */
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.d(TAG, "is camera open");
        try {
            mCameraId = manager.getCameraIdList()[0];
            // Add permission for camera, let user grant the permission
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            manager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.d(TAG, "onOpened");
                    mCameraDevice = camera;
                    createCameraPreview();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    mCameraDevice.close();
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }catch(SecurityException e){
            e.printStackTrace();
        }
    }

    /**
     * Updates the preview by providing an orientation and preview handler
     */
    protected void updatePreview() {
        if(null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        try {
            mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getImageRotationDegrees(false) );
            mTextureView.setRotation(getImageRotationDegrees(true));
            mCameraCaptureSessions.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundPreviewHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRotationChanged(int newRotation) {
        Log.i(TAG, "New device orientation " + Integer.toString(newRotation) );
        updatePreview();
    }

    private int getImageRotationDegrees(boolean invert) {
        // The encoder operates upside-down by default.  So invert this.  Our display is only ROTATION_0 or ROTATION_180
        int rotation = 180;
        if ( ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation() == Surface.ROTATION_180) {
            rotation = 0;
        }
        if(invert) {
            if( 0 == rotation ) {
                rotation = 180;
            }
            else {
                rotation = 0;
            }
        }
        //Log.i(TAG, "Rotation " + Integer.toString(rotation) );
        return rotation;
    }

    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();

        mTextureView = (TextureView) findViewById(R.id.texture);
        if(mTextureView.isAvailable()){
            openCamera();
        }

        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        mRotationListener.listen(this, this);
    }

    @Override
    protected void onPause() {
        mRotationListener.stop();
        Log.e(TAG, "onPause");
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!, you don't have permission to run this app", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
