package com.example.li.simplemuxer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import static android.media.AudioFormat.ENCODING_PCM_16BIT;
import static android.media.MediaFormat.KEY_AAC_PROFILE;
import static android.media.MediaFormat.KEY_BIT_RATE;
import static android.media.MediaFormat.KEY_CAPTURE_RATE;
import static android.media.MediaFormat.KEY_CHANNEL_COUNT;
import static android.media.MediaFormat.KEY_MAX_INPUT_SIZE;
import static android.media.MediaFormat.KEY_PCM_ENCODING;
import static android.media.MediaFormat.KEY_SAMPLE_RATE;
import static android.media.MediaFormat.MIMETYPE_AUDIO_AAC;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

public class MainActivity extends AppCompatActivity {

    final String TAG = "simpleMuxer";
    TextView mLog;

    public static final int AUDIO_SAMPLE_RATE = 48000;
    public static final int AUDIO_CHANNELS = 4;
    public static final int AUDIO_BITRATE = 512000;
    public static final int AUDIO_PCM_SIZE = (AUDIO_SAMPLE_RATE / 50) * (16 / 8) * 4;

    public static final int VIDEO_WIDTH = 3840;
    public static final int VIDEO_HEIGHT = 1920;
    public static final int VIDEO_FPS = 30;

    public static final int BUFFER_SIZE = 2 * 1024 * 1024;

    private MediaMuxer mMediaMuxer;
    MediaCodec mAudioCodec;
    private int mAudioTrack;
    private int mVideoTrack;
    private byte[] mByteArray;
    private MediaCodec.BufferInfo mBufferInfo;
    private int mAudioBytes;
    private int mVideoBytes;
    private long mAudioTime;
    private long mVideoTime;
    private boolean mRecording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mLog = (TextView) findViewById(R.id.log);

        log("App started\n");

        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        muxerStart();
        muxerRun();
        muxerStop();
    }

    public void log(final String s) {
        mLog.append(s);
        mLog.append("\n");
        Log.i(TAG, s);
    }

    public void muxerStart() {
        mAudioTrack = mVideoTrack = -1;
        mAudioBytes = mVideoBytes = 0;
        mAudioTime = mVideoTime = 0;
        mRecording = false;
        mByteArray = new byte[BUFFER_SIZE];
        mBufferInfo = new MediaCodec.BufferInfo();

        String outputMp4 = new StringBuilder().append(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)).append("/").append("out.mp4").toString();
        log("Output .mp4: " + outputMp4);

        try {
            // Muxer
            mMediaMuxer = new MediaMuxer(outputMp4, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Video Track
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
            byte[] sps = {0, 0, 0, 1, 103, 66, -128, 10, -38, 0, -16, 3, -58, -108, -126, -125, 2, -125, 104, 80, -102, -128};
            byte[] pps = {0, 0, 0, 1, 104, -50, 6, -30};
            videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            videoFormat.setInteger(KEY_CAPTURE_RATE, VIDEO_FPS);
            mVideoTrack = mMediaMuxer.addTrack(videoFormat);

            // Audio Encoder & Track
            MediaFormat audioFormat = MediaFormat.createAudioFormat(MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            audioFormat.setInteger(KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(KEY_SAMPLE_RATE, AUDIO_SAMPLE_RATE);
            audioFormat.setInteger(KEY_CHANNEL_COUNT, AUDIO_CHANNELS);
            audioFormat.setInteger(KEY_PCM_ENCODING, ENCODING_PCM_16BIT);
            audioFormat.setInteger(KEY_BIT_RATE, AUDIO_BITRATE);
            audioFormat.setInteger(KEY_MAX_INPUT_SIZE, AUDIO_PCM_SIZE);
            mAudioCodec = MediaCodec.createEncoderByType(MIMETYPE_AUDIO_AAC);
            mAudioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void muxerStop() {
        if (mRecording) {
            // The end-of-stream video frame
            mBufferInfo.offset = 0;
            mBufferInfo.size = 0;
            mBufferInfo.presentationTimeUs += 1000 * 1000 / VIDEO_FPS;
            mBufferInfo.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            byte[] eos = {};
            mMediaMuxer.writeSampleData(mVideoTrack, ByteBuffer.wrap(eos), mBufferInfo);

            // The end-of-stream audio frame
            int inputBufIndex = mAudioCodec.dequeueInputBuffer(-1);
            if (inputBufIndex >= 0) {
                ByteBuffer inputBuffer = mAudioCodec.getInputBuffer(inputBufIndex);
                inputBuffer.clear();
                mAudioCodec.queueInputBuffer(inputBufIndex, 0, 0, mAudioTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            // Audio Output
            int outputBufIndex = 0;
            while (outputBufIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                outputBufIndex = mAudioCodec.dequeueOutputBuffer(mBufferInfo, 0);
                if (outputBufIndex >= 0) {
                    ByteBuffer outputBuffer = mAudioCodec.getOutputBuffer(outputBufIndex);
                    outputBuffer.position(mBufferInfo.offset);
                    outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                    mMediaMuxer.writeSampleData(mAudioTrack, outputBuffer, mBufferInfo);
                    mAudioCodec.releaseOutputBuffer(outputBufIndex, false);
                }
            }

            // Stop Muxer
            mMediaMuxer.stop();
        }

        mMediaMuxer.release();
        mMediaMuxer = null;

    }

    public void muxerRun() {
        try {
            String inputAudio = new StringBuilder().append(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)).append("/").append("dump.pcm").toString();
            String inputVideo = new StringBuilder().append(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)).append("/").append("dump.h264").toString();
            log("Input Audio: " + inputAudio);
            log("Input Video: " + inputVideo);
            File fileAudio = new File(inputAudio);
            File fileVideo = new File(inputVideo);
            FileInputStream fisAudio = new FileInputStream(fileAudio);
            FileInputStream fisVideo = new FileInputStream(fileVideo);

            int frame = 0;
            while (true) {
                int len = fisAudio.read(mByteArray, 0, AUDIO_PCM_SIZE);
                if (len == -1)
                    break;
                mAudioBytes += len;
                int inputBufIndex = mAudioCodec.dequeueInputBuffer(-1);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuffer = mAudioCodec.getInputBuffer(inputBufIndex);
                    inputBuffer.put(mByteArray, 0, len);
                    mAudioCodec.queueInputBuffer(inputBufIndex, 0, len, mAudioTime, 0);
                    mAudioTime = 1000000l * (mAudioBytes / AUDIO_CHANNELS) / (16 / 8) / AUDIO_SAMPLE_RATE;
                    log("[" + frame + "]: mAudioTime=" + (mAudioTime / 1000));
                    frame ++;
                }

                int outputBufIndex = 0;
                while (outputBufIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputBufIndex = mAudioCodec.dequeueOutputBuffer(mBufferInfo, 0);
                    if (outputBufIndex >= 0) {
                        ByteBuffer outputBuffer = mAudioCodec.getOutputBuffer(outputBufIndex);
                        outputBuffer.position(mBufferInfo.offset);
                        outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                        if (mRecording) {
                            mMediaMuxer.writeSampleData(mAudioTrack, outputBuffer, mBufferInfo);
                        }
                        mAudioCodec.releaseOutputBuffer(outputBufIndex, false);
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat audioFormat = mAudioCodec.getOutputFormat();
                        mAudioTrack = mMediaMuxer.addTrack(audioFormat);

                        // Start Muxer
                        mMediaMuxer.start();
                        mRecording = true;
                    }
                }
            }

            frame = 0;
            int len = 0;
            int size = 0;
            int start = 0;
            int end = 0;
            int left = 0;
            while (true) {
                left = size - end;
                size = fisVideo.read(mByteArray, left, BUFFER_SIZE - left);
                if (size == -1)
                    break;
                size += left;
                start = end = 0;
                while (true) {
                    int next = findNal(mByteArray, end, size);
                    if (next < 0)
                        break;
                    start = next;
                    next = findNal(mByteArray, start + 3, size);
                    if (next < 0)
                        break;
                    end = next;
                    len = end - start;
                    if (len <= 0)
                        break;
                    log("[" + frame + "]: VideoFrameLen=" + len);
                    frame++;
                    if (len > 30) {
                        mVideoBytes += len;
                        mBufferInfo.offset = 0;
                        mBufferInfo.size = len;
                        mBufferInfo.presentationTimeUs = mVideoTime;
                        mBufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        mMediaMuxer.writeSampleData(mVideoTrack, ByteBuffer.wrap(mByteArray, start, len), mBufferInfo);
                        mVideoTime += 1000 * 1000 / VIDEO_FPS;
                    }
                }
                for(int i=end, j=0; i<size; i++, j++) {
                    mByteArray[j] = mByteArray[i];

                }
            }

            fisAudio.close();
            fisVideo.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int findNal(byte[] array, int start, int end) {
        for (int i=start; i<end - 4; i++) {
            if (array[i] == 0 && array[i+1] == 0 && array[i+2] == 0 && array[i+3] == 1)
                return i;
        }

        return -1;
    }
}
