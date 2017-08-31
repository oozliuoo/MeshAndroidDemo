package com.dji.videostreamdecodingsample;

/**
 * Created by zhexuanliu on 8/29/17.
 */

/**
 * Constant class containing constants related to server information
 */
public final class ServerInfo {
    public static boolean LOCAL_TEST = true;
    public static final String STREAM_SERVER_ADDRESS = LOCAL_TEST ? "10.7.18.47" : "47.90.19.142";
    public static final int STREAM_SERVER_UDP_PORT = 55055;

    public static final byte REGISTER_DEVICE_EVENT_ID = 0x01;
    public static final byte REQUEST_IMAGE_TRANSMISSION_EVENT_ID = 0x02;
    public static final byte PUSH_IMAGE_TRANSMISSION_EVENT_ID = 0x03;

    public static final String UDP_TOKEN1 = LOCAL_TEST ? "a6yC0L++Xh6zQpudv6jRVqT2qYTfIvGix4EDuMEEyNw=" : "h+itZWK9QAZVBLJL64ZDk+SUQKIWpOn25IcDPWhBB4s=";
    public static final String UDP_TOKEN2 = LOCAL_TEST ? "ZHg4q5rWXp2hY/Py4KRTqHaBBfldxYn2J+uzKig6aKU=" : "DPJnH7rMjpZ1OJNYXcvUQS/bsZzf0tv4c0PpetVsdwc=";
}
