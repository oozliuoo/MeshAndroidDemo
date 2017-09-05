package com.dji.videostreamdecodingsample;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
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

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;

import com.dji.videostreamdecodingsample.media.NativeHelper;

import org.w3c.dom.Text;

import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.camera.Camera;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.useraccount.UserAccountManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.R.attr.focusable;
import static android.R.attr.port;
import static com.dji.videostreamdecodingsample.Utils.byteMerger;
import static java.lang.System.arraycopy;

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
    private short frameID;
    private short segmentID;

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

        setContentView(R.layout.activity_main);

        // create and initialize upload socket
        uploadSocket = new UDPSocket(ServerInfo.STREAM_SERVER_ADDRESS, ServerInfo.STREAM_SERVER_UDP_PORT, ServerInfo.SOCKET_TIMEOUT);

        sendReady = new AtomicBoolean();
        frameID =0;
        //new a background thread to handle server connection
        backgroundHandlerThread = new HandlerThread("background handler thread");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case MSG_SEND:
                        if (sendReady.get()){
                            uploadData((byte[]) msg.obj, msg.arg1);

                            logd("MSG_SEND sent. in thread "+ Thread.currentThread().getId());
                        } else {
                            logd(" not registered");
                        }
                        break;

                    case MSG_CONNECT:
                        registerDevice();
                        break;

                    default:
                        break;
                }
            }
        };

        initUi();
        initPreviewer();
        backgroundHandler.obtainMessage(MSG_CONNECT).sendToTarget();

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

    private boolean keyFrameSent = false;

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


            byte[] keyFrame = Utils.getDefaultKeyFrame(getApplicationContext());

            if (keyFrame != null && !keyFrameSent) {
                // send keyframe first
                // construct upload data
                byte[] keyframeData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, keyFrame);

                // upload data via uploadSocket
                uploadSocket.send(keyframeData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + keyFrame.length);
                keyFrameSent = true;
            }

            if (size > 1000){ // divide it by 1000 bytes

                int segments = size/1000 +1;
                byte[][] buffers = new byte[segments][1004]; //notice whether 1004 has to be declared
                // divide into segments
                logd("data.length ="+ data.length+" |data.size="+size);
                for (int i=0; i<segments; i++ ){
                    segmentID = (short) ((100+i)%(1<<16 -100));  // to prevent segmentID's overflow.
//                    buffers[i][0] = (byte) frameID;
//                    buffers[i][1] = (byte) (frameID>>8);
//                    buffers[i][2] = (byte) segmentID;
//                    buffers[i][3] = (byte) (segmentID>>8);
                    byte[] head = new byte[]{(byte) frameID, (byte) (frameID>>8), (byte) segmentID, (byte) (segmentID>>8)};
                    buffers[i] = Utils.byteMerger( head, Arrays.copyOfRange( data, i*1000, i==segments-1? size: (i+1)*1000 ) );
//                    System.arraycopy(data, i*1000, buffers[i], 4, i==segments-1? size- 1000*i :1000);
                    byte[] upData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, buffers[i]);

                    logd("buffer["+i+"].length= "+ buffers[i].length);
                    // upload data via uploadSocket
                    uploadSocket.send(upData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + buffers[i].length);
                }
            } else { // no need to divide

//                buffer[0] = (byte) frameID;
//                buffer[1] = (byte) (frameID>>8);
//                buffer[2] = 0x00;
//                buffer[3] = 0x00;
                segmentID=0;
                byte[] head = new byte[]{(byte) frameID, (byte) (frameID>>8), (byte) segmentID, (byte) (segmentID>>8)};

                byte[] buffer = Utils.byteMerger(head, data);
//                System.arraycopy(data, 0, buffer, 4, size);
                byte[] upData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, buffer);
                // upload data via uploadSocket
                uploadSocket.send(upData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + size);

            }

            frameID = (short) ((frameID+1) %1000 );

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

                logd( "camera recv video data size: " + size +" videobuffer.length= "+videoBuffer.length);
                // videobuffer.length = 30720

                if (sendReady.get()) {

                    if (sync.isSelected()) {
                        //remove below logs
//                        if (mCount < 10) {
//                            mLogMesg += "\nrecv data to parse: " + size + "\nbuffer (first 10): ";
//                            for (int j=0; j< size/1000; j++) {
//                                for (int i = 0; i < 10; i++) {
////                                mLogMesg += videoBuffer[i] + " ";
//                                    logmsg[j] += videoBuffer[i + 1000 * j] + " ";
//                                }
//                                mLogMesg += logmsg[j]+" | ";
//                            }
//                        } else {
//                            log(mLogMesg);
//                        }
//                        mCount++;
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
                        logd( " send data to decoder");
                        mCodecManager.sendDataToDecoder(videoBuffer, size);
                    }
                }


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

    private void packUpData(byte[] bytes, int size){

        if (size > 1000){ // divide it by 1000 bytes

            int segments = size/1000 +1;
            byte[][] buffers = new byte[segments][]; //notice whether 1004 has to be declared
            // divide into segments
            for (int i=0; i<segments; i++ ){
                segmentID = (short) ((100+i)%(1<<16 -100));  // to prevent segmentID's overflow.
                buffers[i][0] = (byte) (frameID<<8);
                buffers[i][1] = (byte) frameID;
                buffers[i][2] = (byte) (segmentID<<8);
                buffers[i][3] = (byte) (segmentID);
                System.arraycopy(bytes, i*1000, buffers[i], 4, i==segments-1? size- 1000*i :1000);
//                backgroundHandler.sendMessage(backgroundHandler.obtainMessage(MSG_SEND, buffers[i]));

                logd( "byte["+i+"]"+"= "+buffers[i].length);
            }
        } else { // no need to divide

            byte[] buffer = new byte[4+size];
            buffer[0] = (byte) (frameID << 8);
            buffer[1] = (byte) (frameID);

            buffer[2] = 0x00;
            buffer[3] = 0x00;
            System.arraycopy(bytes, 0, buffer, 4, size);
//            backgroundHandler.sendMessage(backgroundHandler.obtainMessage(MSG_SEND,buffer));


        }

        frameID = (short) ((frameID+1) %1000 );

    }

    @Override
    public void onYuvDataReceived(byte[] yuvFrame, int width, int height) {
        //here if connected then send the output decoded framedata to server.
       logd("into onYuvDataReceived yuvFrame.size= "+ yuvFrame.length );
        if (sendReady.get()) {
//            packUpData(yuvFrame);
//            showToast("packupdata in YuvDataReceived callback");
        }

        //In this demo, we test the YUV data by saving it into JPG files.
        if (DJIVideoStreamDecoder.getInstance().frameIndex % 30 == 0) {
            byte[] y = new byte[width * height];
            byte[] u = new byte[width * height / 4];
            byte[] v = new byte[width * height / 4];
            byte[] nu = new byte[width * height / 4]; //
            byte[] nv = new byte[width * height / 4];
            arraycopy(yuvFrame, 0, y, 0, y.length);
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
            arraycopy(y, 0, bytes, 0, y.length);
            for (int i = 0; i < u.length; i++) {
                bytes[y.length + (i * 2)] = nv[i];
                bytes[y.length + (i * 2) + 1] = nu[i];
            }
            Log.d(TAG,
                    "onYuvDataReceived: frame index: "
                            + DJIVideoStreamDecoder.getInstance().frameIndex
                            + ",array length: "
                            + bytes.length);
            screenShot(bytes, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot");
        }
    }

    /**
     * Save the buffered data into a JPG image file
     */
    private void screenShot(byte[] buf, String shotDir) {
        File dir = new File(shotDir);
        if (!dir.exists() || !dir.isDirectory()) {
            dir.mkdirs();
        }
        YuvImage yuvImage = new YuvImage(buf,
                ImageFormat.NV21,
                DJIVideoStreamDecoder.getInstance().width,
                DJIVideoStreamDecoder.getInstance().height,
                null);
        OutputStream outputFile;
        final String path = dir + "/ScreenShot_" + System.currentTimeMillis() + ".jpg";
        try {
            outputFile = new FileOutputStream(new File(path));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "test screenShot: new bitmap output file error: " + e);
            return;
        }
        if (outputFile != null) {
            yuvImage.compressToJpeg(new Rect(0,
                    0,
                    DJIVideoStreamDecoder.getInstance().width,
                    DJIVideoStreamDecoder.getInstance().height), 100, outputFile);
        }
        try {
            outputFile.close();
        } catch (IOException e) {
            Log.e(TAG, "test screenShot: compress yuv image error: " + e);
            e.printStackTrace();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                displayPath(path);
            }
        });
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

    private void displayPath(String path){
        path = path + "\n\n";
        if(pathList.size() < 6){
            pathList.add(path);
        }else{
            pathList.remove(0);
            pathList.add(path);
        }
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0 ;i < pathList.size();i++){
            stringBuilder.append(pathList.get(i));
        }
        savePath.setText(stringBuilder.toString());
    }

}
