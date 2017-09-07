package com.dji.videostreamdecodingsample;

import android.annotation.TargetApi;
import android.app.IntentService;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

import static android.R.attr.data;
import static com.dji.videostreamdecodingsample.media.NativeHelper.TAG;

/**
 * Created by durian on 2017/9/6.
 */

public class CompressIntentService extends IntentService {
    // constants
    public static String YUV_DATA_PART1_KEY = "YuvData1";
    public static String YUV_DATA_PART2_KEY = "YuvData1";
    private String CODEC_NAME = "CompressAndUploadServiceCodec";
    private String MIME_TYPE = "video/avc";
    private int IFRAME_INTERVAL = 5;
    private int FRAME_RATE = 30;
    private int BIT_RATE = 6000000;
    private int KEY_FRAME_INTERVAL = 150;

    // mediacodec relatd
    private MediaCodec codec;
    private MediaFormat mOutputFormat;

    // input/output queue
    private Queue<byte[]> inputQueue;
    private Queue<byte[]> outputQueue;

    // compress video height/width
    private int mHeight = 180;
    private int mWidth = 320;

    private short mSegmentId;
    private short mFrameId;

    // socket
    private UDPSocket uploadSocket;

    public CompressIntentService() {
        super("CompressIntentService");
    }

    @TargetApi(16)
    private void init() {
        try {
            this.codec = MediaCodec.createByCodecName(CODEC_NAME);
            this.inputQueue = new LinkedList<byte[]>();
            this.outputQueue = new LinkedList<byte[]>();

            // configure format
            this.mOutputFormat = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
            this.mOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            this.mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            this.mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            this.mOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            this.mFrameId = 0;
            this.mSegmentId = 0;

            uploadSocket = new UDPSocket(ServerInfo.STREAM_SERVER_ADDRESS, ServerInfo.STREAM_SERVER_UDP_PORT, ServerInfo.SOCKET_TIMEOUT);

            this.codec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                    Log.d("intentservice", "input buffer available");
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                    // fill inputBuffer with valid data
                    if (!inputQueue.isEmpty()) {
                        Log.d("intentservice", "input buffer filled");
                        byte[] yuvData = inputQueue.poll();
                        inputBuffer.put(yuvData);
                        codec.queueInputBuffer(inputBufferId, 0, yuvData.length, 0, 0);
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId, MediaCodec.BufferInfo info) {
                    Log.d("intentservice", "output buffer available");
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    // bufferFormat is equivalent to mOutputFormat
                    // outputBuffer is ready to be processed or rendered.
                    byte[] compressedData = outputBuffer.array();
                    outputQueue.offer(compressedData);
                    codec.releaseOutputBuffer(outputBufferId, false);

                    Log.d("intentservice", "input buffer released");
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    // Subsequent data will conform to new format.
                    // Can ignore if using getOutputFormat(outputBufferId)
                    mOutputFormat = format; // option B
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * get the keyframe
     * @return byte[] keyframe
     */
    private byte[] getKeyFrameData(){

        byte[] keyFrame = Utils.getDefaultKeyFrame(getApplicationContext());

        // send keyframe first
        // construct upload data
        return Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, keyFrame);
    }

    /**
     * The IntentService calls this method from the default worker thread with
     * the intent that started the service. When this method returns, IntentService
     * stops the service, as appropriate.
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        if (uploadSocket == null) {
            this.init();
        }

        if (intent.hasExtra(YUV_DATA_PART1_KEY) && intent.hasExtra(YUV_DATA_PART2_KEY)) {
            byte[] yuvData1 = intent.getByteArrayExtra(YUV_DATA_PART1_KEY);
            byte[] yuvData2 = intent.getByteArrayExtra(YUV_DATA_PART2_KEY);
            byte[] yuvData = Utils.byteMerger(yuvData1, yuvData2);String str ="";

            for (int i=0; i<10; i++){
                str += yuvData[i]+" ";
            }
            Log.d("intentservice", "yuvdata first 10 bytes:"+ str);

            inputQueue.offer(yuvData);

            codec.configure(this.mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            // wait for processing to complete
            while (this.outputQueue.isEmpty()) {

            }

            byte[] encodedData = this.outputQueue.poll();

            if (mFrameId % KEY_FRAME_INTERVAL == 0) {
                byte[] keyFrameData = getKeyFrameData();
                packupAndUpload(keyFrameData, keyFrameData.length);
            }

            packupAndUpload(encodedData, encodedData.length);

            codec.stop();
        }
    }

    /**
     * Segment data and upload it to server
     * @param data - data to be segmented and uploaded
     * @param size - size of data
     */
    private void packupAndUpload(byte[] data, int size) {
        try {
            // connect upload socket
            uploadSocket.connect();

            if (size > 1000) { // divide it by 1000 bytes

                int segments = size / 1000 + 1;
                byte[][] buffers = new byte[segments][1004]; //notice whether 1004 has to be declared
                // divide into segments
                // logd("data.length ="+ data.length+" |data.size="+size);
                for (int i = 0; i < segments; i++) {
                    mSegmentId = (short) ((100 + i) % (1 << 16 - 100));  // to prevent segmentID's overflow.
                    byte[] head = new byte[]{(byte) mFrameId, (byte) (mFrameId >> 8), (byte) mSegmentId, (byte) (mSegmentId >> 8)};
                    buffers[i] = Utils.byteMerger(head, Arrays.copyOfRange(data, i * 1000, i == segments - 1 ? size : (i + 1) * 1000));
                    // System.arraycopy(data, i*1000, buffers[i], 4, i==segments-1? size- 1000*i :1000);
                    byte[] upData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, buffers[i]);

                    // logd("buffer[" + i + "].length= " + buffers[i].length);
                    // upload data via uploadSocket
                    uploadSocket.send(upData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + buffers[i].length);
                }
            } else {
                // no need to divide
                mSegmentId = 0;
                byte[] head = new byte[]{(byte) mFrameId, (byte) (mFrameId >> 8), (byte) mSegmentId, (byte) (mSegmentId >> 8)};

                byte[] buffer = Utils.byteMerger(head, data);
                // System.arraycopy(data, 0, buffer, 4, size);
                byte[] upData = Utils.byteMerger(ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA, buffer);
                // upload data via uploadSocket
                uploadSocket.send(upData, ServerInfo.PUSH_IMAGE_TRANSMISSION_DATA.length + size);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        mFrameId = (short) ((mFrameId+1) %1000 );
    }

}
