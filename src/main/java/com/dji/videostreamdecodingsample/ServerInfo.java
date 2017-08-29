package com.dji.videostreamdecodingsample;

/**
 * Created by zhexuanliu on 8/29/17.
 */

/**
 * Constant class containing constants related to server information
 */
public final class ServerInfo {
    public static boolean LOCAL_TEST = true;
    public static final String STREAM_SERVER_ADDRESS = LOCAL_TEST ? "172.21.0.3" : "47.90.19.142";
    public static final int STREAM_SERVER_UDP_PORT = 55055;
}
