package com.dji.videostreamdecodingsample;

/**
 * Created by durian on 2017/9/6.
 */

public class DataHolder {
    private static final DataHolder holder = new DataHolder();
    public static DataHolder getInstance() {return holder;}

    private byte[] yuvFrame;

    public byte[]  getYuvFrame(){
        return yuvFrame;
    }
    public void setYuvFrame( byte[] yuvFrame){
        this.yuvFrame = yuvFrame;
    }
}
