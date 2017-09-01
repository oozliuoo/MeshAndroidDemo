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

    public static final String UDP_TOKEN1 = LOCAL_TEST ? "QsSwwZRW/vh07xHSlt7VxJBGdzTvuLk3FKHLShAY9S0=" : "h+itZWK9QAZVBLJL64ZDk+SUQKIWpOn25IcDPWhBB4s=";
    public static final String UDP_TOKEN2 = LOCAL_TEST ? "PAAs/9nDGfaiQnbHKMkxcWZM5eGYsFMLqkc3Q0pqdHs=" : "DPJnH7rMjpZ1OJNYXcvUQS/bsZzf0tv4c0PpetVsdwc=";

    public static final String DEVICE_ID = "open_id";

    public static final byte[] JOIN_IMAGE_TRANSMISSION_DATA = Utils.constructSocketData(REQUEST_IMAGE_TRANSMISSION_EVENT_ID, DEVICE_ID, UDP_TOKEN2);
    public static final byte[] REGISTER_DEVICE_DATA = Utils.constructSocketData(REGISTER_DEVICE_EVENT_ID, DEVICE_ID, UDP_TOKEN1);
    public static final byte[] PUSH_IMAGE_TRANSMISSION_DATA = Utils.constructSocketData(PUSH_IMAGE_TRANSMISSION_EVENT_ID, DEVICE_ID, UDP_TOKEN1);

    public static int SOCKET_TIMEOUT = 5000;
}
