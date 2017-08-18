package com.dji.videostreamdecodingsample;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import dji.keysdk.DJIKey;
import dji.keysdk.KeyManager;
import dji.keysdk.ProductKey;
import dji.keysdk.callback.KeyListener;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;

import static android.R.attr.port;


public class ConnectionActivity extends Activity implements View.OnClickListener {

    private static final String TAG = MainActivity.class.getName();

    private TextView mTextConnectionStatus;
    private TextView mTextProduct;
    private TextView mTextModelAvailable;
    private Button mBtnOpen;
    private Button mBtnJoin;
    private KeyListener firmVersionListener = new KeyListener() {
        @Override
        public void onValueChange(@Nullable Object oldValue, @Nullable Object newValue) {
            updateVersion();
        }
    };
    private DJIKey firmkey = ProductKey.create(ProductKey.FIRMWARE_PACKAGE_VERSION);

    private DatagramSocket socket;
    private static final int MSG_JOIN =0;

    private HandlerThread backThread;
    private Handler backHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        setContentView(R.layout.activity_connection);

        initUI();

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(VideoDecodingApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);
        backThread = new HandlerThread("backthread");
        backThread.start();
        backHandler = new Handler(backThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                Log.d("dsm","handle empty msg");
                joinServer();

            }

        };
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        updateTitleBar();
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        unregisterReceiver(mReceiver);
        if (KeyManager.getInstance() != null) {
            KeyManager.getInstance().removeListener(firmVersionListener);
        }
        super.onDestroy();
    }

    private void initUI() {

        mTextConnectionStatus = (TextView) findViewById(R.id.text_connection_status);
        mTextModelAvailable = (TextView) findViewById(R.id.text_model_available);
        mTextProduct = (TextView) findViewById(R.id.text_product_info);
        mBtnOpen = (Button) findViewById(R.id.btn_open);
        mBtnOpen.setOnClickListener(this);
        mBtnOpen.setEnabled(false);
        mBtnJoin = (Button) findViewById(R.id.btn_join);
        mBtnJoin.setOnClickListener(this);

    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSDKRelativeUI();
        }
    };

    private void updateTitleBar() {
        boolean ret = false;
        BaseProduct product = VideoDecodingApplication.getProductInstance();
        if (product != null) {
            if(product.isConnected()) {
                //The product is connected
                showToast(VideoDecodingApplication.getProductInstance().getModel() + " Connected");
                ret = true;
            } else {
                if(product instanceof Aircraft) {
                    Aircraft aircraft = (Aircraft)product;
                    if(aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        showToast("only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if(!ret) {
            // The product or the remote controller are not connected.
            showToast("Disconnected");
        }
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(ConnectionActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateVersion() {

        if(VideoDecodingApplication.getProductInstance() != null) {
            final String version = VideoDecodingApplication.getProductInstance().getFirmwarePackageVersion();
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if(TextUtils.isEmpty(version)) {
                        mTextModelAvailable.setText("N/A"); //Firmware version:
                    } else {
                        mTextModelAvailable.setText(version); //"Firmware version: " +
                    }
                }
            });
        }
    }

//    private Handler handler = new Handler(){
//        @Override
//        public void handleMessage(Message msg) {
//            switch (msg.what){
//                case MSG_JOIN:
//
//                    break;
//                default:
//                    break;
//            }
//        }
//    };





    private void joinServer() {

        try {

            InetAddress inetAddress = InetAddress.getByName("47.90.19.142");
            int port = 55055;
            String deviceid = "join_id";
            String udptoken2 = "DPJnH7rMjpZ1OJNYXcvUQS/bsZzf0tv4c0PpetVsdwc=";

            String joinCode = "2" + deviceid.length() + deviceid + udptoken2.length() + udptoken2;

            socket = new DatagramSocket(port);
            socket.setSoTimeout(2000);

            byte[] socketbyte = joinCode.getBytes();
            DatagramPacket joinPacket = new DatagramPacket(socketbyte, socketbyte.length, inetAddress, port);


            Log.d("dsm","send joinpacket");
            socket.send(joinPacket);


            DatagramPacket receivepacket = new DatagramPacket(socketbyte, 1);
            socket.receive(receivepacket);
            String rcvcode = new String(receivepacket.getData(), receivepacket.getOffset(), receivepacket.getLength());
            Log.d("dsm", "rcvcode= " + rcvcode + " |2.equals== " + "2".equals(rcvcode));
            if ("2".equals(rcvcode)) {
//            handler.sendMessage(handler.obtainMessage(MSG_JOIN));
                Intent intent = new Intent(ConnectionActivity.this, MainActivity.class);
                intent.putExtra("isJoin", true);
                startActivity(intent);
            } else {
                new Toast(this).makeText(this, "can not join please check if created", Toast.LENGTH_SHORT).show();
            }
        }catch (IOException e){
            e.printStackTrace();
        }finally {
            socket.close();
        }

    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {

            case R.id.btn_open: {
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("isJoin", false);
                startActivity(intent);
                break;
            }
            case R.id.btn_join:{

//                new Toast(this).makeText(this,"pressed button join", Toast.LENGTH_SHORT).show();
//                    joinServer();
                    backHandler.sendEmptyMessage(0);

                break;
            }

            default:
                break;
        }
    }

    private void refreshSDKRelativeUI() {

        BaseProduct mProduct = VideoDecodingApplication.getProductInstance();
        Log.v(TAG, "refreshSDKRelativeUI");

        if (null != mProduct && mProduct.isConnected()) {
            Log.v(TAG, "refreshSDK: True");
            mBtnOpen.setEnabled(true);

            String str = mProduct instanceof Aircraft ? "DJIAircraft" : "DJIHandHeld";
            mTextConnectionStatus.setText("Status: " + str + " connected");

            if (null != mProduct.getModel()) {
                mTextProduct.setText("" + mProduct.getModel().getDisplayName());
            } else {
                mTextProduct.setText(R.string.product_information);
            }
            if (KeyManager.getInstance() != null) {
                KeyManager.getInstance().addListener(firmkey, firmVersionListener);
            }
        } else {
            Log.v(TAG, "refreshSDK: False");
            mBtnOpen.setEnabled(false);

            mTextProduct.setText(R.string.product_information);
            mTextConnectionStatus.setText(R.string.connection_loose);
        }
    }

}
