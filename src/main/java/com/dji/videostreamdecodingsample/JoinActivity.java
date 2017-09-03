package com.dji.videostreamdecodingsample;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.text.format.Formatter;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dji.videostreamdecodingsample.media.NativeHelper;

import dji.common.product.Model;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.sdkmanager.DJISDKManager;

import static android.R.attr.port;
import static com.dji.videostreamdecodingsample.MainActivity.MSG_WHAT_SHOW_TOAST;
import static com.dji.videostreamdecodingsample.MainActivity.MSG_WHAT_UPDATE_TITLE;
import static com.dji.videostreamdecodingsample.MainActivity.useSurface;

/**
 * Created by durian on 2017/8/20.
 */

public class JoinActivity extends Activity {

    private static final String TAG = "dsm_JoinActivity";

    // UI elements
    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;
    private TextView mLogTextView;
    private TextView titleTv;

    // DJI SDK related
    private DJICodecManager mCodecManager;
    private BaseProduct mProduct;
    private Camera mCamera;
    protected VideoFeeder.VideoDataCallback mReceivedVideoDataCallBack = null;

    // sockets
    private UDPSocket downloadSocket;

    // Threads and handlers
    private HandlerThread backThread;
    private Handler backHandler;

    // Looper messages
    private static final int MSG_CONNECT =0;
    private static final int MSG_DOWNLOAD = 1;

    // atomic booleans for locks
    private static AtomicBoolean isConnected;

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

        setContentView(R.layout.activity_join);
        // create and initialize download socket
        downloadSocket = new UDPSocket(ServerInfo.STREAM_SERVER_ADDRESS, ServerInfo.STREAM_SERVER_UDP_PORT, ServerInfo.SOCKET_TIMEOUT);

        isConnected = new AtomicBoolean();

        backThread = new HandlerThread("background handler thread");
        backThread.start();
        backHandler = new Handler(backThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case MSG_CONNECT:
                        logd("handle msgconnect");
                        joinImageTransmission();
                        break;
                    case MSG_DOWNLOAD:
                        logd("handle MSG_DOWNLOAD");
                        downloadStream();
                        break;
                    default:
                        break;

                }
            }
        };
        initUI();
        initPreviewer();

        // connect to server as joining
        if ( backHandler!=null && !backHandler.hasMessages(MSG_CONNECT) ) {
            backHandler.sendEmptyMessage(MSG_CONNECT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }


    @Override
    protected void onStop() {
        backHandler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {

        if (Build.VERSION.SDK_INT >= 18) {
            backThread.quitSafely();
        } else {
            backThread.quit();
        }
        NativeHelper.getInstance().release();
        downloadSocket.close();

        super.onDestroy();
    }

    private void logd(String log){
        Log.d(TAG, log);
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

    private void log(String s) {
        this.mLogTextView.setText(s);
    }

    private void initUI(){

        titleTv = (TextView) findViewById(R.id.title_tv);
        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
        mLogTextView = (TextView) findViewById(R.id.log_tv);
        videostreamPreviewSh = videostreamPreviewSf.getHolder();

        videostreamPreviewSf.setVisibility(View.GONE);
        videostreamPreviewTtView.setVisibility(View.VISIBLE);
        videostreamPreviewSh.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), videostreamPreviewSh.getSurface());
                // yuv data needed when screenshot
//                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(JoinActivity.this);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                DJIVideoStreamDecoder.getInstance().changeSurface(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });

    }

    /**
     * Return boardcast address in wifi network
     * @return
     * @throws IOException
     */
    private InetAddress getBroadcastAddress(){
        InetAddress result = null;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi.getDhcpInfo(); // handle null somehow
            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] quads = new byte[4];
            for (int k = 0; k < 4; k++)
                quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
            result = InetAddress.getByAddress(quads);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Join image transmission by sending request to server
     * @throws IOException
     */
    private void joinImageTransmission() {
        boolean receiveResponse = false;

        while (!receiveResponse) {
            try {
                // connect socket
                downloadSocket.connect();

                // send join image transmission request server
                downloadSocket.send(ServerInfo.JOIN_IMAGE_TRANSMISSION_DATA, ServerInfo.JOIN_IMAGE_TRANSMISSION_DATA.length);

                // receive code from server
                byte[] buffer = new byte[1024];
                byte[] receiveData = downloadSocket.receive(buffer, 1);
                int response = receiveData != null ? (int) receiveData[0] : -1;
                receiveResponse = true;
                if (response == 2) {
                    isConnected.set(true);
                    backHandler.sendEmptyMessage(MSG_DOWNLOAD);
                    logd("Join successfully: receive code 2.");
                }
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
        }
        /* if (null != joinSocket) {
            joinSocket.close();
        } */
    }

    /**
     * Continuously receive stream data from server
     */
    private void downloadStream() {
        byte[] recvData;
        byte[] response;
        /* try {
            // connect downloadSocket
            downloadSocket.connect();

        } catch (IOException e) {
            e.printStackTrace();
            isConnected.set(false);
            logd(" IOException: on initialize recvsocket handlerthread id = " + Thread.currentThread().getId());
        } */
        int count = 0;
        while (isConnected.get()) {
            try {
                // send join image transmission request server
                recvData = new byte[6000];
                response = downloadSocket.receive(recvData, recvData.length);
            } catch (SocketTimeoutException e) {
                logd("recvsocket receive timeout; try one more time");
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e2) {
                    e2.printStackTrace();
                }
                continue;
            } catch (IOException e) {
                e.printStackTrace();
                isConnected.set(false);
                logd(" IOException: handlerthread id = " + Thread.currentThread().getId());
                break;
            }
            try {
                byte[] videoData = parseVideoData(response);

                // should remove below after testing
                String logMsg = "";
                logMsg += "Received data length: " + videoData.length + "\n buffer (first 10): ";
                if(count < 10) {
                    for (int i = 0; i < Math.min(10, videoData.length); i++) {
                        logMsg += (int) videoData[i];
                    }
                }
                logd(logMsg);
                count += 1;
                logd("receive " + count + " times");
                // should remove above after testing
                mCodecManager.sendDataToDecoder(videoData, videoData.length);

                /*
                */

            } catch (Exception e) {
                logd("Generic Exception when parsing/displaying video data: " + e.toString());
                downloadSocket.close();
            }
        }

        downloadSocket.close();
    }

    /**
     * Parse video data as an array of bytes from response
     * @param response - response from server
     * @return video data as an array of bytes
     */
    private byte[] parseVideoData(byte[] response) {
        int deviceIdLength = (int) response[1];
        byte[] videoData = new byte[response.length - 2 - deviceIdLength];

        for (int i = deviceIdLength + 2; i < response.length; i++) {
            videoData[i - deviceIdLength - 2] = response[i];
        }

        return videoData;
    }

    private void parseStream(){

    }

    private void decodeData(){

    }

    private void preview(){

    }

    private void updateTitle(String s) {
    }

    private void showToast(String s) {
    }
}
