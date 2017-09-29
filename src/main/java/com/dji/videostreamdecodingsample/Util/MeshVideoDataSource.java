package com.dji.videostreamdecodingsample.Util;

import android.annotation.TargetApi;
import android.media.MediaDataSource;

import java.io.IOException;

/**
 * Created by zhexuanliu on 9/29/17.
 */

@TargetApi(23)
public class MeshVideoDataSource extends MediaDataSource {

    private volatile byte[] mRawVideo = null;

    /**
     * Constructor of this customized data source class
     * @param rawVideo
     */
    public MeshVideoDataSource(byte[] rawVideo) {
        this.mRawVideo = rawVideo;
    }

    @Override
    public synchronized long getSize() {
        synchronized (mRawVideo) {
            return mRawVideo.length;
        }
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        synchronized(mRawVideo) {
            int length = mRawVideo.length;
            if (position >= length) {
                // -1 indicates EOF
                return -1;
            }
            if (position + size > length) {
                size -= (position + size) - length;
            }
            System.arraycopy(mRawVideo, (int)position, buffer, offset, size);
            return size;
        }
    }

    @Override
    public void close() throws IOException {

    }
}
