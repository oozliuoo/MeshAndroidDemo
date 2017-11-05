#include <stdio.h>
#include <sys/types.h>
#include <pthread.h> 
#include <ffmpeg.h>
 
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavfilter/avfilter.h"
#include "libswscale/swscale.h"

// Log
#ifdef ANDROID
#include <jni.h>
#include <android/log.h>
#define LOGE(format, ...)  __android_log_print(ANDROID_LOG_ERROR, "DJI", format, ##__VA_ARGS__)
#define LOGD(format, ...)  __android_log_print(ANDROID_LOG_DEBUG, "DJI", format, ##__VA_ARGS__)
#else
#define LOGE(format, ...)  printf("DJI" format "\n", ##__VA_ARGS__)
#define LOGD(format, ...)  printf("DJI" format "\n", ##__VA_ARGS__)
#endif

int isFFmpegInitialized;

AVFrame* m_pYUVFrame;
AVCodecContext* m_pCodecCtx;
AVCodec* m_pAVCodec;
AVCodecParserContext* m_pCodecPaser;

jmethodID dataCallbackMID;

//FIX
struct URLProtocol;

/**
 * Codec information
 */
JNIEXPORT jstring Java_com_dji_videostreamdecodingsample_media_NativeHelper_codecinfotest(JNIEnv *env, jobject obj)
{
	char info[40000] = { 0 };

	av_register_all();

	AVCodec *codec_head = av_codec_next(NULL);

	while(codec_head!=NULL)
	{
		
		sprintf(info, "%s\n",  codec_head->name);
		codec_head=codec_head->next;
	}
	LOGE("%s", info);

	return (*env)->NewStringUTF(env, info);
}

/**
 * Invoke the java callback method
 */
void invokeFrameDataCallback(JNIEnv *env, jobject obj, uint8_t* buf, int size, int frameNum, int isKeyFrame, int width, int height)
{
	jbyte* buff = (jbyte*)buf;
	jbyteArray jarray = (*env)->NewByteArray(env, size);
	(*env)->SetByteArrayRegion(env, jarray, 0, size, buf);
	(*env)->CallVoidMethod(env, obj, dataCallbackMID, jarray, size, frameNum, isKeyFrame!=0, width, height);
}

/**
 * Initialize the ffmpeg and software decoder.
 */
JNIEXPORT jboolean Java_com_dji_videostreamdecodingsample_media_NativeHelper_init(JNIEnv *env, jobject obj)
{
	jclass clazz = (*env)->GetObjectClass(env, obj);
	dataCallbackMID = (*env)->GetMethodID(env, clazz, "onFrameDataRecv", "([BIIZII)V");
	if (isFFmpegInitialized == 0)
	{
		avcodec_register_all();
		av_register_all();
		isFFmpegInitialized = 1;
	}
	m_pAVCodec = avcodec_find_decoder(AV_CODEC_ID_H264);
	m_pCodecCtx = avcodec_alloc_context3(m_pAVCodec);
	m_pCodecPaser = av_parser_init(AV_CODEC_ID_H264);
	if (m_pAVCodec == NULL || m_pCodecCtx == NULL)
	{
		LOGD("m_pAVCodec == NULL||m_pCodecCtx == NULL");
		return 0;
	}

	if (m_pAVCodec->capabilities & CODEC_CAP_TRUNCATED)
		m_pCodecCtx->flags |= CODEC_FLAG_TRUNCATED;

	m_pCodecCtx->thread_count = 4;
	m_pCodecCtx->thread_type = FF_THREAD_FRAME;

	if (avcodec_open2(m_pCodecCtx, m_pAVCodec,NULL) < 0) 
	{
		m_pAVCodec = NULL;
		return 0;
	}

	m_pYUVFrame = av_frame_alloc();

	if (m_pYUVFrame == NULL) 
	{
		LOGD(" CDecoder avcodec_alloc_frame() == NULL ");
		return 0;
	}
	LOGD("CDecoder::prepare()2");
	return 1;
}

/**
 * Framing the raw data from camera using the av parser.
 */
int parse(JNIEnv *env, jobject obj, uint8_t* pBuff, int videosize, uint64_t pts) 
{
	int paserLength_In = videosize;
	int paserLen;
	int decode_data_length;
	int got_picture = 0;
	uint8_t *pFrameBuff = (uint8_t*) pBuff;
	while (paserLength_In > 0) 
	{
		AVPacket packet;
		av_init_packet(&packet);
		if (m_pCodecPaser == NULL) {
			LOGE("m_pCodecPaser == NULL");
			Java_com_dji_videostreamdecodingsample_media_NativeHelper_init(env, obj);
		}
		if (m_pCodecCtx == NULL) {
			LOGE("m_pCodecCtx == NULL");
			Java_com_dji_videostreamdecodingsample_media_NativeHelper_init(env, obj);
		}
 		paserLen = av_parser_parse2(m_pCodecPaser, m_pCodecCtx, &packet.data, &packet.size, pFrameBuff,
				paserLength_In, AV_NOPTS_VALUE, AV_NOPTS_VALUE, AV_NOPTS_VALUE);

		//LOGD("paserLen = %d",paserLen);
		paserLength_In -= paserLen;
		pFrameBuff += paserLen;

		if (packet.size > 0) 
		{

			// LOGD(
			// 	"packet size=%d, pts=%lld, width_in_pixel=%d, height_in_pixel=%d, key_frame=%d, frame_has_sps=%d, frame_has_pps=%d, frame_num=%d",
			// 	packet.size,
			// 	pts,
			// 	m_pCodecPaser->width_in_pixel,
			// 	m_pCodecPaser->height_in_pixel,
			// 	m_pCodecPaser->key_frame,
			// 	m_pCodecPaser->frame_has_sps,
			// 	m_pCodecPaser->frame_has_pps,
			// 	m_pCodecPaser->frame_num
			// 	);
			invokeFrameDataCallback(
				env, 
				obj, 
				packet.data, 
				packet.size, 
				m_pCodecPaser->frame_num, 
				m_pCodecPaser->key_frame, 
				m_pCodecPaser->width_in_pixel, 
				m_pCodecPaser->height_in_pixel
				);
			
		}
		av_free_packet(&packet);
	}

	return 0;
}

uint8_t audbuffer2[] = {0x00,0x00,0x00,0x01,0x09,0x10};
uint8_t audsize2 = 6;
uint8_t fillerbuffer2[] = {0x00,0x00,0x00,0x01,0x0C,0x00,0x00,0x00,0x01,0x09,0x10};
uint8_t fillersize2 = 11;
uint8_t audaudbuffer2[] = {0x00,0x00,0x00,0x01,0x09,0x10, 0x00,0x00,0x00,0x01,0x09,0x10};
uint8_t audaudsize2 = 12;
/**
 * Framing the raw data from camera.
 */
JNIEXPORT jboolean Java_com_dji_videostreamdecodingsample_media_NativeHelper_parse(JNIEnv *env, jobject obj, jbyteArray pBuff, int size)
{
	jbyte* jBuff = (jbyte*)((*env)->GetByteArrayElements(env, pBuff, 0));
	uint8_t* buff = (uint8_t*) jBuff;
	uint64_t pts = 0;
	jbyte* jBuff2;
	
	// LOGD("pts=%llu", pts);

    // Removing the aud bytes.
	if(size >= fillersize2 && memcmp(fillerbuffer2, buff+size-fillersize2, fillersize2) == 0) 
	{
		LOGD("Remove filler+AUD");
		parse(env, obj, buff, size-fillersize2, pts);
	}
	else if (size >= audaudsize2 && memcmp(audaudbuffer2, buff+size-audaudsize2, audaudsize2) == 0)
	{
		LOGD("Remove AUD+AUD");
		parse(env, obj, buff, size-audaudsize2, pts);
	}
	else if (size >= audsize2 && memcmp(audbuffer2, buff+size-audsize2, audsize2) == 0)
	{
		LOGD("Remove AUD");
		parse(env, obj, buff, size-audsize2, pts);
	}
	else
	{
		// LOGD("Remove Nothing");
		parse(env, obj, buff, size, pts);
	}
	(*env)->ReleaseByteArrayElements(env, pBuff, jBuff, 0);
	
	return 1;
}


/**
 * Release the ffmpeg.
 */
JNIEXPORT jboolean Java_com_dji_videostreamdecodingsample_media_NativeHelper_release(JNIEnv *env, jobject obj)
{
	if (m_pCodecCtx) 
	{
		avcodec_close(m_pCodecCtx);
		m_pCodecCtx = NULL;
	}

	av_free(m_pYUVFrame);
	av_free(m_pCodecCtx);
	av_parser_close(m_pCodecPaser);

	return 1;
}

JNIEXPORT jbyteArray Java_com_dji_videostreamdecodingsample_media_NativeHelper_scaleImage(
        JNIEnv *env,
        jobject  obj,
        jbyteArray source,
        int src_width,
        int src_height,
        int dst_width,
        int dst_height,
        int frame_num
    )
{
    enum AVPixelFormat pix_fmt = AV_PIX_FMT_YUV420P16BE;
    // size for yuv buffer
    const int read_frame_size = src_width * src_height * 3 / 2;
    const int write_frame_size = dst_width * dst_height * 3 / 2;
    const int read_size = read_frame_size * frame_num;
    const int write_size = write_frame_size * frame_num;

    struct SwsContext *img_convert_ctx;
    uint8_t *inbuf[4];
    uint8_t *outbuf[4];

    // setup variables
    int inlinesize[4] = {src_width, src_width/2, src_width/2, 0};
    int outlinesize[4] = {dst_width, dst_width/2, dst_width/2, 0};

    uint8_t *temp;
    // uint8_t in[read_size];
    uint8_t out[write_size];

    temp = (*env) -> GetByteArrayElements(env, source, NULL);

    /*for (int i = 0; i < read_size; i ++)
    {
        in[i] = *(temp + i);
    }*/

    inbuf[0] = malloc(src_width * src_height);
    inbuf[1] = malloc(src_width * src_height >> 2);
    inbuf[2] = malloc(src_width * src_height >> 2);
    inbuf[3] = NULL;

    outbuf[0] = malloc(dst_width * dst_height);
    outbuf[1] = malloc(dst_width * dst_height >> 2);
    outbuf[2] = malloc(dst_width * dst_height >> 2);
    outbuf[3] = NULL;

    // Initialize convert context
    img_convert_ctx = sws_getContext(
            src_width,
            src_height,
            pix_fmt,
            dst_width,
            dst_height,
            pix_fmt,
            SWS_FAST_BILINEAR,
            NULL, NULL, NULL
    );

    if(img_convert_ctx == NULL) {
        fprintf(stderr, "Cannot initialize the conversion context!\n");
        return NULL;
    }

    int i = 0;

    for (i = 0; i < frame_num; i ++)
    {
        long offset = i * src_width * src_height * 3 / 2;
        // printf("input offset: %ld\n", offset);
        memcpy(inbuf[0], temp + offset, src_width * src_height);
        memcpy(inbuf[1], temp + offset + src_width * src_height, src_width * src_height >> 2);
        memcpy(inbuf[2], temp + offset + (src_width * src_height * 5 >> 2), src_width * src_height >> 2);

        // start sws_scale
        sws_scale(
                img_convert_ctx,
                (const uint8_t * const*) inbuf,
                inlinesize,
                0,
                src_height,
                outbuf,
                outlinesize
        );

        long output_offset = i * dst_width * dst_height * 3 / 2;
        // printf("output offset: %ld\n", output_offset);
        memcpy(out + output_offset, outbuf[0], dst_width * dst_height);
        memcpy(out + output_offset + dst_width * dst_height, outbuf[1], dst_width * dst_height >> 2);
        memcpy(out + output_offset + (dst_width * dst_height * 5 >> 2), outbuf[2], dst_width * dst_height >> 2);
    }

    // release the ConvertContext
    sws_freeContext(img_convert_ctx);

    // setup and return the byte array to java
    jbyteArray  result = (*env) -> NewByteArray(env, write_size);

    (*env) -> SetByteArrayRegion(env, result, 0, write_size, (jbyte*) out);
    return result;
}

JNIEXPORT jint Java_com_dji_videostreamdecodingsample_media_NativeHelper_testNative(JNIEnv *env, jobject  obj)
{
    return -1;
}