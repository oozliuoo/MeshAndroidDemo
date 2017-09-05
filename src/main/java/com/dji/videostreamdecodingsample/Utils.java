package com.dji.videostreamdecodingsample;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

import static java.lang.System.arraycopy;

/**
 * Created by zhexuanliu on 8/30/17.
 */

public class Utils {
    /**
     * Consturct a UDP socket data
     * @param eventID - event ID defined in mesh
     * @param deviceId - device id
     * @param udpToken - udp token used by this client
     * @return
     */
    public static byte[] constructSocketData(byte eventID, String deviceId, String udpToken) {
        byte deviceIdLength = (byte) deviceId.length();
        byte[] deviceIdBytes = deviceId.getBytes();
        byte udptokenLength = (byte) udpToken.length();
        byte[] udpTokenBytes = udpToken.getBytes();
        byte[] data = new byte[3 + deviceIdBytes.length + udpTokenBytes.length];
        data[0] = eventID;
        data[1] = deviceIdLength;

        for (int i = 0; i < deviceIdBytes.length; i ++) {
            data[i+2] = deviceIdBytes[i];
        }
        data[2 + deviceIdBytes.length] = udptokenLength;
        for (int i = 0; i < udpTokenBytes.length; i ++) {
            data[i + 2 + deviceIdBytes.length + 1] = udpTokenBytes[i];
        }

        return data;
    }

    //tool to merge two byte[]
    public static byte[] byteMerger(byte[] byte_1, byte[] byte_2){
        if (null == byte_1)
            return byte_2;
        if (null == byte_2)
            return byte_1;
        byte[] byte_3 = new byte[byte_1.length+byte_2.length];
        arraycopy(byte_1, 0, byte_3, 0, byte_1.length);
        arraycopy(byte_2, 0, byte_3, byte_1.length, byte_2.length);
        return byte_3;
    }

    //transfer a short(2bytes) to a byte[2]
    public static byte[] shrt2byte(short shrt){
        byte[] bytes = new byte[2];
        bytes[0] = (byte)shrt;
        bytes[1] = (byte)( shrt >>8);
        return bytes;
    }

    //cuz short occupies two bytes so here only get the lower 2 bytes of a buffer.
    public static short bytes2shrt(byte[] bytes){
        return (short) (bytes[0]&0xff | bytes[1]<<8);
    }


    // hard coded key frame
    public static byte[] getDefaultKeyFrame(Context context) {
        try {
            InputStream inputStream = context.getResources().openRawResource(R.raw.iframe_p4p_720_16x9);
            int length = inputStream.available();
            byte[] buffer = new byte[length];
            inputStream.read(buffer);
            inputStream.close();

            return buffer;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
}
