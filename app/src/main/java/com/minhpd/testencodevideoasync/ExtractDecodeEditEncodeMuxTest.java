/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.minhpd.testencodevideoasync;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;

import com.minhpd.testencodevideoasync.SafeMediaCodecCallback.MediaCodecCallbackException;

/**
 * Test for the integration of MediaMuxer and MediaCodec's encoder.
 *
 * <p>It uses MediaExtractor to get frames from a test stream, decodes them to a surface, uses a
 * shader to edit them, encodes them from the resulting surface, and then uses MediaMuxer to write
 * them into a file.
 *
 * <p>It does not currently check whether the result file is correct, but makes sure that nothing
 * fails along the way.
 *
 * <p>It also tests the way the codec config buffers need to be passed from the MediaCodec to the
 * MediaMuxer.
 */
@TargetApi(18)
public class ExtractDecodeEditEncodeMuxTest {

    private static final String TAG = ExtractDecodeEditEncodeMuxTest.class.getSimpleName();
    private static final boolean VERBOSE = true; // lots of logging

    /** How long to wait for the next buffer to become available. */
    private static final int TIMEOUT_USEC = 10000;

    /** Where to output the test files. */
    private static final File OUTPUT_FILENAME_DIR = Environment.getExternalStorageDirectory();

    // parameters for the video encoder
    private static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int OUTPUT_VIDEO_BIT_RATE = 2000000; // 2Mbps
    private static final int OUTPUT_VIDEO_FRAME_RATE = 15; // 15fps
    private static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 10; // 10 seconds between I-frames
    private static final int OUTPUT_VIDEO_COLOR_FORMAT =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;

    // parameters for the audio encoder
    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
    private static int OUTPUT_AUDIO_CHANNEL_COUNT = 2; // Must match the input stream.
    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
    private static final int OUTPUT_AUDIO_AAC_PROFILE =
            MediaCodecInfo.CodecProfileLevel.AACObjectHE;
    private static int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 44100; // Must match the input stream.

    /**
     * Used for editing the frames.
     *
     * <p>Swaps green and blue channels by storing an RBGA color in an RGBA buffer.
     */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord).rbga;\n" +
                    "}\n";

    /** Whether to copy the video from the test video. */
    private boolean mCopyVideo;
    /** Whether to copy the audio from the test video. */
    private boolean mCopyAudio;
    /** Width of the output frames. */
    private int mWidth = -1;
    /** Height of the output frames. */
    private int mHeight = -1;

    /** The raw resource used as the input file. */
    private int mSourceResId;

    /** The destination file for the encoded output. */
    private String mOutputFile;
    private Context context;

    public void setContext(Context context) {
        this.context = context;
    }

//    public void testExtractDecodeEditEncodeMuxQCIF() throws Throwable {
//        setSize(176, 144);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//
//    public void testExtractDecodeEditEncodeMuxQVGA() throws Throwable {
//        setSize(320, 240);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//
//    public void testExtractDecodeEditEncodeMux720p() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//
//    public void testExtractDecodeEditEncodeMuxAudio() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyAudio();
//        TestWrapper.runTest(this);
//    }

    public void testExtractDecodeEditEncodeMuxAudioVideo() throws Throwable {
        setSize(1280, 720);
        setSource(R.raw.vid_20181118_220522);
        setCopyAudio();
        setCopyVideo();
        TestWrapper.runTest(this);
    }

    /** Wraps testExtractDecodeEditEncodeMux() */
    private static class TestWrapper implements Runnable {
        private Throwable mThrowable;
        private ExtractDecodeEditEncodeMuxTest mTest;

        private TestWrapper(ExtractDecodeEditEncodeMuxTest test) {
            mTest = test;
        }

        @Override
        public void run() {
            try {
                mTest.extractDecodeEditEncodeMux();
            } catch (Throwable th) {
                mThrowable = th;
            }
        }

        /**
         * Entry point.
         */
        public static void runTest(ExtractDecodeEditEncodeMuxTest test) throws Throwable {
            test.setOutputFile();
            TestWrapper wrapper = new TestWrapper(test);
            Thread th = new Thread(wrapper, "codec test");
            th.start();
            th.join();
            if (wrapper.mThrowable != null) {
                throw wrapper.mThrowable;
            }
        }
    }

    /**
     * Sets the test to copy the video stream.
     */
    private void setCopyVideo() {
        mCopyVideo = true;
    }

    /**
     * Sets the test to copy the video stream.
     */
    private void setCopyAudio() {
        mCopyAudio = true;
    }

    /**
     * Sets the desired frame size.
     */
    private void setSize(int width, int height) {
        if ((width % 16) != 0 || (height % 16) != 0) {
            Log.w(TAG, "WARNING: width or height not multiple of 16");
        }
        mWidth = width;
        mHeight = height;
    }

    /**
     * Sets the raw resource used as the source video.
     */
    private void setSource(int resId) {
        mSourceResId = resId;
    }

    /**
     * Sets the name of the output file based on the other parameters.
     *
     * <p>Must be called after {@link #setSize(int, int)} and {@link #setSource(int)}.
     */
    private void setOutputFile() {
        StringBuilder sb = new StringBuilder();
        sb.append(OUTPUT_FILENAME_DIR.getAbsolutePath());
        sb.append("/cts-media-");
        sb.append(getClass().getSimpleName());
        Log.d(TAG, "should have called setSource() first"/*, mSourceResId != -1*/);
        sb.append('-');
        sb.append(mSourceResId);
        if (mCopyVideo) {
            Log.d(TAG, "should have called setSize() first"/*, mWidth != -1*/);
            Log.d(TAG, "should have called setSize() first"/*, mHeight != -1*/);
            sb.append('-');
            sb.append("video");
            sb.append('-');
            sb.append(mWidth);
            sb.append('x');
            sb.append(mHeight);
        }
        if (mCopyAudio) {
            sb.append('-');
            sb.append("audio");
        }
        sb.append(".mp4");
        mOutputFile = sb.toString();
    }

    private MediaExtractor mVideoExtractor = null;
    private MediaExtractor mAudioExtractor = null;
    private InputSurface mInputSurface = null;
    private OutputSurface mOutputSurface = null;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private MediaCodec mVideoEncoder = null;
    private MediaCodec mAudioEncoder = null;
    private MediaMuxer mMuxer = null;

    /**
     * Tests encoding and subsequently decoding video from frames generated into a buffer.
     * <p>
     * We encode several frames of a video test pattern using MediaCodec, then decode the output
     * with MediaCodec and do some simple checks.
     */
    private void extractDecodeEditEncodeMux() throws Exception {
        long startTime = System.currentTimeMillis();
        // Exception that may be thrown during release.
        Exception exception = null;

        mDecoderOutputVideoFormat = null;
        mDecoderOutputAudioFormat = null;
        mEncoderOutputVideoFormat = null;
        mEncoderOutputAudioFormat = null;

        mOutputVideoTrack = -1;
        mOutputAudioTrack = -1;
        mVideoExtractorDone = false;
        mVideoDecoderDone = false;
        mVideoEncoderDone = false;
        mAudioExtractorDone = false;
        mAudioDecoderDone = false;
        mAudioEncoderDone = false;
        mIsCrashed = false;
        mPendingAudioDecoderOutputBufferIndices = new LinkedList<Integer>();
        mPendingAudioDecoderOutputBufferInfos = new LinkedList<MediaCodec.BufferInfo>();
        mPendingAudioEncoderInputBufferIndices = new LinkedList<Integer>();
        mPendingVideoEncoderOutputBufferIndices = new LinkedList<Integer>();
        mPendingVideoEncoderOutputBufferInfos = new LinkedList<MediaCodec.BufferInfo>();
        mPendingAudioEncoderOutputBufferIndices = new LinkedList<Integer>();
        mPendingAudioEncoderOutputBufferInfos = new LinkedList<MediaCodec.BufferInfo>();

        mMuxing = false;
        mVideoExtractedFrameCount = 0;
        mVideoDecodedFrameCount = 0;
        mVideoEncodedFrameCount = 0;
        mAudioExtractedFrameCount = 0;
        mAudioDecodedFrameCount = 0;
        mAudioEncodedFrameCount = 0;

        MediaCodecInfo videoCodecInfo = selectCodec(OUTPUT_VIDEO_MIME_TYPE);
        if (videoCodecInfo == null) {
            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_VIDEO_MIME_TYPE);
            return;
        }
        if (VERBOSE) Log.d(TAG, "video found codec: " + videoCodecInfo.getName());

        MediaCodecInfo audioCodecInfo = selectCodec(OUTPUT_AUDIO_MIME_TYPE);
        if (audioCodecInfo == null) {
            // Don't fail CTS if they don't have an AAC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE);
            return;
        }
        if (VERBOSE) Log.d(TAG, "audio found codec: " + audioCodecInfo.getName());

        try {
            // Creates a muxer but do not start or add tracks just yet.
            mMuxer = createMuxer();

            if (mCopyVideo) {
                mVideoExtractor = createExtractor();
                int videoInputTrack = getAndSelectVideoTrackIndex(mVideoExtractor);
                Log.d(TAG, "missing video track in test video"/*, videoInputTrack != -1*/);
                MediaFormat inputFormat = mVideoExtractor.getTrackFormat(videoInputTrack);

                // We avoid the device-specific limitations on width and height by using values
                // that are multiples of 16, which all tested devices seem to be able to handle.
                MediaFormat outputVideoFormat =
                        MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME_TYPE, mWidth, mHeight);

                // Set some properties. Failing to specify some of these can cause the MediaCodec
                // configure() call to throw an unhelpful exception.
                outputVideoFormat.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT, OUTPUT_VIDEO_COLOR_FORMAT);
                outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE);
                outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE);
                outputVideoFormat.setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL);
                if (VERBOSE) Log.d(TAG, "video format: " + outputVideoFormat);

                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties. Request a Surface to use for input.
                AtomicReference<Surface> inputSurfaceReference = new AtomicReference<Surface>();
                mVideoEncoder = createVideoEncoder(
                        videoCodecInfo, outputVideoFormat, inputSurfaceReference);
                mInputSurface = new InputSurface(inputSurfaceReference.get());
                mInputSurface.makeCurrent();
                // Create a MediaCodec for the decoder, based on the extractor's format.
                mOutputSurface = new OutputSurface();
                mOutputSurface.changeFragmentShader(FRAGMENT_SHADER);
                mVideoDecoder = createVideoDecoder(inputFormat, mOutputSurface.getSurface());
                mInputSurface.releaseEGLContext();
            }

            if (mCopyAudio) {
                mAudioExtractor = createExtractor();
                int audioInputTrack = getAndSelectAudioTrackIndex(mAudioExtractor);
                if (audioInputTrack != -1) {
                    MediaFormat inputFormat = mAudioExtractor.getTrackFormat(audioInputTrack);

                    // Audio sample rate and channel count must match the input stream
                    int audioOutputSampleRate = OUTPUT_AUDIO_SAMPLE_RATE_HZ;
                    int audioOutputChannelCount = OUTPUT_AUDIO_CHANNEL_COUNT;

                    if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        audioOutputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        audioOutputChannelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }

                    MediaFormat outputAudioFormat =
                            MediaFormat.createAudioFormat(OUTPUT_AUDIO_MIME_TYPE, audioOutputSampleRate,
                                    audioOutputChannelCount);
                    outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
                    outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);

                    // Create a MediaCodec for the desired codec, then configure it as an encoder with
                    // our desired properties. Request a Surface to use for input.
                    mAudioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat);
                    // Create a MediaCodec for the decoder, based on the extractor's format.
                    mAudioDecoder = createAudioDecoder(inputFormat);
                } else {
                    Log.d(TAG, "missing audio track in test video");
                    mCopyAudio = false;
                }
            }

            awaitEncode();
        } finally {
            if (VERBOSE) Log.d(TAG, "releasing extractor, decoder, encoder, and muxer");
            // Try to release everything we acquired, even if one of the releases fails, in which
            // case we save the first exception we got and re-throw at the end (unless something
            // other exception has already been thrown). This guarantees the first exception thrown
            // is reported as the cause of the error, everything is (attempted) to be released, and
            // all other exceptions appear in the logs.
            try {
                if (mVideoExtractor != null) {
                    mVideoExtractor.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mAudioExtractor != null) {
                    mAudioExtractor.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mVideoDecoder != null) {
                    mVideoDecoder.stop();
                    mVideoDecoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mOutputSurface != null) {
                    mOutputSurface.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing outputSurface", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mVideoEncoder != null) {
                    mVideoEncoder.stop();
                    mVideoEncoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mAudioDecoder != null) {
                    mAudioDecoder.stop();
                    mAudioDecoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mAudioEncoder != null) {
                    mAudioEncoder.stop();
                    mAudioEncoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mMuxer != null) {
                    mMuxer.stop();
                    mMuxer.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing muxer", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mInputSurface != null) {
                    mInputSurface.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing inputSurface", e);
                if (exception == null) {
                    exception = e;
                }
            }
            if (mVideoDecoderHandlerThread != null) {
                mVideoDecoderHandlerThread.quitSafely();
            }
            mVideoExtractor = null;
            mAudioExtractor = null;
            mOutputSurface = null;
            mInputSurface = null;
            mVideoDecoder = null;
            mAudioDecoder = null;
            mVideoEncoder = null;
            mAudioEncoder = null;
            mMuxer = null;
            mVideoDecoderHandlerThread = null;
        }
        if (exception != null) {
            throw exception;
        }
        Log.d(TAG, "encode video in: " + (System.currentTimeMillis() - startTime));
    }

    private void doMuxAudio(int maxBufferSize) {
        ByteBuffer audioInputBuffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!mAudioEncoderDone) {
            bufferInfo.size = mAudioExtractor.readSampleData(audioInputBuffer, 0);
            if (bufferInfo.size < 0) {
                bufferInfo.size = 0;
                mAudioEncoderDone = true;
                break;
            }

            bufferInfo.presentationTimeUs = mAudioExtractor.getSampleTime();
            bufferInfo.offset = 0;
            bufferInfo.flags = mAudioExtractor.getSampleFlags();
            mMuxer.writeSampleData(mOutputAudioTrack, audioInputBuffer, bufferInfo);
            mAudioExtractor.advance();
        }
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSourceResId}.
     */
    private MediaExtractor createExtractor() throws IOException {
        MediaExtractor extractor;
        AssetFileDescriptor srcFd = context.getResources().openRawResourceFd(mSourceResId);
        extractor = new MediaExtractor();
        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
                srcFd.getLength());
        return extractor;
    }

    static class CallbackHandler extends Handler {
        CallbackHandler(Looper l) {
            super(l);
        }
        private MediaCodec mCodec;
        private boolean mEncoder;
        private MediaCodec.Callback mCallback;
        private String mMime;
        private boolean mSetDone;
        @Override
        public void handleMessage(Message msg) {
            try {
                mCodec = mEncoder ? MediaCodec.createEncoderByType(mMime) : MediaCodec.createDecoderByType(mMime);
            } catch (IOException ioe) {
            }
            mCodec.setCallback(mCallback);
            synchronized (this) {
                mSetDone = true;
                notifyAll();
            }
        }
        void create(boolean encoder, String mime, MediaCodec.Callback callback) {
            mEncoder = encoder;
            mMime = mime;
            mCallback = callback;
            mSetDone = false;
            sendEmptyMessage(0);
            synchronized (this) {
                while (!mSetDone) {
                    try {
                        wait();
                    } catch (InterruptedException ie) {
                    }
                }
            }
        }
        MediaCodec getCodec() {
            return mCodec;
        }
    }
    private HandlerThread mVideoDecoderHandlerThread;
    private CallbackHandler mVideoDecoderHandler;

    /**
     * Creates a decoder for the given format, which outputs to the given surface.
     *
     * @param inputFormat the format of the stream to decode
     * @param surface into which to decode the frames
     */
    private MediaCodec createVideoDecoder(MediaFormat inputFormat, Surface surface)
            throws IOException, IllegalStateException {
        mVideoDecoderHandlerThread = new HandlerThread("DecoderThread");
        mVideoDecoderHandlerThread.start();
        mVideoDecoderHandler = new CallbackHandler(mVideoDecoderHandlerThread.getLooper());
        MediaCodecCallbackException mediaCodecCallbackException = new MediaCodecCallbackException() {
            @Override
            public void onException(Exception e) {
                handleMediaCodecCallbackException(e);
            }
        };
        SafeMediaCodecCallback callback = new SafeMediaCodecCallback(mediaCodecCallbackException) {
            @Override
            public void onInputBufferAvailableSafe(@NonNull MediaCodec codec, int index) {
                // Extract video from file and feed to decoder.
                // We feed packets regardless of whether the muxer is set up or not.
                // If the muxer isn't set up yet, the encoder output will be queued up,
                // finally blocking the decoder as well.
                ByteBuffer decoderInputBuffer = codec.getInputBuffer(index);
                while (!mVideoExtractorDone) {
                    int size = mVideoExtractor.readSampleData(decoderInputBuffer, 0);
                    long presentationTime = mVideoExtractor.getSampleTime();
                    if (VERBOSE) {
                        Log.d(TAG, "video extractor: returned buffer of size " + size);
                        Log.d(TAG, "video extractor: returned buffer for time " + presentationTime);
                    }
                    if (size >= 0) {
                        codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                presentationTime,
                                mVideoExtractor.getSampleFlags());
                    }
                    mVideoExtractorDone = !mVideoExtractor.advance();
                    if (mVideoExtractorDone) {
                        if (VERBOSE) Log.d(TAG, "video extractor: EOS");
                        codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    mVideoExtractedFrameCount++;
                    logState();
                    if (size >= 0)
                        break;
                }
            }

            @Override
            public void onOutputBufferAvailableSafe(@NonNull MediaCodec codec, int index,
                                                    @NonNull MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: returned output buffer: " + index);
                    Log.d(TAG, "video decoder: returned buffer of size " + info.size);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (VERBOSE) Log.d(TAG, "video decoder: codec config buffer");
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: returned buffer for time "
                            + info.presentationTimeUs);
                }
                boolean render = info.size != 0;
                codec.releaseOutputBuffer(index, render);
                if (render) {
                    mInputSurface.makeCurrent();
                    if (VERBOSE) Log.d(TAG, "output surface: await new image");
                    mOutputSurface.awaitNewImage();
                    // Edit the frame and send it to the encoder.
                    if (VERBOSE) Log.d(TAG, "output surface: draw image");
                    mOutputSurface.drawImage();
                    mInputSurface.setPresentationTime(
                            info.presentationTimeUs * 1000);
                    if (VERBOSE) Log.d(TAG, "input surface: swap buffers");
                    mInputSurface.swapBuffers();
                    if (VERBOSE) Log.d(TAG, "video encoder: notified of new frame");
                    mInputSurface.releaseEGLContext();
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (VERBOSE) Log.d(TAG, "video decoder: EOS");
                    mVideoDecoderDone = true;
                    mVideoEncoder.signalEndOfInputStream();
                }
                mVideoDecodedFrameCount++;
                logState();
            }

            @Override
            public void onOutputFormatChangedSafe(@NonNull MediaCodec codec,
                                                  @NonNull MediaFormat format) {
                mDecoderOutputVideoFormat = codec.getOutputFormat();
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: output format changed: "
                            + mDecoderOutputVideoFormat);
                }
            }
        };
        // Create the decoder on a different thread, in order to have the callbacks there.
        // This makes sure that the blocking waiting and rendering in onOutputBufferAvailable
        // won't block other callbacks (e.g. blocking encoder output callbacks), which
        // would otherwise lead to the transcoding pipeline to lock up.

        // Since API 23, we could just do setCallback(callback, mVideoDecoderHandler) instead
        // of using a custom Handler and passing a message to create the MediaCodec there.

        // When the callbacks are received on a different thread, the updating of the variables
        // that are used for state logging (mVideoExtractedFrameCount, mVideoDecodedFrameCount,
        // mVideoExtractorDone and mVideoDecoderDone) should ideally be synchronized properly
        // against accesses from other threads, but that is left out for brevity since it's
        // not essential to the actual transcoding.
        mVideoDecoderHandler.create(false, getMimeTypeFor(inputFormat), callback);
        MediaCodec decoder = mVideoDecoderHandler.getCodec();
        decoder.configure(inputFormat, surface, null, 0);
        decoder.start();
        return decoder;
    }

    synchronized void handleMediaCodecCallbackException(Exception e) {
        if (mCopyVideo) mVideoEncoderDone = true;
        if (mCopyAudio) mAudioEncoderDone = true;
        mIsCrashed = true;
        Log.d(TAG, "handleMediaCodecCallbackException");
        notifyAll();
    }

    /**
     * Creates an encoder for the given format using the specified codec, taking input from a
     * surface.
     *
     * <p>The surface to use as input is stored in the given reference.
     *
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     * @param surfaceReference to store the surface to use as input
     */
    private MediaCodec createVideoEncoder(
            MediaCodecInfo codecInfo,
            MediaFormat format,
            AtomicReference<Surface> surfaceReference) throws IOException {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        MediaCodecCallbackException mediaCodecCallbackException = new MediaCodecCallbackException() {
            @Override
            public void onException(Exception e) {
                handleMediaCodecCallbackException(e);
            }
        };
        SafeMediaCodecCallback callback = new SafeMediaCodecCallback(mediaCodecCallbackException) {
            @Override
            public void onInputBufferAvailableSafe(@NonNull MediaCodec codec, int index) {

            }

            @Override
            public void onOutputBufferAvailableSafe(@NonNull MediaCodec codec, int index,
                                                    @NonNull MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.d(TAG, "video encoder: returned output buffer: " + index);
                    Log.d(TAG, "video encoder: returned buffer of size " + info.size);
                }
                muxVideo(index, info);
            }

            @Override
            public void onOutputFormatChangedSafe(@NonNull MediaCodec codec,
                                                  @NonNull MediaFormat format) {
                if (VERBOSE) Log.d(TAG, "video encoder: output format changed");
                if (mOutputVideoTrack >= 0) {
                    Log.d(TAG, "video encoder changed its output format again?");
                }
                mEncoderOutputVideoFormat = codec.getOutputFormat();
                setupMuxer();
            }
        };
        encoder.setCallback(callback);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // Must be called before start() is.
        surfaceReference.set(encoder.createInputSurface());
        encoder.start();
        return encoder;
    }

    /**
     * Creates a decoder for the given format.
     *
     * @param inputFormat the format of the stream to decode
     */
    private MediaCodec createAudioDecoder(MediaFormat inputFormat) throws IOException {
        MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
        MediaCodecCallbackException mediaCodecCallbackException = new MediaCodecCallbackException() {
            @Override
            public void onException(Exception e) {
                handleMediaCodecCallbackException(e);
            }
        };
        SafeMediaCodecCallback callback = new SafeMediaCodecCallback(mediaCodecCallbackException) {
            @Override
            public void onInputBufferAvailableSafe(@NonNull MediaCodec codec, int index) {
                ByteBuffer decoderInputBuffer = codec.getInputBuffer(index);
                while (!mAudioExtractorDone) {
                    int size = mAudioExtractor.readSampleData(decoderInputBuffer, 0);
                    long presentationTime = mAudioExtractor.getSampleTime();
                    if (VERBOSE) {
                        Log.d(TAG, "audio extractor: returned buffer of size " + size);
                        Log.d(TAG, "audio extractor: returned buffer for time " + presentationTime);
                    }
                    if (size >= 0) {
                        codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                presentationTime,
                                mAudioExtractor.getSampleFlags());
                    }
                    mAudioExtractorDone = !mAudioExtractor.advance();
                    if (mAudioExtractorDone) {
                        if (VERBOSE) Log.d(TAG, "audio extractor: EOS");
                        codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    mAudioExtractedFrameCount++;
                    logState();
                    if (size >= 0)
                        break;
                }
            }

            @Override
            public void onOutputBufferAvailableSafe(@NonNull MediaCodec codec, int index,
                                                    @NonNull MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned output buffer: " + index);
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned buffer of size " + info.size);
                }
                ByteBuffer decoderOutputBuffer = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (VERBOSE) Log.d(TAG, "audio decoder: codec config buffer");
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned buffer for time "
                            + info.presentationTimeUs);
                }
                mPendingAudioDecoderOutputBufferIndices.add(index);
                mPendingAudioDecoderOutputBufferInfos.add(info);
                mAudioDecodedFrameCount++;
                logState();
                tryEncodeAudio();
            }

            @Override
            public void onOutputFormatChangedSafe(@NonNull MediaCodec codec,
                                                  @NonNull MediaFormat format) {
                mDecoderOutputAudioFormat = codec.getOutputFormat();
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: output format changed: "
                            + mDecoderOutputAudioFormat);
                }
            }
        };
        decoder.setCallback(callback);
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();
        return decoder;
    }

    /**
     * Creates an encoder for the given format using the specified codec.
     *
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     */
    private MediaCodec createAudioEncoder(MediaCodecInfo codecInfo, MediaFormat format) throws IOException {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        MediaCodecCallbackException mediaCodecCallbackException = new MediaCodecCallbackException() {
            @Override
            public void onException(Exception e) {
                handleMediaCodecCallbackException(e);
            }
        };
        SafeMediaCodecCallback callback = new SafeMediaCodecCallback(mediaCodecCallbackException) {
            @Override
            public void onInputBufferAvailableSafe(@NonNull MediaCodec codec, int index) {
                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned input buffer: " + index);
                }
                mPendingAudioEncoderInputBufferIndices.add(index);
                tryEncodeAudio();
            }

            @Override
            public void onOutputBufferAvailableSafe(@NonNull MediaCodec codec, int index,
                                                    @NonNull MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned output buffer: " + index);
                    Log.d(TAG, "audio encoder: returned buffer of size " + info.size);
                }
                muxAudio(index, info);
            }

            @Override
            public void onOutputFormatChangedSafe(@NonNull MediaCodec codec,
                                                  @NonNull MediaFormat format) {
                if (VERBOSE) Log.d(TAG, "audio encoder: output format changed");
                if (mOutputAudioTrack >= 0) {
                    Log.d(TAG, "audio encoder changed its output format again?");
                }

                mEncoderOutputAudioFormat = codec.getOutputFormat();
                setupMuxer();
            }
        };
        encoder.setCallback(callback);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }

    // No need to have synchronization around this, since both audio encoder and
    // decoder callbacks are on the same thread.
    private void tryEncodeAudio() {
        if (mPendingAudioEncoderInputBufferIndices.size() == 0 ||
                mPendingAudioDecoderOutputBufferIndices.size() == 0)
            return;
        int decoderIndex = mPendingAudioDecoderOutputBufferIndices.poll();
        int encoderIndex = mPendingAudioEncoderInputBufferIndices.poll();
        MediaCodec.BufferInfo info = mPendingAudioDecoderOutputBufferInfos.poll();

        ByteBuffer encoderInputBuffer = mAudioEncoder.getInputBuffer(encoderIndex);
        int size = info.size;
        long presentationTime = info.presentationTimeUs;
        if (VERBOSE) {
            Log.d(TAG, "audio decoder: processing pending buffer: "
                    + decoderIndex);
        }
        if (VERBOSE) {
            Log.d(TAG, "audio decoder: pending buffer of size " + size);
            Log.d(TAG, "audio decoder: pending buffer for time " + presentationTime);
        }
        if (size >= 0) {
            ByteBuffer decoderOutputBuffer = mAudioDecoder.getOutputBuffer(decoderIndex).duplicate();
            decoderOutputBuffer.position(info.offset);
            decoderOutputBuffer.limit(info.offset + size);
            encoderInputBuffer.position(0);
            encoderInputBuffer.put(decoderOutputBuffer);

            mAudioEncoder.queueInputBuffer(
                    encoderIndex,
                    0,
                    size,
                    presentationTime,
                    info.flags);
        }
        mAudioDecoder.releaseOutputBuffer(decoderIndex, false);
        if ((info.flags
                & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.d(TAG, "audio decoder: EOS");
            mAudioDecoderDone = true;
        }
        logState();
    }

    private void setupMuxer() {
        if (!mMuxing
                && (!mCopyAudio || mEncoderOutputAudioFormat != null)
                && (!mCopyVideo || mEncoderOutputVideoFormat != null)) {
            if (mCopyVideo) {
                Log.d(TAG, "muxer: adding video track.");
                mOutputVideoTrack = mMuxer.addTrack(mEncoderOutputVideoFormat);
            }
            if (mCopyAudio) {
                Log.d(TAG, "muxer: adding audio track.");
                mOutputAudioTrack = mMuxer.addTrack(mEncoderOutputAudioFormat);
            }
            Log.d(TAG, "muxer: starting");
            mMuxer.start();
            mMuxing = true;

            MediaCodec.BufferInfo info;
            while ((info = mPendingVideoEncoderOutputBufferInfos.poll()) != null) {
                int index = mPendingVideoEncoderOutputBufferIndices.poll().intValue();
                muxVideo(index, info);
            }
            while ((info = mPendingAudioEncoderOutputBufferInfos.poll()) != null) {
                int index = mPendingAudioEncoderOutputBufferIndices.poll().intValue();
                muxAudio(index, info);
            }
        }
    }

    private void muxVideo(int index, MediaCodec.BufferInfo info) {
        if (!mMuxing) {
            mPendingVideoEncoderOutputBufferIndices.add(new Integer(index));
            mPendingVideoEncoderOutputBufferInfos.add(info);
            return;
        }
        ByteBuffer encoderOutputBuffer = mVideoEncoder.getOutputBuffer(index);
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (VERBOSE) Log.d(TAG, "video encoder: codec config buffer");
            // Simply ignore codec config buffers.
            mVideoEncoder.releaseOutputBuffer(index, false);
            return;
        }
        if (VERBOSE) {
            Log.d(TAG, "video encoder: returned buffer for time "
                    + info.presentationTimeUs);
        }
        if (info.size != 0) {
            mMuxer.writeSampleData(
                    mOutputVideoTrack, encoderOutputBuffer, info);
        }
        mVideoEncoder.releaseOutputBuffer(index, false);
        mVideoEncodedFrameCount++;
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.d(TAG, "video encoder: EOS");
            synchronized (this) {
                mVideoEncoderDone = true;
                notifyAll();
            }
        }
        logState();
    }
    private void muxAudio(int index, MediaCodec.BufferInfo info) {
        if (!mMuxing) {
            mPendingAudioEncoderOutputBufferIndices.add(new Integer(index));
            mPendingAudioEncoderOutputBufferInfos.add(info);
            return;
        }
        ByteBuffer encoderOutputBuffer = mAudioEncoder.getOutputBuffer(index);
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (VERBOSE) Log.d(TAG, "audio encoder: codec config buffer");
            // Simply ignore codec config buffers.
            mAudioEncoder.releaseOutputBuffer(index, false);
            return;
        }
        if (VERBOSE) {
            Log.d(TAG, "audio encoder: returned buffer for time " + info.presentationTimeUs);
        }
        if (info.size != 0) {
            mMuxer.writeSampleData(
                    mOutputAudioTrack, encoderOutputBuffer, info);
        }
        mAudioEncoder.releaseOutputBuffer(index, false);
        mAudioEncodedFrameCount++;
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.d(TAG, "audio encoder: EOS");
            synchronized (this) {
                mAudioEncoderDone = true;
                notifyAll();
            }
        }
        logState();
    }

    /**
     * Creates a muxer to write the encoded frames.
     *
     * <p>The muxer is not started as it needs to be started only after all streams have been added.
     */
    private MediaMuxer createMuxer() throws IOException {
        return new MediaMuxer(mOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.d(TAG, "format for track " + index + " is "
                        + getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (isVideoFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    private int getAndSelectAudioTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.d(TAG, "format for track " + index + " is "
                        + getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (isAudioFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    // We will get these from the decoders when notified of a format change.
    private MediaFormat mDecoderOutputVideoFormat = null;
    private MediaFormat mDecoderOutputAudioFormat = null;
    // We will get these from the encoders when notified of a format change.
    private MediaFormat mEncoderOutputVideoFormat = null;
    private MediaFormat mEncoderOutputAudioFormat = null;

    // We will determine these once we have the output format.
    private int mOutputVideoTrack = -1;
    private int mOutputAudioTrack = -1;
    // Whether things are done on the video side.
    private boolean mVideoExtractorDone = false;
    private boolean mVideoDecoderDone = false;
    private boolean mVideoEncoderDone = false;
    // Whether things are done on the audio side.
    private boolean mAudioExtractorDone = false;
    private boolean mAudioDecoderDone = false;
    private boolean mAudioEncoderDone = false;
    private boolean mIsCrashed = false;
    private LinkedList<Integer> mPendingAudioDecoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingAudioDecoderOutputBufferInfos;
    private LinkedList<Integer> mPendingAudioEncoderInputBufferIndices;

    private LinkedList<Integer> mPendingVideoEncoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingVideoEncoderOutputBufferInfos;
    private LinkedList<Integer> mPendingAudioEncoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingAudioEncoderOutputBufferInfos;

    private boolean mMuxing = false;

    private int mVideoExtractedFrameCount = 0;
    private int mVideoDecodedFrameCount = 0;
    private int mVideoEncodedFrameCount = 0;

    private int mAudioExtractedFrameCount = 0;
    private int mAudioDecodedFrameCount = 0;
    private int mAudioEncodedFrameCount = 0;

    private void logState() {
        if (VERBOSE) {
            Log.d(TAG, String.format(
                    "loop: "

                            + "V(%b){"
                            + "extracted:%d(done:%b) "
                            + "decoded:%d(done:%b) "
                            + "encoded:%d(done:%b)} "

                            + "A(%b){"
                            + "extracted:%d(done:%b) "
                            + "decoded:%d(done:%b) "
                            + "encoded:%d(done:%b) "

                            + "muxing:%b(V:%d,A:%d)",

                    mCopyVideo,
                    mVideoExtractedFrameCount, mVideoExtractorDone,
                    mVideoDecodedFrameCount, mVideoDecoderDone,
                    mVideoEncodedFrameCount, mVideoEncoderDone,

                    mCopyAudio,
                    mAudioExtractedFrameCount, mAudioExtractorDone,
                    mAudioDecodedFrameCount, mAudioDecoderDone,
                    mAudioEncodedFrameCount, mAudioEncoderDone,

                    mMuxing, mOutputVideoTrack, mOutputAudioTrack));
        }
    }

    private void awaitEncode() {
        synchronized (this) {
            while (!mIsCrashed && ((mCopyVideo && !mVideoEncoderDone) || (mCopyAudio && !mAudioEncoderDone))) {
                try {
                    wait();
                } catch (InterruptedException ie) {
                }
            }
        }

        // Basic sanity checks.
        if (mCopyVideo) {
            Log.d(TAG, "encoded and decoded video frame counts should match" +
                    "mVideoDecodedFrameCount: " + ", mVideoEncodedFrameCount: " + mVideoDecodedFrameCount);
            Log.d(TAG, "decoded frame count should be less than extracted frame count" +
                    "mVideoDecodedFrameCount <= mVideoExtractedFrameCount");
        }
        if (mCopyAudio) {
            Log.d(TAG, "no frame should be pending: " + mPendingAudioDecoderOutputBufferIndices.size());
        }

        if (mIsCrashed) {
            Log.e(TAG, "Crash!!! Something's wrong.");
            throw new IllegalStateException();
        }

        // TODO: Check the generated output file.
    }

    private static boolean isVideoFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("video/");
    }

    private static boolean isAudioFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("audio/");
    }

    private static String getMimeTypeFor(MediaFormat format) {
        return format.getString(MediaFormat.KEY_MIME);
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no match was
     * found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

}

///*
// * Copyright (C) 2013 The Android Open Source Project
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package com.minhpd.testencodevideoasync;
//import android.annotation.TargetApi;
//import android.content.Context;
//import android.content.res.AssetFileDescriptor;
//import android.media.MediaCodec;
//import android.media.MediaCodecInfo;
//import android.media.MediaCodecList;
//import android.media.MediaExtractor;
//import android.media.MediaFormat;
//import android.media.MediaMuxer;
//import android.os.Environment;
//import android.util.Log;
//import android.view.Surface;
//import java.io.File;
//import java.io.IOException;
//import java.nio.ByteBuffer;
//import java.util.concurrent.atomic.AtomicReference;
///**
// * Test for the integration of MediaMuxer and MediaCodec's encoder.
// *
// * <p>It uses MediaExtractor to get frames from a test stream, decodes them to a surface, uses a
// * shader to edit them, encodes them from the resulting surface, and then uses MediaMuxer to write
// * them into a file.
// *
// * <p>It does not currently check whether the result file is correct, but makes sure that nothing
// * fails along the way.
// *
// * <p>It also tests the way the codec config buffers need to be passed from the MediaCodec to the
// * MediaMuxer.
// */
//@TargetApi(18)
//public class ExtractDecodeEditEncodeMuxTest {
//    private static final String TAG = ExtractDecodeEditEncodeMuxTest.class.getSimpleName();
//    private static final boolean VERBOSE = true; // lots of logging
//    /** How long to wait for the next buffer to become available. */
//    private static final int TIMEOUT_USEC = 10000;
//    /** Where to output the test files. */
//    private static final File OUTPUT_FILENAME_DIR = Environment.getExternalStorageDirectory();
//    // parameters for the video encoder
//    private static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
//    private static final int OUTPUT_VIDEO_BIT_RATE = 2000000; // 2Mbps
//    private static final int OUTPUT_VIDEO_FRAME_RATE = 15; // 15fps
//    private static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 10; // 10 seconds between I-frames
//    private static final int OUTPUT_VIDEO_COLOR_FORMAT =
//            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
//    // parameters for the audio encoder
//    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
//    private static final int OUTPUT_AUDIO_CHANNEL_COUNT = 2; // Must match the input stream.
//    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
//    private static final int OUTPUT_AUDIO_AAC_PROFILE =
//            MediaCodecInfo.CodecProfileLevel.AACObjectHE;
//    private static final int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 48000; // Must match the input stream.
//    /**
//     * Used for editing the frames.
//     *
//     * <p>Swaps green and blue channels by storing an RBGA color in an RGBA buffer.
//     */
//    private static final String FRAGMENT_SHADER =
//            "#extension GL_OES_EGL_image_external : require\n" +
//                    "precision mediump float;\n" +
//                    "varying vec2 vTextureCoord;\n" +
//                    "uniform samplerExternalOES sTexture;\n" +
//                    "void main() {\n" +
//                    "  gl_FragColor = texture2D(sTexture, vTextureCoord).rbga;\n" +
//                    "}\n";
//    /** Whether to copy the video from the test video. */
//    private boolean mCopyVideo;
//    /** Whether to copy the audio from the test video. */
//    private boolean mCopyAudio;
//    /** Width of the output frames. */
//    private int mWidth = -1;
//    /** Height of the output frames. */
//    private int mHeight = -1;
//    /** The raw resource used as the input file. */
//    private int mSourceResId;
//    /** The destination file for the encoded output. */
//    private String mOutputFile;
//    private Context context;
//
//    public void setContext(Context context) {
//        this.context = context;
//    }
//
//    public void testExtractDecodeEditEncodeMuxQCIF() throws Throwable {
//        setSize(176, 144);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//    public void testExtractDecodeEditEncodeMuxQVGA() throws Throwable {
//        setSize(320, 240);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//    public void testExtractDecodeEditEncodeMux720p() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//    public void testExtractDecodeEditEncodeMuxAudio() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyAudio();
//        TestWrapper.runTest(this);
//    }
//    public void testExtractDecodeEditEncodeMuxAudioVideo() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.vid_20181118_220522);
//        setCopyAudio();
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//    /** Wraps testExtractDecodeEditEncodeMux() */
//    private static class TestWrapper implements Runnable {
//        private Throwable mThrowable;
//        private ExtractDecodeEditEncodeMuxTest mTest;
//        private TestWrapper(ExtractDecodeEditEncodeMuxTest test) {
//            mTest = test;
//        }
//        @Override
//        public void run() {
//            try {
//                mTest.extractDecodeEditEncodeMux();
//            } catch (Throwable th) {
//                mThrowable = th;
//            }
//        }
//        /**
//         * Entry point.
//         */
//        public static void runTest(ExtractDecodeEditEncodeMuxTest test) throws Throwable {
//            test.setOutputFile();
//            TestWrapper wrapper = new TestWrapper(test);
//            Thread th = new Thread(wrapper, "codec test");
//            th.start();
//            th.join();
//            if (wrapper.mThrowable != null) {
//                throw wrapper.mThrowable;
//            }
//        }
//    }
//    /**
//     * Sets the test to copy the video stream.
//     */
//    private void setCopyVideo() {
//        mCopyVideo = true;
//    }
//    /**
//     * Sets the test to copy the video stream.
//     */
//    private void setCopyAudio() {
//        mCopyAudio = true;
//    }
//    /**
//     * Sets the desired frame size.
//     */
//    private void setSize(int width, int height) {
//        if ((width % 16) != 0 || (height % 16) != 0) {
//            Log.w(TAG, "WARNING: width or height not multiple of 16");
//        }
//        mWidth = width;
//        mHeight = height;
//    }
//    /**
//     * Sets the raw resource used as the source video.
//     */
//    private void setSource(int resId) {
//        mSourceResId = resId;
//    }
//    /**
//     * Sets the name of the output file based on the other parameters.
//     *
//     * <p>Must be called after {@link #setSize(int, int)} and {@link #setSource(int)}.
//     */
//    private void setOutputFile() {
//        StringBuilder sb = new StringBuilder();
//        sb.append(OUTPUT_FILENAME_DIR.getAbsolutePath());
//        sb.append("/cts-media-");
//        sb.append(getClass().getSimpleName());
//        Log.d(TAG, "should have called setSource() first"/*, mSourceResId != -1*/);
//        sb.append('-');
//        sb.append(mSourceResId);
//        if (mCopyVideo) {
//            Log.d(TAG, "should have called setSize() first"/*, mWidth != -1*/);
//            Log.d(TAG, "should have called setSize() first"/*, mHeight != -1*/);
//            sb.append('-');
//            sb.append("video");
//            sb.append('-');
//            sb.append(mWidth);
//            sb.append('x');
//            sb.append(mHeight);
//        }
//        if (mCopyAudio) {
//            sb.append('-');
//            sb.append("audio");
//        }
//        sb.append(".mp4");
//        mOutputFile = sb.toString();
//    }
//    /**
//     * Tests encoding and subsequently decoding video from frames generated into a buffer.
//     * <p>
//     * We encode several frames of a video test pattern using MediaCodec, then decode the output
//     * with MediaCodec and do some simple checks.
//     */
//    private void extractDecodeEditEncodeMux() throws Exception {
//        // Exception that may be thrown during release.
//        Exception exception = null;
//        MediaCodecInfo videoCodecInfo = selectCodec(OUTPUT_VIDEO_MIME_TYPE);
//        if (videoCodecInfo == null) {
//            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
//            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_VIDEO_MIME_TYPE);
//            return;
//        }
//        if (VERBOSE) Log.d(TAG, "video found codec: " + videoCodecInfo.getName());
//        MediaCodecInfo audioCodecInfo = selectCodec(OUTPUT_AUDIO_MIME_TYPE);
//        if (audioCodecInfo == null) {
//            // Don't fail CTS if they don't have an AAC codec (not here, anyway).
//            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE);
//            return;
//        }
//        if (VERBOSE) Log.d(TAG, "audio found codec: " + audioCodecInfo.getName());
//        MediaExtractor videoExtractor = null;
//        MediaExtractor audioExtractor = null;
//        OutputSurface outputSurface = null;
//        MediaCodec videoDecoder = null;
//        MediaCodec audioDecoder = null;
//        MediaCodec videoEncoder = null;
//        MediaCodec audioEncoder = null;
//        MediaMuxer muxer = null;
//        InputSurface inputSurface = null;
//        try {
//            if (mCopyVideo) {
//                videoExtractor = createExtractor();
//                int videoInputTrack = getAndSelectVideoTrackIndex(videoExtractor);
//                Log.d(TAG, "missing video track in test video"/*, videoInputTrack != -1*/);
//                MediaFormat inputFormat = videoExtractor.getTrackFormat(videoInputTrack);
//                // We avoid the device-specific limitations on width and height by using values
//                // that are multiples of 16, which all tested devices seem to be able to handle.
//                MediaFormat outputVideoFormat =
//                        MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME_TYPE, mWidth, mHeight);
//                // Set some properties. Failing to specify some of these can cause the MediaCodec
//                // configure() call to throw an unhelpful exception.
//                outputVideoFormat.setInteger(
//                        MediaFormat.KEY_COLOR_FORMAT, OUTPUT_VIDEO_COLOR_FORMAT);
//                outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE);
//                outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE);
//                outputVideoFormat.setInteger(
//                        MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL);
//                if (VERBOSE) Log.d(TAG, "video format: " + outputVideoFormat);
//                // Create a MediaCodec for the desired codec, then configure it as an encoder with
//                // our desired properties. Request a Surface to use for input.
//                AtomicReference<Surface> inputSurfaceReference = new AtomicReference<Surface>();
//                videoEncoder = createVideoEncoder(
//                        videoCodecInfo, outputVideoFormat, inputSurfaceReference);
//                inputSurface = new InputSurface(inputSurfaceReference.get());
//                inputSurface.makeCurrent();
//                // Create a MediaCodec for the decoder, based on the extractor's format.
//                outputSurface = new OutputSurface();
//                outputSurface.changeFragmentShader(FRAGMENT_SHADER);
//                videoDecoder = createVideoDecoder(inputFormat, outputSurface.getSurface());
//            }
//            if (mCopyAudio) {
//                audioExtractor = createExtractor();
//                int audioInputTrack = getAndSelectAudioTrackIndex(audioExtractor);
//                Log.d(TAG, "missing audio track in test video"/*, audioInputTrack != -1*/);
//                MediaFormat inputFormat = audioExtractor.getTrackFormat(audioInputTrack);
//                MediaFormat outputAudioFormat =
//                        MediaFormat.createAudioFormat(
//                                OUTPUT_AUDIO_MIME_TYPE, OUTPUT_AUDIO_SAMPLE_RATE_HZ,
//                                OUTPUT_AUDIO_CHANNEL_COUNT);
//                outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
//                outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);
//                // Create a MediaCodec for the desired codec, then configure it as an encoder with
//                // our desired properties. Request a Surface to use for input.
//                audioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat);
//                // Create a MediaCodec for the decoder, based on the extractor's format.
//                audioDecoder = createAudioDecoder(inputFormat);
//            }
//            // Creates a muxer but do not start or add tracks just yet.
//            muxer = createMuxer();
//            doExtractDecodeEditEncodeMux(
//                    videoExtractor,
//                    audioExtractor,
//                    videoDecoder,
//                    videoEncoder,
//                    audioDecoder,
//                    audioEncoder,
//                    muxer,
//                    inputSurface,
//                    outputSurface);
//        } finally {
//            if (VERBOSE) Log.d(TAG, "releasing extractor, decoder, encoder, and muxer");
//            // Try to release everything we acquired, even if one of the releases fails, in which
//            // case we save the first exception we got and re-throw at the end (unless something
//            // other exception has already been thrown). This guarantees the first exception thrown
//            // is reported as the cause of the error, everything is (attempted) to be released, and
//            // all other exceptions appear in the logs.
//            try {
//                if (videoExtractor != null) {
//                    videoExtractor.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing videoExtractor", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (audioExtractor != null) {
//                    audioExtractor.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing audioExtractor", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (videoDecoder != null) {
//                    videoDecoder.stop();
//                    videoDecoder.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing videoDecoder", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (outputSurface != null) {
//                    outputSurface.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing outputSurface", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (videoEncoder != null) {
//                    videoEncoder.stop();
//                    videoEncoder.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing videoEncoder", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (audioDecoder != null) {
//                    audioDecoder.stop();
//                    audioDecoder.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing audioDecoder", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (audioEncoder != null) {
//                    audioEncoder.stop();
//                    audioEncoder.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing audioEncoder", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (muxer != null) {
//                    muxer.stop();
//                    muxer.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing muxer", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//            try {
//                if (inputSurface != null) {
//                    inputSurface.release();
//                }
//            } catch(Exception e) {
//                Log.e(TAG, "error while releasing inputSurface", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
//        }
//        if (exception != null) {
//            throw exception;
//        }
//    }
//    /**
//     * Creates an extractor that reads its frames from {@link #mSourceResId}.
//     */
//    private MediaExtractor createExtractor() throws IOException {
//        MediaExtractor extractor;
//        AssetFileDescriptor srcFd = context.getResources().openRawResourceFd(mSourceResId);
//        extractor = new MediaExtractor();
//        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
//                srcFd.getLength());
//        return extractor;
//    }
//    /**
//     * Creates a decoder for the given format, which outputs to the given surface.
//     *
//     * @param inputFormat the format of the stream to decode
//     * @param surface into which to decode the frames
//     */
//    private MediaCodec createVideoDecoder(MediaFormat inputFormat, Surface surface) throws IOException {
//        MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
//        decoder.configure(inputFormat, surface, null, 0);
//        decoder.start();
//        return decoder;
//    }
//    /**
//     * Creates an encoder for the given format using the specified codec, taking input from a
//     * surface.
//     *
//     * <p>The surface to use as input is stored in the given reference.
//     *
//     * @param codecInfo of the codec to use
//     * @param format of the stream to be produced
//     * @param surfaceReference to store the surface to use as input
//     */
//    private MediaCodec createVideoEncoder(
//            MediaCodecInfo codecInfo,
//            MediaFormat format,
//            AtomicReference<Surface> surfaceReference) throws IOException {
//        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
//        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//        // Must be called before start() is.
//        surfaceReference.set(encoder.createInputSurface());
//        encoder.start();
//        return encoder;
//    }
//    /**
//     * Creates a decoder for the given format.
//     *
//     * @param inputFormat the format of the stream to decode
//     */
//    private MediaCodec createAudioDecoder(MediaFormat inputFormat) throws IOException {
//        MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
//        decoder.configure(inputFormat, null, null, 0);
//        decoder.start();
//        return decoder;
//    }
//    /**
//     * Creates an encoder for the given format using the specified codec.
//     *
//     * @param codecInfo of the codec to use
//     * @param format of the stream to be produced
//     */
//    private MediaCodec createAudioEncoder(MediaCodecInfo codecInfo, MediaFormat format) throws IOException {
//        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
//        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//        encoder.start();
//        return encoder;
//    }
//    /**
//     * Creates a muxer to write the encoded frames.
//     *
//     * <p>The muxer is not started as it needs to be started only after all streams have been added.
//     */
//    private MediaMuxer createMuxer() throws IOException {
//        return new MediaMuxer(mOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
//    }
//    private int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
//        for (int index = 0; index < extractor.getTrackCount(); ++index) {
//            if (VERBOSE) {
//                Log.d(TAG, "format for track " + index + " is "
//                        + getMimeTypeFor(extractor.getTrackFormat(index)));
//            }
//            if (isVideoFormat(extractor.getTrackFormat(index))) {
//                extractor.selectTrack(index);
//                return index;
//            }
//        }
//        return -1;
//    }
//    private int getAndSelectAudioTrackIndex(MediaExtractor extractor) {
//        for (int index = 0; index < extractor.getTrackCount(); ++index) {
//            if (VERBOSE) {
//                Log.d(TAG, "format for track " + index + " is "
//                        + getMimeTypeFor(extractor.getTrackFormat(index)));
//            }
//            if (isAudioFormat(extractor.getTrackFormat(index))) {
//                extractor.selectTrack(index);
//                return index;
//            }
//        }
//        return -1;
//    }
//    /**
//     * Does the actual work for extracting, decoding, encoding and muxing.
//     */
//    private void doExtractDecodeEditEncodeMux(
//            MediaExtractor videoExtractor,
//            MediaExtractor audioExtractor,
//            MediaCodec videoDecoder,
//            MediaCodec videoEncoder,
//            MediaCodec audioDecoder,
//            MediaCodec audioEncoder,
//            MediaMuxer muxer,
//            InputSurface inputSurface,
//            OutputSurface outputSurface) {
//        ByteBuffer[] videoDecoderInputBuffers = null;
//        ByteBuffer[] videoDecoderOutputBuffers = null;
//        ByteBuffer[] videoEncoderOutputBuffers = null;
//        MediaCodec.BufferInfo videoDecoderOutputBufferInfo = null;
//        MediaCodec.BufferInfo videoEncoderOutputBufferInfo = null;
//        if (mCopyVideo) {
//            videoDecoderInputBuffers = videoDecoder.getInputBuffers();
//            videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
//            videoEncoderOutputBuffers = videoEncoder.getOutputBuffers();
//            videoDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
//            videoEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
//        }
//        ByteBuffer[] audioDecoderInputBuffers = null;
//        ByteBuffer[] audioDecoderOutputBuffers = null;
//        ByteBuffer[] audioEncoderInputBuffers = null;
//        ByteBuffer[] audioEncoderOutputBuffers = null;
//        MediaCodec.BufferInfo audioDecoderOutputBufferInfo = null;
//        MediaCodec.BufferInfo audioEncoderOutputBufferInfo = null;
//        if (mCopyAudio) {
//            audioDecoderInputBuffers = audioDecoder.getInputBuffers();
//            audioDecoderOutputBuffers =  audioDecoder.getOutputBuffers();
//            audioEncoderInputBuffers = audioEncoder.getInputBuffers();
//            audioEncoderOutputBuffers = audioEncoder.getOutputBuffers();
//            audioDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
//            audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
//        }
//        // We will get these from the decoders when notified of a format change.
//        MediaFormat decoderOutputVideoFormat = null;
//        MediaFormat decoderOutputAudioFormat = null;
//        // We will get these from the encoders when notified of a format change.
//        MediaFormat encoderOutputVideoFormat = null;
//        MediaFormat encoderOutputAudioFormat = null;
//        // We will determine these once we have the output format.
//        int outputVideoTrack = -1;
//        int outputAudioTrack = -1;
//        // Whether things are done on the video side.
//        boolean videoExtractorDone = false;
//        boolean videoDecoderDone = false;
//        boolean videoEncoderDone = false;
//        // Whether things are done on the audio side.
//        boolean audioExtractorDone = false;
//        boolean audioDecoderDone = false;
//        boolean audioEncoderDone = false;
//        // The audio decoder output buffer to process, -1 if none.
//        int pendingAudioDecoderOutputBufferIndex = -1;
//        boolean muxing = false;
//        int videoExtractedFrameCount = 0;
//        int videoDecodedFrameCount = 0;
//        int videoEncodedFrameCount = 0;
//        int audioExtractedFrameCount = 0;
//        int audioDecodedFrameCount = 0;
//        int audioEncodedFrameCount = 0;
//        while ((mCopyVideo && !videoEncoderDone) || (mCopyAudio && !audioEncoderDone)) {
//            if (VERBOSE) {
//                Log.d(TAG, String.format(
//                        "loop: "
//                                + "V(%b){"
//                                + "extracted:%d(done:%b) "
//                                + "decoded:%d(done:%b) "
//                                + "encoded:%d(done:%b)} "
//                                + "A(%b){"
//                                + "extracted:%d(done:%b) "
//                                + "decoded:%d(done:%b) "
//                                + "encoded:%d(done:%b) "
//                                + "pending:%d} "
//                                + "muxing:%b(V:%d,A:%d)",
//                        mCopyVideo,
//                        videoExtractedFrameCount, videoExtractorDone,
//                        videoDecodedFrameCount, videoDecoderDone,
//                        videoEncodedFrameCount, videoEncoderDone,
//                        mCopyAudio,
//                        audioExtractedFrameCount, audioExtractorDone,
//                        audioDecodedFrameCount, audioDecoderDone,
//                        audioEncodedFrameCount, audioEncoderDone,
//                        pendingAudioDecoderOutputBufferIndex,
//                        muxing, outputVideoTrack, outputAudioTrack));
//            }
//            // Extract video from file and feed to decoder.
//            // Do not extract video if we have determined the output format but we are not yet
//            // ready to mux the frames.
//            while (mCopyVideo && !videoExtractorDone
//                    && (encoderOutputVideoFormat == null || muxing)) {
//                int decoderInputBufferIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_USEC);
//                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no video decoder input buffer");
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "video decoder: returned input buffer: " + decoderInputBufferIndex);
//                }
//                ByteBuffer decoderInputBuffer = videoDecoderInputBuffers[decoderInputBufferIndex];
//                int size = videoExtractor.readSampleData(decoderInputBuffer, 0);
//                long presentationTime = videoExtractor.getSampleTime();
//                if (VERBOSE) {
//                    Log.d(TAG, "video extractor: returned buffer of size " + size);
//                    Log.d(TAG, "video extractor: returned buffer for time " + presentationTime);
//                }
//                if (size >= 0) {
//                    videoDecoder.queueInputBuffer(
//                            decoderInputBufferIndex,
//                            0,
//                            size,
//                            presentationTime,
//                            videoExtractor.getSampleFlags());
//                }
//                videoExtractorDone = !videoExtractor.advance();
//                if (videoExtractorDone) {
//                    if (VERBOSE) Log.d(TAG, "video extractor: EOS");
//                    videoDecoder.queueInputBuffer(
//                            decoderInputBufferIndex,
//                            0,
//                            0,
//                            0,
//                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//                }
//                videoExtractedFrameCount++;
//                // We extracted a frame, let's try something else next.
//                break;
//            }
//            // Extract audio from file and feed to decoder.
//            // Do not extract audio if we have determined the output format but we are not yet
//            // ready to mux the frames.
//            while (mCopyAudio && !audioExtractorDone
//                    && (encoderOutputAudioFormat == null || muxing)) {
//                int decoderInputBufferIndex = audioDecoder.dequeueInputBuffer(TIMEOUT_USEC);
//                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no audio decoder input buffer");
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: returned input buffer: " + decoderInputBufferIndex);
//                }
//                ByteBuffer decoderInputBuffer = audioDecoderInputBuffers[decoderInputBufferIndex];
//                int size = audioExtractor.readSampleData(decoderInputBuffer, 0);
//                long presentationTime = audioExtractor.getSampleTime();
//                if (VERBOSE) {
//                    Log.d(TAG, "audio extractor: returned buffer of size " + size);
//                    Log.d(TAG, "audio extractor: returned buffer for time " + presentationTime);
//                }
//                if (size >= 0) {
//                    audioDecoder.queueInputBuffer(
//                            decoderInputBufferIndex,
//                            0,
//                            size,
//                            presentationTime,
//                            audioExtractor.getSampleFlags());
//                }
//                audioExtractorDone = !audioExtractor.advance();
//                if (audioExtractorDone) {
//                    if (VERBOSE) Log.d(TAG, "audio extractor: EOS");
//                    audioDecoder.queueInputBuffer(
//                            decoderInputBufferIndex,
//                            0,
//                            0,
//                            0,
//                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//                }
//                audioExtractedFrameCount++;
//                // We extracted a frame, let's try something else next.
//                break;
//            }
//            // Poll output frames from the video decoder and feed the encoder.
//            while (mCopyVideo && !videoDecoderDone
//                    && (encoderOutputVideoFormat == null || muxing)) {
//                int decoderOutputBufferIndex =
//                        videoDecoder.dequeueOutputBuffer(
//                                videoDecoderOutputBufferInfo, TIMEOUT_USEC);
//                if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no video decoder output buffer");
//                    break;
//                }
//                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
//                    if (VERBOSE) Log.d(TAG, "video decoder: output buffers changed");
//                    videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
//                    break;
//                }
//                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
//                    decoderOutputVideoFormat = videoDecoder.getOutputFormat();
//                    if (VERBOSE) {
//                        Log.d(TAG, "video decoder: output format changed: "
//                                + decoderOutputVideoFormat);
//                    }
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "video decoder: returned output buffer: "
//                            + decoderOutputBufferIndex);
//                    Log.d(TAG, "video decoder: returned buffer of size "
//                            + videoDecoderOutputBufferInfo.size);
//                }
//                ByteBuffer decoderOutputBuffer =
//                        videoDecoderOutputBuffers[decoderOutputBufferIndex];
//                if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
//                        != 0) {
//                    if (VERBOSE) Log.d(TAG, "video decoder: codec config buffer");
//                    videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "video decoder: returned buffer for time "
//                            + videoDecoderOutputBufferInfo.presentationTimeUs);
//                }
//                boolean render = videoDecoderOutputBufferInfo.size != 0;
//                videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, render);
//                if (render) {
//                    if (VERBOSE) Log.d(TAG, "output surface: await new image");
//                    outputSurface.awaitNewImage();
//                    // Edit the frame and send it to the encoder.
//                    if (VERBOSE) Log.d(TAG, "output surface: draw image");
//                    outputSurface.drawImage();
//                    inputSurface.setPresentationTime(
//                            videoDecoderOutputBufferInfo.presentationTimeUs * 1000);
//                    if (VERBOSE) Log.d(TAG, "input surface: swap buffers");
//                    inputSurface.swapBuffers();
//                    if (VERBOSE) Log.d(TAG, "video encoder: notified of new frame");
//                }
//                if ((videoDecoderOutputBufferInfo.flags
//                        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    if (VERBOSE) Log.d(TAG, "video decoder: EOS");
//                    videoDecoderDone = true;
//                    videoEncoder.signalEndOfInputStream();
//                }
//                videoDecodedFrameCount++;
//                // We extracted a pending frame, let's try something else next.
//                break;
//            }
//            // Poll output frames from the audio decoder.
//            // Do not poll if we already have a pending buffer to feed to the encoder.
//            while (mCopyAudio && !audioDecoderDone && pendingAudioDecoderOutputBufferIndex == -1
//                    && (encoderOutputAudioFormat == null || muxing)) {
//                int decoderOutputBufferIndex =
//                        audioDecoder.dequeueOutputBuffer(
//                                audioDecoderOutputBufferInfo, TIMEOUT_USEC);
//                if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no audio decoder output buffer");
//                    break;
//                }
//                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
//                    if (VERBOSE) Log.d(TAG, "audio decoder: output buffers changed");
//                    audioDecoderOutputBuffers = audioDecoder.getOutputBuffers();
//                    break;
//                }
//                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
//                    decoderOutputAudioFormat = audioDecoder.getOutputFormat();
//                    if (VERBOSE) {
//                        Log.d(TAG, "audio decoder: output format changed: "
//                                + decoderOutputAudioFormat);
//                    }
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: returned output buffer: "
//                            + decoderOutputBufferIndex);
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: returned buffer of size "
//                            + audioDecoderOutputBufferInfo.size);
//                }
//                ByteBuffer decoderOutputBuffer =
//                        audioDecoderOutputBuffers[decoderOutputBufferIndex];
//                if ((audioDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
//                        != 0) {
//                    if (VERBOSE) Log.d(TAG, "audio decoder: codec config buffer");
//                    audioDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: returned buffer for time "
//                            + audioDecoderOutputBufferInfo.presentationTimeUs);
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: output buffer is now pending: "
//                            + pendingAudioDecoderOutputBufferIndex);
//                }
//                pendingAudioDecoderOutputBufferIndex = decoderOutputBufferIndex;
//                audioDecodedFrameCount++;
//                // We extracted a pending frame, let's try something else next.
//                break;
//            }
//            // Feed the pending decoded audio buffer to the audio encoder.
//            while (mCopyAudio && pendingAudioDecoderOutputBufferIndex != -1) {
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: attempting to process pending buffer: "
//                            + pendingAudioDecoderOutputBufferIndex);
//                }
//                int encoderInputBufferIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_USEC);
//                if (encoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no audio encoder input buffer");
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio encoder: returned input buffer: " + encoderInputBufferIndex);
//                }
//                ByteBuffer encoderInputBuffer = audioEncoderInputBuffers[encoderInputBufferIndex];
//                int size = audioDecoderOutputBufferInfo.size;
//                long presentationTime = audioDecoderOutputBufferInfo.presentationTimeUs;
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: processing pending buffer: "
//                            + pendingAudioDecoderOutputBufferIndex);
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio decoder: pending buffer of size " + size);
//                    Log.d(TAG, "audio decoder: pending buffer for time " + presentationTime);
//                }
//                if (size >= 0) {
//                    ByteBuffer decoderOutputBuffer =
//                            audioDecoderOutputBuffers[pendingAudioDecoderOutputBufferIndex]
//                                    .duplicate();
//                    decoderOutputBuffer.position(audioDecoderOutputBufferInfo.offset);
//                    decoderOutputBuffer.limit(audioDecoderOutputBufferInfo.offset + size);
//                    encoderInputBuffer.position(0);
//                    encoderInputBuffer.put(decoderOutputBuffer);
//                    audioEncoder.queueInputBuffer(
//                            encoderInputBufferIndex,
//                            0,
//                            size,
//                            presentationTime,
//                            audioDecoderOutputBufferInfo.flags);
//                }
//                audioDecoder.releaseOutputBuffer(pendingAudioDecoderOutputBufferIndex, false);
//                pendingAudioDecoderOutputBufferIndex = -1;
//                if ((audioDecoderOutputBufferInfo.flags
//                        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    if (VERBOSE) Log.d(TAG, "audio decoder: EOS");
//                    audioDecoderDone = true;
//                }
//                // We enqueued a pending frame, let's try something else next.
//                break;
//            }
//            // Poll frames from the video encoder and send them to the muxer.
//            while (mCopyVideo && !videoEncoderDone
//                    && (encoderOutputVideoFormat == null || muxing)) {
//                int encoderOutputBufferIndex = videoEncoder.dequeueOutputBuffer(
//                        videoEncoderOutputBufferInfo, TIMEOUT_USEC);
//                if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no video encoder output buffer");
//                    break;
//                }
//                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
//                    if (VERBOSE) Log.d(TAG, "video encoder: output buffers changed");
//                    videoEncoderOutputBuffers = videoEncoder.getOutputBuffers();
//                    break;
//                }
//                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
//                    if (VERBOSE) Log.d(TAG, "video encoder: output format changed");
//                    if (outputVideoTrack >= 0) {
//                        Log.d(TAG, "video encoder changed its output format again?");
//                    }
//                    encoderOutputVideoFormat = videoEncoder.getOutputFormat();
//                    break;
//                }
//                Log.d(TAG, "should have added track before processing output"/*, muxing*/);
//                if (VERBOSE) {
//                    Log.d(TAG, "video encoder: returned output buffer: "
//                            + encoderOutputBufferIndex);
//                    Log.d(TAG, "video encoder: returned buffer of size "
//                            + videoEncoderOutputBufferInfo.size);
//                }
//                ByteBuffer encoderOutputBuffer =
//                        videoEncoderOutputBuffers[encoderOutputBufferIndex];
//                if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
//                        != 0) {
//                    if (VERBOSE) Log.d(TAG, "video encoder: codec config buffer");
//                    // Simply ignore codec config buffers.
//                    videoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "video encoder: returned buffer for time "
//                            + videoEncoderOutputBufferInfo.presentationTimeUs);
//                }
//                if (videoEncoderOutputBufferInfo.size != 0) {
//                    muxer.writeSampleData(
//                            outputVideoTrack, encoderOutputBuffer, videoEncoderOutputBufferInfo);
//                }
//                if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
//                        != 0) {
//                    if (VERBOSE) Log.d(TAG, "video encoder: EOS");
//                    videoEncoderDone = true;
//                }
//                videoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
//                videoEncodedFrameCount++;
//                // We enqueued an encoded frame, let's try something else next.
//                break;
//            }
//            // Poll frames from the audio encoder and send them to the muxer.
//            while (mCopyAudio && !audioEncoderDone
//                    && (encoderOutputAudioFormat == null || muxing)) {
//                int encoderOutputBufferIndex = audioEncoder.dequeueOutputBuffer(
//                        audioEncoderOutputBufferInfo, TIMEOUT_USEC);
//                if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    if (VERBOSE) Log.d(TAG, "no audio encoder output buffer");
//                    break;
//                }
//                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
//                    if (VERBOSE) Log.d(TAG, "audio encoder: output buffers changed");
//                    audioEncoderOutputBuffers = audioEncoder.getOutputBuffers();
//                    break;
//                }
//                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
//                    if (VERBOSE) Log.d(TAG, "audio encoder: output format changed");
//                    if (outputAudioTrack >= 0) {
//                        Log.d(TAG, "audio encoder changed its output format again?");
//                    }
//                    encoderOutputAudioFormat = audioEncoder.getOutputFormat();
//                    break;
//                }
//                Log.d(TAG, "should have added track before processing output"/*, muxing*/);
//                if (VERBOSE) {
//                    Log.d(TAG, "audio encoder: returned output buffer: "
//                            + encoderOutputBufferIndex);
//                    Log.d(TAG, "audio encoder: returned buffer of size "
//                            + audioEncoderOutputBufferInfo.size);
//                }
//                ByteBuffer encoderOutputBuffer =
//                        audioEncoderOutputBuffers[encoderOutputBufferIndex];
//                if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
//                        != 0) {
//                    if (VERBOSE) Log.d(TAG, "audio encoder: codec config buffer");
//                    // Simply ignore codec config buffers.
//                    audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
//                    break;
//                }
//                if (VERBOSE) {
//                    Log.d(TAG, "audio encoder: returned buffer for time "
//                            + audioEncoderOutputBufferInfo.presentationTimeUs);
//                }
//                if (audioEncoderOutputBufferInfo.size != 0) {
//                    muxer.writeSampleData(
//                            outputAudioTrack, encoderOutputBuffer, audioEncoderOutputBufferInfo);
//                }
//                if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
//                        != 0) {
//                    if (VERBOSE) Log.d(TAG, "audio encoder: EOS");
//                    audioEncoderDone = true;
//                }
//                audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
//                audioEncodedFrameCount++;
//                // We enqueued an encoded frame, let's try something else next.
//                break;
//            }
//            if (!muxing
//                    && (!mCopyAudio || encoderOutputAudioFormat != null)
//                    && (!mCopyVideo || encoderOutputVideoFormat != null)) {
//                if (mCopyVideo) {
//                    Log.d(TAG, "muxer: adding video track.");
//                    outputVideoTrack = muxer.addTrack(encoderOutputVideoFormat);
//                }
//                if (mCopyAudio) {
//                    Log.d(TAG, "muxer: adding audio track.");
//                    outputAudioTrack = muxer.addTrack(encoderOutputAudioFormat);
//                }
//                Log.d(TAG, "muxer: starting");
//                muxer.start();
//                muxing = true;
//            }
//        }
//        // Basic sanity checks.
//        if (mCopyVideo) {
//            Log.d(TAG, "encoded and decoded video frame counts should match"/*,
//                    videoDecodedFrameCount, videoEncodedFrameCount*/);
//            Log.d(TAG, "decoded frame count should be less than extracted frame count"/*,
//                    videoDecodedFrameCount <= videoExtractedFrameCount*/);
//        }
//        if (mCopyAudio) {
//            Log.d(TAG, "no frame should be pending"/*, -1, pendingAudioDecoderOutputBufferIndex*/);
//        }
//        // TODO: Check the generated output file.
//    }
//    private static boolean isVideoFormat(MediaFormat format) {
//        return getMimeTypeFor(format).startsWith("video/");
//    }
//    private static boolean isAudioFormat(MediaFormat format) {
//        return getMimeTypeFor(format).startsWith("audio/");
//    }
//    private static String getMimeTypeFor(MediaFormat format) {
//        return format.getString(MediaFormat.KEY_MIME);
//    }
//    /**
//     * Returns the first codec capable of encoding the specified MIME type, or null if no match was
//     * found.
//     */
//    private static MediaCodecInfo selectCodec(String mimeType) {
//        int numCodecs = MediaCodecList.getCodecCount();
//        for (int i = 0; i < numCodecs; i++) {
//            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
//            if (!codecInfo.isEncoder()) {
//                continue;
//            }
//            String[] types = codecInfo.getSupportedTypes();
//            for (int j = 0; j < types.length; j++) {
//                if (types[j].equalsIgnoreCase(mimeType)) {
//                    return codecInfo;
//                }
//            }
//        }
//        return null;
//    }
//}