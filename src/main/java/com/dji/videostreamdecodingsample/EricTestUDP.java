package com.dji.videostreamdecodingsample;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by zhexuanliu on 9/2/17.
 */

class EricTestUDP {
    public static void main(String[] args) {
        TestUDP t = new TestUDP();
        t.joinImageTransmission();
        t.downloadStream();
    }
}

class TestUDP {
    // sockets
    private UDPSocket downloadSocket;

    // atomic booleans for locks
    private static AtomicBoolean isConnected;

    public TestUDP() {
        // create and initialize download socket
        isConnected = new AtomicBoolean(false);
    }

    /**
     * Join image transmission by sending request to server
     * @throws IOException
     */
    public void joinImageTransmission() {
        boolean receiveResponse = false;

        while (!receiveResponse) {
            try {
                downloadSocket = new UDPSocket(ServerInfo.STREAM_SERVER_ADDRESS, ServerInfo.STREAM_SERVER_UDP_PORT, ServerInfo.SOCKET_TIMEOUT);
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
                    System.out.println("Join successfully: receive code 2.");
                }
            } catch (IOException e) {
                downloadSocket.close();
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
    public void downloadStream() {
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
                System.out.println("recvsocket receive timeout; try one more time");
                continue;
            } catch (IOException e) {
                e.printStackTrace();
                isConnected.set(false);
                System.out.println(" IOException: handlerthread id = " + Thread.currentThread().getId());
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
                System.out.println(logMsg);
                count += 1;
                System.out.println("receive " + count + " times");
                // should remove above after testing

                // DJIVideoStreamDecoder.getInstance().parse(videoData, videoData.length);
            } catch (Exception e) {
                System.out.println("Generic Exception when parsing/displaying video data: " + e.toString());
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
}
