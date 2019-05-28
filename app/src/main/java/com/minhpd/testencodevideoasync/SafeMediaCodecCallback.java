package com.minhpd.testencodevideoasync;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.support.annotation.NonNull;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

@TargetApi(LOLLIPOP)
public abstract class SafeMediaCodecCallback extends MediaCodec.Callback {

    final MediaCodecCallbackException mediaCodecCallbackException;

    public SafeMediaCodecCallback(MediaCodecCallbackException mediaCodecCallbackException) {
        this.mediaCodecCallbackException = mediaCodecCallbackException;
    }

    @Override
    public final void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int index) {
        try {
            onInputBufferAvailableSafe(mediaCodec, index);
        } catch (Exception exception) {
            handleException(exception);
        }
    }

    @Override
    public final void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int index,
                                              @NonNull MediaCodec.BufferInfo bufferInfo) {
        try {
            onOutputBufferAvailableSafe(mediaCodec, index, bufferInfo);
        } catch (Exception exception) {
            handleException(exception);
        }
    }

    @Override
    public final void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
        handleException(e);
    }

    @Override
    public final void onOutputFormatChanged(@NonNull MediaCodec mediaCodec,
                                            @NonNull MediaFormat mediaFormat) {
        try {
            onOutputFormatChangedSafe(mediaCodec, mediaFormat);
        } catch (Exception exception) {
            handleException(exception);
        }
    }

    private void handleException(Exception e) {
        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            if (e instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException codecExc = (MediaCodec.CodecException) e;

                if (codecExc.isTransient()) {
                    // We'll let transient exceptions go
                    return;
                }
            }
        }

        mediaCodecCallbackException.onException(e);
    }

    public abstract void onInputBufferAvailableSafe(@NonNull MediaCodec codec, int index);

    public abstract void onOutputBufferAvailableSafe(@NonNull MediaCodec codec, int index,
                                                     @NonNull MediaCodec.BufferInfo info);

    public abstract void onOutputFormatChangedSafe(@NonNull MediaCodec codec,
                                                   @NonNull MediaFormat format);

    public interface MediaCodecCallbackException {
        void onException(Exception e);
    }

}
