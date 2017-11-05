package com.dji.videostreamdecodingsample;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.videostreamdecodingsample.Util.VideoResampler;
import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;

import com.dji.videostreamdecodingsample.media.NativeHelper;

import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.camera.Camera;
import dji.sdk.useraccount.UserAccountManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.R.attr.format;
import static dji.midware.media.d.w;
import static dji.sdksharedlib.extension.a.f;

public class MainActivity extends Activity implements DJIVideoStreamDecoder.IYuvDataListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String tag = "dsm_MainActivity";
    static final boolean useSurface = false;

    // Looper messages for main thread
    static final int MSG_WHAT_SHOW_TOAST = 0;
    static final int MSG_WHAT_UPDATE_TITLE = 1;

    // Looper messages for backend thread
    private static final int MSG_SEND = 0;
    private static final int MSG_CONNECT = 1;
    private static final int MSG_COMPRESS = 2;

    // constants
    private String MIME_TYPE = "video/avc";
    private int IFRAME_INTERVAL = 5;
    private int FRAME_RATE = 15;
    private int BIT_RATE = 6000000;
    private int KEY_FRAME_INTERVAL = 150;
    private String CODEC_NAME = "CompressCodec";

    // mediacodec relatd
    private MediaCodec codec;
    private MediaFormat mOutputFormat;

    // input/output queue
    private Queue<byte[]> inputQueue;
    private Queue<byte[]> outputQueue;

    // compress video height/width
    private int mHeight = 180;
    private int mWidth = 320;

    // UI Elements
    private TextView titleTv;
    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;
    private TextView mLogTv;
    private TextView savePath;
    private TextView screenShot;
    private TextView sync;

    // DJI SDK related
    private BaseProduct mProduct;
    private Camera mCamera;
    private DJICodecManager mCodecManager;
    protected VideoFeeder.VideoDataCallback mReceivedVideoDataCallBack = null;

    // Threads and Handlers
    private HandlerThread backgroundHandlerThread;
    public Handler backgroundHandler;

    // frame id and segment id
    private short mFrameID;
    private short mSegmentID;

    // Atomic booleans for locking
    private static AtomicBoolean sendReady;

    // socket
    private UDPSocket uploadSocket;

    // util variables for testings/debugging
    private String mLogMesg = "";
    private List<String> pathList = new ArrayList<>();

    @Override
    protected void onResume() {
        super.onResume();
        if (useSurface) {
            DJIVideoStreamDecoder.getInstance().resume();
        }
        notifyStatusChange();
        // loginAccount();

    }

    @Override
    protected void onPause() {
        if (mCamera != null) {
            if (VideoFeeder.getInstance().getVideoFeeds() != null
                    && VideoFeeder.getInstance().getVideoFeeds().size() > 0) {
                VideoFeeder.getInstance().getVideoFeeds().get(0).setCallback(null);
            }
        }
        if (useSurface) {
            DJIVideoStreamDecoder.getInstance().stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {

        if (Build.VERSION.SDK_INT >= 18) {
            backgroundHandlerThread.quitSafely();
        } else {
            backgroundHandlerThread.quit();
        }
        if (useSurface) {
            DJIVideoStreamDecoder.getInstance().destroy();
            NativeHelper.getInstance().release();
        }
        if (mCodecManager != null) {
            mCodecManager.destroyCodec();
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NativeHelper.getInstance().init();

        initPermission();

        setContentView(R.layout.activity_main);

        // create and initialize upload socket
        uploadSocket = new UDPSocket(ServerInfo.STREAM_SERVER_ADDRESS, ServerInfo.STREAM_SERVER_UDP_PORT, ServerInfo.SOCKET_TIMEOUT);
        sendReady = new AtomicBoolean();
        this.inputQueue = new LinkedList<byte[]>();
        this.outputQueue = new LinkedList<byte[]>();
        this.mFrameID = 0;
        this.mSegmentID = 0;

        initBackgroundThread();
        initUi();
        initPreviewer();
        backgroundHandler.obtainMessage(MSG_CONNECT).sendToTarget();
    }

    /**
     * Initialize background thread
     */
    private void initBackgroundThread() {
        backgroundHandlerThread = new HandlerThread("background handler thread");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case MSG_SEND:
                        if (sendReady.get()){
                            /* if (!keyFrameSent){
                                byte[] keyframe = getKeyFrameData();
                                if (null!=keyframe) {
                                    int size = keyframe.length;
                                    uploadData(keyframe, size);
                                    keyFrameSent = true;
                                }
                            } else {
                                uploadData((byte[])msg.obj, msg.arg1);
                                logd("MSG_SEND sent. in thread " + Thread.currentThread().getId());
                            }

//                            byte[] data = keyFrameSent? (byte[]) msg.obj : getKeyFrameData();
//                            int size = keyFrameSent? msg.arg1: getKeyFrameData().length ;
//                            uploadData(data, size);
//                            keyFrameSent = true;
                            */
                        } else {
                            logd(" not registered");
                        }
                        break;

                    case MSG_CONNECT:
                        registerDevice();
                        break;
                    case MSG_COMPRESS:
                        // to get yuv data from yuv callback
                        DJIVideoStreamDecoder.getInstance().parse((byte[])msg.obj, msg.arg1);
                        break;

                    default:
                        break;
                }
            }
        };
    }

    /**
     * Initialize necessary permission based on SDK version
     */
    private void initPermission() {
        // When the compile and target version is higher than 22, please request the
        // following permissions at runtime to ensure the
        // SDK work well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.VIBRATE,
                            Manifest.permission.INTERNET, Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SYSTEM_ALERT_WINDOW,
                            Manifest.permission.READ_PHONE_STATE,
                    }
                    , 1);
        }
    }

    /**
     * Initialize MediaCodec
     */
    @TargetApi(16)
    private void initCodec() {
        try {
            this.codec = MediaCodec.createEncoderByType(MIME_TYPE);
            // configure format
            this.mOutputFormat = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
            this.mOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            this.mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            this.mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            this.mOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            this.codec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                    Log.d("intentservice", "input buffer available");
                    // fill inputBuffer with valid data
                    if (!inputQueue.isEmpty()) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                        Log.d("intentservice", "input buffer filled");
                        byte[] yuvData = inputQueue.poll();
                        inputBuffer.clear();
                        inputBuffer.put(yuvData, 0, yuvData.length);
                        codec.queueInputBuffer(inputBufferId, 0, yuvData.length, 0, 0);
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId, MediaCodec.BufferInfo info) {
                    Log.d("intentservice", "output buffer available");
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    // bufferFormat is equivalent to mOutputFormat
                    // outputBuffer is ready to be processed or rendered.
                    byte[] compressedData = outputBuffer.array();
                    if (outputQueue != null) {
                        outputQueue.offer(compressedData);
                        codec.releaseOutputBuffer(outputBufferId, false);

                        Log.d("intentservice", "input buffer released");

                        if (mFrameID % KEY_FRAME_INTERVAL == 0) {
                            byte[] keyFrameData = getKeyFrameData();
                            uploadData(keyFrameData, keyFrameData.length);
                        }

                        uploadData(compressedData, compressedData.length);
                    }
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    // Subsequent data will conform to new format.
                    // Can ignore if using getOutputFormat(outputBufferId)
                    mOutputFormat = format; // option B
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    e.printStackTrace();
                }
            });

            codec.configure(this.mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void logd(String log){
        Log.d(tag, log);
    }
    private void loginAccount(){

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.e(TAG, "Login Success");
                    }
                    @Override
                    public void onFailure(DJIError error) {
                        showToast("Login Error:"
                                + error.getDescription());
                    }
                });
    }

    /**
     * Send socket request to server to register device
     * @throws IOException
     */
    private void registerDevice() {
        try {
            // create and init register socket
            UDPSocket registerSocket = new UDPSocket(ServerInfo.STREAM_SERVER_ADDRESS, ServerInfo.STREAM_SERVER_UDP_PORT, ServerInfo.SOCKET_TIMEOUT);

            // connect register socket
            registerSocket.connect();

            //send to server
            registerSocket.send(ServerInfo.REGISTER_DEVICE_DATA, ServerInfo.REGISTER_DEVICE_DATA.length);
            logd("Register device with deviceID:  "+ ServerInfo.DEVICE_ID);

            //and receive code from server
            byte[] buffer = new byte[1024];
            byte[] response = registerSocket.receive(buffer, 1);
            int recode = response != null ? (int)response[0] : -1;
            if (recode == 1){
                sendReady.set(true);
                logd("Register successfully: receive code 1.");
            }
            registerSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * get the keyframe
     * @return byte[] keyframe
     */
    private byte[] getKeyFrameData(){

        byte[] keyFrame = Utils.getDefaultKeyFrame(getApplicationContext());

            // send keyframe first
            // construct upload data
        return Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, keyFrame);

    }
    /**
     * upload data to server via uploadSocket
     *
     * @param data - data to be uploaded
     * @param size - data valid size
     */
    private void uploadData(byte[] data, int size) {
        try {
            // connect upload socket
            uploadSocket.connect();

            if (size > 1000){ // divide it by 1000 bytes

                int segments = size/1000 +1;
                byte[][] buffers = new byte[segments][1004]; //notice whether 1004 has to be declared
                // divide into segments
                logd("data.length ="+ data.length+" |data.size="+size);
                for (int i=0; i<segments; i++ ){
                    mSegmentID = (short) ((100+i)%(1<<16 -100));  // to prevent mSegmentID's overflow.
                    byte[] head = new byte[]{(byte) mFrameID, (byte) (mFrameID >>8), (byte) mSegmentID, (byte) (mSegmentID>>8)};
                    buffers[i] = Utils.byteMerger( head, Arrays.copyOfRange( data, i*1000, i==segments-1? size: (i+1)*1000 ) );
//                    System.arraycopy(data, i*1000, buffers[i], 4, i==segments-1? size- 1000*i :1000);
                    byte[] upData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, buffers[i]);

                    logd("buffer["+i+"].length= "+ buffers[i].length);
                    // upload data via uploadSocket
                    uploadSocket.send(upData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + buffers[i].length);
                }
            } else {
                // no need to divide
                mSegmentID=0;
                byte[] head = new byte[]{(byte) mFrameID, (byte) (mFrameID >>8), (byte) mSegmentID, (byte) (mSegmentID>>8)};

                byte[] buffer = Utils.byteMerger(head, data);
//                System.arraycopy(data, 0, buffer, 4, size);
                byte[] upData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, buffer);
                // upload data via uploadSocket
                uploadSocket.send(upData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + size);
            }

            mFrameID = (short) ((mFrameID +1) %1000 );

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SHOW_TOAST:
                    Toast.makeText(getApplicationContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_WHAT_UPDATE_TITLE:
                    if (titleTv != null) {
                        titleTv.setText((String) msg.obj);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void showToast(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        );
    }

    private void updateTitle(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        );
    }

    private void log(String s)
    {
        this.mLogTv.setText(s);
    }

    /**
     * Initialize UIs
     */
    private void initUi() {
        savePath = (TextView) findViewById(R.id.activity_main_save_path);
        screenShot = (TextView) findViewById(R.id.activity_main_screen_shot);
        screenShot.setSelected(false);
        sync = (TextView) findViewById(R.id.activity_main_sync);
        sync.setSelected(false);
        titleTv = (TextView) findViewById(R.id.title_tv);
        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
        mLogTv = (TextView) findViewById(R.id.activity_main_log);
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
        if (useSurface) {
            videostreamPreviewSf.setVisibility(View.VISIBLE);
            videostreamPreviewTtView.setVisibility(View.GONE);
            videostreamPreviewSh.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), videostreamPreviewSh.getSurface());
                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(MainActivity.this);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    DJIVideoStreamDecoder.getInstance().changeSurface(holder.getSurface());
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {

                }
            });
        } else {
            videostreamPreviewSf.setVisibility(View.GONE);
            videostreamPreviewTtView.setVisibility(View.VISIBLE);
        }
    }

    private int mCount =0;
    private String[] logmsg = new String[20];
    private ArrayList<byte[]> tempBufferList = new ArrayList<byte[]>();

    private void notifyStatusChange() {

        mProduct = VideoDecodingApplication.getProductInstance();

//        Log.d(TAG, "notifyStatusChange: " + (mProduct == null ? "Disconnect" : (mProduct.getModel() == null ? "null model" : mProduct.getModel().name())));
        if (mProduct != null && mProduct.isConnected() && mProduct.getModel() != null) {
            updateTitle(mProduct.getModel().name() + " Connected");
        } else {
            updateTitle("Disconnected");
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataCallBack = new VideoFeeder.VideoDataCallback() {
            @Override
            public void onReceive(byte[] videoBuffer, int size) {

//                logd( "camera recv video data size: " + size +" videobuffer.length= "+videoBuffer.length);
                //  note that videobuffer.length is 30720 as defined

                // if (sendReady.get()) {

                    if (sync.isSelected()) {
                        //remove below logs
//                        if (mCount < 10) {
//                            mLogMesg += "\nrecv data to parse: " + size +"\n";
//                            logd("\n recv data to parse:"+ size+"\n");
//                            for (int j=0; j<= size/1000; j++) {
//                                for (int i = 0; i < Math.min(10, size-1000*j); i++) {
////                                mLogMesg += videoBuffer[i] + " ";
//                                    logmsg[j] += " "+videoBuffer[i + 1000 * j];
//                                }
//                                mLogMesg += j+":"+logmsg[j]+" |";
//                                logd(j+ ":"+ logmsg[j]+" |");
//                                logmsg[j] = "";
//                            }
//                            logd("\n send "+ mCount+ "times");
//                        }else {
//                            log(mLogMesg);
//                        }
//                        mCount++;
//                        mLogMesg += "\n send "+ mCount+ "times";
                        //remove above logs

                        Message msg = new Message();
                        msg.what = MSG_SEND;
                        byte[] newVideoBuffer = Arrays.copyOfRange(videoBuffer, 0, videoBuffer.length);
                        msg.obj = newVideoBuffer;
                        msg.arg1 = size;
                        backgroundHandler.sendMessage(msg);

                    }

                    if (useSurface) {
                        DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                    } else if (mCodecManager != null) {
//                        logd( " send data to decoder");
                        mCodecManager.sendDataToDecoder(videoBuffer, size);
                        DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                        // tempBufferList.add(videoBuffer);

                        /*
                        Message msg = new Message();
                        msg.what = MSG_COMPRESS;
                        byte[] newVideoBuffer = Arrays.copyOfRange(videoBuffer, 0, videoBuffer.length);
                        msg.obj = newVideoBuffer;
                        msg.arg1 = size;
                        backgroundHandler.sendMessage(msg);
                        */
                        /*
                        if (tempBufferList.size() > 500) {
                            long totalSize = 0;
                            for (int i = 0; i < tempBufferList.size(); i ++) {
                                totalSize += tempBufferList.get(i).length;
                            }

                            byte[] newBuffer = new byte[(int)totalSize];
                            totalSize = 0;
                            for (int i = 0; i < tempBufferList.size(); i ++) {
                                byte[] buffer = tempBufferList.get(i);
                                System.arraycopy(newBuffer, (int)totalSize, buffer, 0, buffer.length);
                                totalSize += buffer.length;
                            }

                            VideoResampler resampler = new VideoResampler(newBuffer);
                            // resampler.setInput( inputUri );
                            resampler.setOutput( Uri.parse("/mnt/sdcard/resample.mp4") );

                            resampler.setOutputResolution( mWidth, mHeight );
                            resampler.setOutputBitRate( BIT_RATE );
                            resampler.setOutputFrameRate( FRAME_RATE );
                            resampler.setOutputIFrameInterval( IFRAME_INTERVAL );

                            try {
                                resampler.start();
                            } catch ( Throwable e ) {
                                e.printStackTrace();
                            }
                        }
                        */

                    }
                // }


            }
        };

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast("Disconnected");
        } else {
            if (!mProduct.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                if (VideoFeeder.getInstance().getVideoFeeds() != null
                        && VideoFeeder.getInstance().getVideoFeeds().size() > 0) {
                    VideoFeeder.getInstance().getVideoFeeds().get(0).setCallback(mReceivedVideoDataCallBack);
                }
            }
        }
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private void initPreviewer() {
        videostreamPreviewTtView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                if (mCodecManager == null) {
                    mCodecManager = new DJICodecManager(getApplicationContext(), surface, width, height);
                }
                DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), null);
                DJIVideoStreamDecoder.getInstance().setYuvDataListener(MainActivity.this);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mCodecManager != null) mCodecManager.cleanSurface();
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    private boolean mScaledFrame = false;
    private int mYuvFrameCount = 0;
    @Override
    public void onYuvDataReceived(byte[] yuvFrame, int width, int height) {
        //here if connected then send the output decoded framedata to server.
        logd("into onYuvDataReceived yuvFrame.length= "+ yuvFrame.length );
        // if (sendReady.get()) {
            // showToast("packupdata in YuvDataReceived callback");

            // compress received image
            /*
            YuvImage yuvImage = new YuvImage(yuvFrame, ImageFormat.YUY2, width, height, null);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Rect rect = new Rect(0, 0, width, height);
             */
            /* yuvImage.compressToJpeg(rect, 100, outputStream);
            byte[] imageByte = outputStream.toByteArray();
            Bitmap image = BitmapFactory.decodeByteArray(imageByte, 0, imageByte.length);
            Bitmap resizedImage= Bitmap.createScaledBitmap(image, mWidth, mHeight, true);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            resizedImage.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();

            this.inputQueue.offer(byteArray);
            logd("After compress, frame.length= "+ byteArray.length );
            */

            /* if (null == this.codec) {
                initCodec();
            } */
        // }
        byte[] y = new byte[width * height];
        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];
        byte[] nu = new byte[width * height / 4]; //
        byte[] nv = new byte[width * height / 4];
        System.arraycopy(yuvFrame, 0, y, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[y.length + 2 * i];
            u[i] = yuvFrame[y.length + 2 * i + 1];
        }
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        for (int j = 0; j < uvWidth / 2; j++) {
            for (int i = 0; i < uvHeight / 2; i++) {
                byte uSample1 = u[i * uvWidth + j];
                byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
                byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
                byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
                nu[2 * (i * uvWidth + j)] = uSample1;
                nu[2 * (i * uvWidth + j) + 1] = uSample1;
                nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
                nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
                nv[2 * (i * uvWidth + j)] = vSample1;
                nv[2 * (i * uvWidth + j) + 1] = vSample1;
                nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
                nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
            }
        }
        //nv21test
        byte[] bytes = new byte[yuvFrame.length];
        System.arraycopy(y, 0, bytes, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            bytes[y.length + (i * 2)] = nv[i];
            bytes[y.length + (i * 2) + 1] = nu[i];
        }

        mYuvFrameCount ++;

        Log.e("dsm", "frameCount: " + mYuvFrameCount);
        // store original file
        String albumName = "yuvTest";
        String originalFileName = "origin" + Integer.toString(mYuvFrameCount) + ".yuv";
        Log.e("dsm", "Storing file: " + originalFileName);
        File originalF = getAlbumStorageDir(albumName, originalFileName);
        FileOutputStream originalOutputStream;
        try {
            originalOutputStream = new FileOutputStream(originalF);
            originalOutputStream.write(bytes);
            originalOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        byte[] scaledImage = NativeHelper.getInstance().scaleImage(bytes, width, height, 320, 180, 1);

        // store scaled file
        String filename = "scaled" + Integer.toString(mYuvFrameCount) + ".yuv";
        File f = getAlbumStorageDir(albumName, filename);
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(f);
            outputStream.write(scaledImage);
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public File getAlbumStorageDir(String albumName, String filename) {
        // Get the directory for the user's public pictures directory.
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), albumName);
        if (!dir.mkdirs()) {
            Log.e("dsm", "Directory not created");
        }

        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), albumName + "/" + filename);

        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    public void onClick(View v) {
        if (screenShot.isSelected()) {
            screenShot.setText("Screen Shot");
            screenShot.setSelected(false);
            if (useSurface) {
                DJIVideoStreamDecoder.getInstance().changeSurface(videostreamPreviewSh.getSurface());
            }
            savePath.setText("");
            savePath.setVisibility(View.INVISIBLE);
        } else {
            screenShot.setText("Live Stream");
            screenShot.setSelected(true);
            if (useSurface) {
                DJIVideoStreamDecoder.getInstance().changeSurface(null);
            }
            savePath.setText("");
            savePath.setVisibility(View.VISIBLE);
            pathList.clear();
        }

        if (!sync.isSelected()) {
            sync.setText("Syncing");
            sync.setSelected(true);
        }
    }
}
