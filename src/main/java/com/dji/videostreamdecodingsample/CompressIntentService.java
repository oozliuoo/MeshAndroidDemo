package com.dji.videostreamdecodingsample;

import android.app.IntentService;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.util.Log;

import static com.dji.videostreamdecodingsample.media.NativeHelper.TAG;

/**
 * Created by durian on 2017/9/6.
 */

public class CompressIntentService extends IntentService{
    public CompressIntentService() {
        super("CompressIntentService");
        // set true then if this process die before onHandleIntent, the process will be restarted and the most recent one Intent redelivered.
//        setIntentRedelivery(true);
    }
//    public static final String YUVEXTRA = "YUVEXTRA";
    public static final String YUVWIDTH = "YUVWIDTH";
    public static final String YUVHEIGHT = "YUVHEIGHT";

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {

        if (null!=intent){
//            byte[] yuvFrame = intent.getByteArrayExtra(YUVEXTRA);
            byte[] yuvFrame = DataHolder.getInstance().getYuvFrame();
            int width = intent.getIntExtra(YUVWIDTH, 0);
            int height = intent.getIntExtra(YUVHEIGHT, 0);

            String str ="";
            for (int i=0; i<10; i++){
                str += yuvFrame[i]+" ";
            }
            Log.d("intentservice", "yuvdata first 10 bytes:"+ str);

        }
    }
}
