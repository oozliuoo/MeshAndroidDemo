## Java Files Explanation
There are six java files in the package which I named it "com.dji.videostreamdecodingsample". And details are as follow:

### DJIVideoStreamDecoder.java

   This class is a helper class for hardware decoding, it manages tasks including raw data frame parsing, inserting i-frame, using `MediaCodec` for decoding, etc. 
   
   Here are the steps to learn how to use this class:
   
    1. Initialize and set the instance as a listener of NativeDataListener to receive the frame data.

    2. Send the raw data from camera to ffmpeg for frame parsing.
 
    3. Get the parsed frame data from ffmpeg parsing frame callback and cache the parsed framed data into the frameQueue.
 
    4. Initialize the MediaCodec as a decoder and then check whether there is any i-frame in the MediaCodec. If not, get the default i-frame from sdk resource and insert it at the head of frameQueue. Then dequeue the framed data from the frameQueue and feed it(which is Byte buffer) into the MediaCodec.

    5. Get the output byte buffer from MediaCodec, if a surface(Video Previewing View) is configured in the MediaCodec, the output byte buffer is only need to be released. If not, the output yuv data should invoke the callback and pass it out to external listener, it should also be released too.

    6. Release the ffmpeg and the MediaCodec, stop the decoding thread.

### NativeHelper.java

  This is a helper class to invoke native methods. Since we need invoke FFmpeg functions on JNI layer, this class is defined as an assistant to invoke all JNI methods and receive callback data.

### VideoDecodingApplication.java

  It's an Application class to do DJI SDK Registration, product connection, product change and product connectivity change checking. Then use broadcast to send the changes.

### MainActivity.java

  It's an Activity class to implement the features of the sample project, like implementing the UI elements, init a SurfaceView to preview the live stream data, save buffer data into a JPEG image file, etc. And what's more, stream uploading happens here. Once received the output buffers from MediaCodec, upload them to the server with datagram socket.

### ConnectionActivity.java

  It's an Activity class to accomplish the connection of phone and the aircraft through the remote controller. And provides two ways to connect: create(upload the stream) or join(download and decode the stream).

### JoinActivity.java

  It's an Activity class to get watchers to join the mission after clicking the “JOIN” button besides the “OPEN” button. It does the work of downloading and decoding the video stream from the server.

Two notes:
1.flow of get raw data from camera and preview in one device(the operating one):
Get videodata from VideoFeeder.callback -> DJIVideoStreamDecoder.parse -> NativeHelper.parse -> get data from NativeDataListener callback onDataRecv -> FrameQueue-> MediaCodec decode-> preview to the surface configured to mediacodec.

2.flow of this version：
“OPEN” upload: get data from VideoFeeder.callback -> upload to server
”JOIN“ download and decode： download stream from server -> DJIVideoStreamDecoder.parse -> …-> MediaCodec decode -> preview.
But note that, it does not work well now. So it would be much appreciated if can help to figure out what is on earth the best way to do this stuff.