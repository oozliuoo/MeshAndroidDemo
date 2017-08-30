package com.dji.videostreamdecodingsample;

/**
 * Created by zhexuanliu on 8/30/17.
 */

public class Utils {
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
}
