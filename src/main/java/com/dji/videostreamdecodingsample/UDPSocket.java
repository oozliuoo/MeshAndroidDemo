package com.dji.videostreamdecodingsample;

import android.provider.ContactsContract;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

import static android.R.attr.data;
import static android.R.attr.port;
import static dji.midware.ble.BLE.bleRequestStatus.timeout;

/**
 * Created by zhexuanliu on 9/1/17.
 */

/**
 * Wrapper class of DatagramSocket used in this demo, handles
 * timeout setting and data parsing, as well as exception stack tracing
 */
public class UDPSocket {
    private int mTimeout;
    private DatagramSocket mSocket;
    private InetAddress mAddress;
    private int mOutPort;

    private static final int DEFAULT_TIMEOUT = 5000;
    private static final int DEFAULT_MAX_RETRY = 5;

    /**
     * Constructor of a UDPSocket
     *
     * @param ip - remote server's ip
     * @param outPort - port for sending data to
     * @param timeout - timeout of a socket
     */
    public UDPSocket(String ip, int outPort, int timeout) {
        try {
            this.mAddress = InetAddress.getByName(ip);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.mOutPort = outPort;
        this.mTimeout = timeout > 0 ? timeout : DEFAULT_TIMEOUT;
    }

    /**
     * Creates the socket if its not created before or if its
     * closed. Then set timeout for the socket
     */
    public void connect() throws IOException {
        if (null == this.mSocket) {
            this.mSocket = new DatagramSocket();
            this.mSocket.setSoTimeout(this.mTimeout);
            this.mSocket.setReceiveBufferSize(6000 * 30 * 100);
        }

        if (!this.mSocket.isConnected()) {
            this.mSocket.connect(this.mAddress, this.mOutPort);
        }
    }

    /**
     * Send DatagramPacket via the socket if it is available
     *
     * @param data - data of DatagramPacket to be sent
     * @param size - size of data
     */
    public void send(byte[] data, int size) throws IOException {
        if (null != this.mSocket && null != this.mAddress) {
            DatagramPacket p = new DatagramPacket(data, size, this.mAddress, this.mOutPort);
            this.mSocket.send(p);
        }
    }

    /**
     * Receive data from the socket using a datagram packet
     *
     * @param buffer - buffer used in Datagram packet to be received
     * @param size - size (length) of the data
     * @return Received data
     */
    public byte[] receive(byte buffer[], int size) throws IOException {
        byte[] returnedData;

        if (null != this.mSocket) {
            DatagramPacket p = new DatagramPacket(buffer, size);
            this.mSocket.receive(p);
            returnedData = Arrays.copyOfRange(p.getData(), p.getOffset(), p.getLength());
            return returnedData;
        }

        return null;
    }


    /**
     * Disconnect socket
     */
    public void disconnect() {
        this.mSocket.disconnect();
    }

    /**
     * Disconnect and close the socket
     */
    public void close() {
        this.disconnect();
        this.mSocket.close();
    }
}
