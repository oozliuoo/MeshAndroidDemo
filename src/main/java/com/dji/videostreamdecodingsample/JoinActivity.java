package com.dji.videostreamdecodingsample;

import android.Manifest;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dji.videostreamdecodingsample.media.NativeHelper;

import dji.sdk.base.BaseProduct;
import dji.sdk.codec.DJICodecManager;

/**
 * Created by durian on 2017/8/20.
 */

public class JoinActivity extends Activity {

    private static final String TAG = "dsm_JoinActivity";
    private TextView titleTv;
    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;

    private BaseProduct mProduct;
    private DJICodecManager mCodecManager;
    private InetAddress inetAddress;
    private int port;
    private String deviceid;
    private String udptoken2;
    private String joinData;
    private byte[] registerDeviceData;
    private byte[] joinImageTransmissionData;
    private byte[] recvData;

    private HandlerThread backThread;
    private Handler backHandler;

    private static AtomicBoolean isConnected;
    private static final int MSG_CONNECT =0;
    private static final int MSG_DOWNLOAD = 1;

    private static DatagramPacket recvpacket;
    private static DatagramSocket recvsocket;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NativeHelper.getInstance().init(); // When the compile and target version is higher than 22, please request the
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
        initInet();
        initUI();


        isConnected = new AtomicBoolean();

        backThread = new HandlerThread("background handler thread");
        backThread.start();
        backHandler = new Handler(backThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case MSG_CONNECT:
                        logd("handle msgconnect");
                        try {
                            connectoServer();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
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

        // connect to server as joining
        if ( backHandler!=null && !backHandler.hasMessages(MSG_CONNECT) )
        backHandler.sendEmptyMessage(MSG_CONNECT);

    }

    @Override
    protected void onResume() {
        super.onResume();
        DJIVideoStreamDecoder.getInstance().resume();
    }

    @Override
    protected void onPause() {
        DJIVideoStreamDecoder.getInstance().stop();
        super.onPause();
    }


    @Override
    protected void onStop() {
        backHandler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {

        DJIVideoStreamDecoder.getInstance().destroy();
        NativeHelper.getInstance().release();
        if (Build.VERSION.SDK_INT >= 18) {
            backThread.quitSafely();
        } else {
            backThread.quit();
        }

        super.onDestroy();
    }

    private void logd(String log){
        Log.d(TAG, log);
    }

    private void initInet(){

        try {
            inetAddress = InetAddress.getByName(ServerInfo.STREAM_SERVER_ADDRESS);
        } catch (IOException e) {
            e.printStackTrace();
        }

        port = ServerInfo.STREAM_SERVER_UDP_PORT;
        deviceid = "open_id";
        udptoken2 = ServerInfo.UDP_TOKEN2;

        registerDeviceData = Utils.constructSocketData(ServerInfo.REGISTER_DEVICE_EVENT_ID, deviceid, udptoken2);
        joinImageTransmissionData = Utils.constructSocketData(ServerInfo.REQUEST_IMAGE_TRANSMISSION_EVENT_ID, deviceid, udptoken2);
    }

    private void initUI(){

        titleTv = (TextView) findViewById(R.id.title_tv);
        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
            videostreamPreviewSf.setVisibility(View.VISIBLE);
            videostreamPreviewTtView.setVisibility(View.GONE);
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


    private void connectoServer() throws IOException {
        // registerDevice();
        joinImageTransmission();
    }

    private void registerDevice() throws IOException {
        DatagramSocket socket = new DatagramSocket(port);
        DatagramPacket packet = new DatagramPacket(registerDeviceData, registerDeviceData.length, inetAddress, port);

        //send to server
        socket.send(packet);
        logd("Register device with deviceID:  "+ deviceid);

        //and receive code from server
        byte[] rcvData = new byte[1024];
        DatagramPacket receivepacket = new DatagramPacket(rcvData, 1);
        socket.receive(receivepacket);
        int recode = (int)receivepacket.getData()[0];
        if (recode == 1){
            isConnected.set(true);
            logd("Register successfully: receive code 1.");
        }
        socket.close();

    }

    private void joinImageTransmission() throws IOException {
        // initialize datagram
        DatagramSocket socket = new DatagramSocket(port);
        DatagramPacket packet = new DatagramPacket(joinImageTransmissionData, joinImageTransmissionData.length, inetAddress, port);

        //send to server
        socket.send(packet);
        logd("Joining Image Transmission as deviceID: "+ deviceid);

        //and receive code from server
        byte[] rcvData = new byte[1024];
        DatagramPacket receivepacket = new DatagramPacket(rcvData, 1);
        socket.receive(receivepacket);
        int recode = (int)receivepacket.getData()[0];
        if (recode == 2){
            isConnected.set(true);
            backHandler.sendEmptyMessage(MSG_DOWNLOAD);
            logd("Join successfully: receive code 2.");
        }
        socket.close();
    }
    private void downloadStream(){

        // aware the size of received data. 1004 if packed.
        // but now it's not 1004. unknow raw data from VideoFeeder.callback
        if (null == recvsocket) {
            try {
                recvData = new byte[6000];
                recvpacket = new DatagramPacket(recvData, recvData.length, inetAddress, port);
                recvsocket = new DatagramSocket(port);
            } catch (IOException e) {
                e.printStackTrace();
            }
            logd("recvsocket initialized for the first time");
        }

        //continually receive data from the server.
        while (isConnected.get()) {
            try {
                recvsocket.receive(recvpacket);
                logd("recvpacket length="+ recvpacket.getData().length);
            } catch (IOException e) {
                e.printStackTrace();
                isConnected.set(false);
                logd(" IOException: handlerthread id = " + Thread.currentThread().getId());
            }

            int validByteCount = 0;
            for (int i = 0; i < recvData.length; i ++)
            {
                validByteCount += recvData[i] != 0 ? 1 : 0;
            }
            int deviceIdLength = (int)recvData[1];
            byte[] videoData = new byte[validByteCount - 2 - deviceIdLength];

            for (int i = deviceIdLength + 2; i < validByteCount; i ++)
            {
                videoData[i - deviceIdLength - 2] = recvData[i];
            }

            DJIVideoStreamDecoder.getInstance().parse(videoData, videoData.length);
            logd("recvdata = " + new String(recvData));
        }
    }
    private void parseStream(){

    }

    private void decodeData(){

    }

    private void preview(){

    }
}
