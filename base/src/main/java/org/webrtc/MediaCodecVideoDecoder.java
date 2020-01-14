package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;


import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Deprecated
public class MediaCodecVideoDecoder
{
    private static final String TAG = "MediaCodecVideoDecoder";
    private static final long MAX_DECODE_TIME_MS = 200L;
    private static final String FORMAT_KEY_STRIDE = "stride";
    private static final String FORMAT_KEY_SLICE_HEIGHT = "slice-height";
    private static final String FORMAT_KEY_CROP_LEFT = "crop-left";
    private static final String FORMAT_KEY_CROP_RIGHT = "crop-right";
    private static final String FORMAT_KEY_CROP_TOP = "crop-top";
    private static final String FORMAT_KEY_CROP_BOTTOM = "crop-bottom";
    private static final int DEQUEUE_INPUT_TIMEOUT = 500000;
    private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
    private static final int MAX_QUEUED_OUTPUTBUFFERS = 3;
    
    private static MediaCodecVideoDecoder runningInstance;
    
    private static MediaCodecVideoDecoderErrorCallback errorCallback;
    private static int codecErrors;
    private static Set<String> hwDecoderDisabledTypes;
 
    private static org.webrtc.EglBase eglBase;
    
    private Thread mediaCodecThread;
    
    private MediaCodec mediaCodec;
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;
    private static final String VP8_MIME_TYPE = "video/x-vnd.on2.vp8";
    private static final String VP9_MIME_TYPE = "video/x-vnd.on2.vp9";
    private static final String H264_MIME_TYPE = "video/avc";
    private static final String H265_MIME_TYPE = "video/hevc";
    private static String[] vp8HwCodecBlacklist;
    private static final String[] supportedVp9HwCodecPrefixes;
    private static String[] vp9HwCodecBlacklist;
    private static String[] h264HwCodecBlacklist;
    private static final String[] supportedH265HwCodecPrefixes;
    private static String[] h265HwCodecBlacklist;
    private static final String supportedQcomH264HighProfileHwCodecPrefix = "OMX.qcom.";
    private static final String supportedExynosH264HighProfileHwCodecPrefix = "OMX.Exynos.";
    private static final String supportedMediaTekH264HighProfileHwCodecPrefix = "OMX.MTK.";
    private static final int COLOR_QCOM_FORMATYVU420PackedSemiPlanar32m4ka = 2141391873;
    private static final int COLOR_QCOM_FORMATYVU420PackedSemiPlanar16m4ka = 2141391874;
    private static final int COLOR_QCOM_FORMATYVU420PackedSemiPlanar64x32Tile2m8ka = 2141391875;
    private static final int COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m = 2141391876;
    private static final List<Integer> supportedColorList;
    private int colorFormat;
    private int width;
    private int height;
    private int stride;
    private int sliceHeight;
    private boolean hasDecodedFirstFrame;
    private final Queue<TimeStamps> decodeStartTimeMs;
    
    private TextureListener textureListener;
    private int droppedFrames;
    
    private Surface surface;
    private final Queue<DecodedOutputBuffer> dequeuedSurfaceOutputBuffers;
    
    public static org.webrtc.VideoDecoderFactory createFactory() {
        return new org.webrtc.DefaultVideoDecoderFactory(new HwDecoderFactory());
    }
    
    private static final String[] supportedVp8HwCodecPrefixes() {
        final ArrayList<String> supportedPrefixes = new ArrayList<String>();
        supportedPrefixes.add("OMX.qcom.");
        supportedPrefixes.add("OMX.Nvidia.");
        supportedPrefixes.add("OMX.Exynos.");
        supportedPrefixes.add("OMX.Intel.");
        if (org.webrtc.PeerConnectionFactory.fieldTrialsFindFullName("WebRTC-MediaTekVP8").equals("Enabled") && Build.VERSION.SDK_INT >= 24) {
            supportedPrefixes.add("OMX.MTK.");
        }
        supportedPrefixes.add("OMX.");
        return supportedPrefixes.toArray(new String[supportedPrefixes.size()]);
    }
    
    private static final String[] supportedH264HwCodecPrefixes() {
        final ArrayList<String> supportedPrefixes = new ArrayList<String>();
        supportedPrefixes.add("OMX.rk.");
        supportedPrefixes.add("OMX.google.");
        supportedPrefixes.add("OMX.qcom.");
        supportedPrefixes.add("OMX.Intel.");
        supportedPrefixes.add("OMX.Exynos.");
        if (org.webrtc.PeerConnectionFactory.fieldTrialsFindFullName("WebRTC-MediaTekH264").equals("Enabled") && Build.VERSION.SDK_INT >= 27) {
            supportedPrefixes.add("OMX.MTK.");
        }
        supportedPrefixes.add("OMX.");
        return supportedPrefixes.toArray(new String[supportedPrefixes.size()]);
    }
    
    public static void setEglContext(final org.webrtc.EglBase.Context eglContext) {
        if (MediaCodecVideoDecoder.eglBase != null) {
            org.webrtc.Logging.w("MediaCodecVideoDecoder", "Egl context already set.");
            MediaCodecVideoDecoder.eglBase.release();
        }
        MediaCodecVideoDecoder.eglBase = org.webrtc.EglBase.create(eglContext);
    }
    
    public static void disposeEglContext() {
        if (MediaCodecVideoDecoder.eglBase != null) {
            MediaCodecVideoDecoder.eglBase.release();
            MediaCodecVideoDecoder.eglBase = null;
        }
    }
    
    static boolean useSurface() {
        return MediaCodecVideoDecoder.eglBase != null;
    }
    
    public static void setErrorCallback(final MediaCodecVideoDecoderErrorCallback errorCallback) {
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Set error callback");
        MediaCodecVideoDecoder.errorCallback = errorCallback;
    }
    
    public static void disableVp8HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoDecoder", "VP8 decoding is disabled by application.");
        MediaCodecVideoDecoder.hwDecoderDisabledTypes.add("video/x-vnd.on2.vp8");
    }
    
    public static void disableVp9HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoDecoder", "VP9 decoding is disabled by application.");
        MediaCodecVideoDecoder.hwDecoderDisabledTypes.add("video/x-vnd.on2.vp9");
    }
    
    public static void disableH264HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoDecoder", "H.264 decoding is disabled by application.");
        MediaCodecVideoDecoder.hwDecoderDisabledTypes.add("video/avc");
    }
    
    public static void disableH265HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoDecoder", "H.265 decoding is disabled by application.");
        MediaCodecVideoDecoder.hwDecoderDisabledTypes.add("video/hevc");
    }
    
    public static boolean isVp8HwSupported() {
        return !MediaCodecVideoDecoder.hwDecoderDisabledTypes.contains("video/x-vnd.on2.vp8") && findDecoder("video/x-vnd.on2.vp8", supportedVp8HwCodecPrefixes()) != null;
    }
    
    public static boolean isVp9HwSupported() {
        return !MediaCodecVideoDecoder.hwDecoderDisabledTypes.contains("video/x-vnd.on2.vp9") && findDecoder("video/x-vnd.on2.vp9", MediaCodecVideoDecoder.supportedVp9HwCodecPrefixes) != null;
    }
    
    public static boolean isH264HwSupported() {
        return !MediaCodecVideoDecoder.hwDecoderDisabledTypes.contains("video/avc")
                && findDecoder("video/avc", supportedH264HwCodecPrefixes()) != null;
    }
    
    public static boolean isH264HighProfileHwSupported() {
        return !MediaCodecVideoDecoder.hwDecoderDisabledTypes.contains("video/avc")
                && ((Build.VERSION.SDK_INT >= 21
                && findDecoder("video/avc", new String[] { "OMX.qcom." }) != null)
                || (Build.VERSION.SDK_INT >= 23 && findDecoder("video/avc", new String[] { "OMX.Exynos." }) != null)
                || (org.webrtc.PeerConnectionFactory.fieldTrialsFindFullName("WebRTC-MediaTekH264").equals("Enabled") && Build.VERSION.SDK_INT >= 27 && findDecoder("video/avc", new String[] { "OMX.MTK." }) != null));
    }
    
    public static void printStackTrace() {
        if (MediaCodecVideoDecoder.runningInstance != null && MediaCodecVideoDecoder.runningInstance.mediaCodecThread != null) {
            final StackTraceElement[] mediaCodecStackTraces = MediaCodecVideoDecoder.runningInstance.mediaCodecThread.getStackTrace();
            if (mediaCodecStackTraces.length > 0) {
                org.webrtc.Logging.d("MediaCodecVideoDecoder", "MediaCodecVideoDecoder stacks trace:");
                for (final StackTraceElement stackTrace : mediaCodecStackTraces) {
                    org.webrtc.Logging.d("MediaCodecVideoDecoder", stackTrace.toString());
                }
            }
        }
    }
    
    private static boolean isBlacklisted(final String codecName, final String mime) {
        String[] blacklist;
        if (mime.equals("video/x-vnd.on2.vp8")) {
            blacklist = MediaCodecVideoDecoder.vp8HwCodecBlacklist;
        }
        else if (mime.equals("video/x-vnd.on2.vp9")) {
            blacklist = MediaCodecVideoDecoder.vp9HwCodecBlacklist;
        }
        else if (mime.equals("video/avc")) {
            blacklist = MediaCodecVideoDecoder.h264HwCodecBlacklist;
        }
        else {
            if (!mime.equals("video/hevc")) {
                return false;
            }
            blacklist = MediaCodecVideoDecoder.h265HwCodecBlacklist;
        }
        for (final String blacklistedCodec : blacklist) {
            if (codecName.startsWith(blacklistedCodec)) {
                return true;
            }
        }
        return false;
    }
    
    
    private static DecoderProperties findDecoder(final String mime, final String[] supportedCodecPrefixes) {
        if (Build.VERSION.SDK_INT < 19) {
            return null;
        }
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Trying to find HW decoder for mime " + mime);
        for (int i = 0; i < MediaCodecList.getCodecCount(); ++i) {
            MediaCodecInfo info = null;
            try {
                info = MediaCodecList.getCodecInfoAt(i);
            }
            catch (IllegalArgumentException e) {
                org.webrtc.Logging.e("MediaCodecVideoDecoder", "Cannot retrieve decoder codec info", e);
            }
            if (info != null) {
                if (!info.isEncoder()) {
                    String name = null;
                    for (final String mimeType : info.getSupportedTypes()) {
                        if (mimeType.equals(mime)) {
                            name = info.getName();
                            break;
                        }
                    }
                    if (name != null) {
                        if (!isBlacklisted(name, mime)) {
                            org.webrtc.Logging.d("MediaCodecVideoDecoder", "Found candidate decoder " + name);
                            boolean supportedCodec = false;
                            for (final String codecPrefix : supportedCodecPrefixes) {
                                if (name.startsWith(codecPrefix)) {
                                    supportedCodec = true;
                                    break;
                                }
                            }
                            if (supportedCodec) {
                                MediaCodecInfo.CodecCapabilities capabilities;
                                try {
                                    capabilities = info.getCapabilitiesForType(mime);
                                }
                                catch (IllegalArgumentException e2) {
                                    org.webrtc.Logging.e("MediaCodecVideoDecoder", "Cannot retrieve decoder capabilities", e2);
                                    continue;
                                }
                                for (final int colorFormat : capabilities.colorFormats) {
                                    org.webrtc.Logging.v("MediaCodecVideoDecoder", "   Color: 0x" + Integer.toHexString(colorFormat));
                                }
                                for (final int supportedColorFormat : MediaCodecVideoDecoder.supportedColorList) {
                                    for (final int codecColorFormat : capabilities.colorFormats) {
                                        if (codecColorFormat == supportedColorFormat) {
                                            org.webrtc.Logging.d("MediaCodecVideoDecoder", "Found target decoder " + name + ". Color: 0x" + Integer.toHexString(codecColorFormat));
                                            return new DecoderProperties(name, codecColorFormat);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "No HW decoder found for mime " + mime);
        return null;
    }
    
    @org.webrtc.CalledByNative
    MediaCodecVideoDecoder() {
        this.decodeStartTimeMs = new ArrayDeque<TimeStamps>();
        this.surface = null;
        this.dequeuedSurfaceOutputBuffers = new ArrayDeque<DecodedOutputBuffer>();
    }
    
    private void checkOnMediaCodecThread() throws IllegalStateException {
        if (this.mediaCodecThread.getId() != Thread.currentThread().getId()) {
            throw new IllegalStateException("MediaCodecVideoDecoder previously operated on " + this.mediaCodecThread + " but is now called on " + Thread.currentThread());
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private boolean initDecode(final VideoCodecType type, final int width, final int height) {
        if (this.mediaCodecThread != null) {
            throw new RuntimeException("initDecode: Forgot to release()?");
        }
        String mime = null;
        String[] supportedCodecPrefixes = null;
        if (type == VideoCodecType.VIDEO_CODEC_VP8) {
            mime = "video/x-vnd.on2.vp8";
            supportedCodecPrefixes = supportedVp8HwCodecPrefixes();
        }
        else if (type == VideoCodecType.VIDEO_CODEC_VP9) {
            mime = "video/x-vnd.on2.vp9";
            supportedCodecPrefixes = MediaCodecVideoDecoder.supportedVp9HwCodecPrefixes;
        }
        else if (type == VideoCodecType.VIDEO_CODEC_H264) {
            mime = "video/avc";
            supportedCodecPrefixes = supportedH264HwCodecPrefixes();
        }
        else {
            if (type != VideoCodecType.VIDEO_CODEC_H265) {
                throw new RuntimeException("initDecode: Non-supported codec " + type);
            }
            mime = "video/hevc";
            supportedCodecPrefixes = MediaCodecVideoDecoder.supportedH265HwCodecPrefixes;
        }
        final DecoderProperties properties = findDecoder(mime, supportedCodecPrefixes);
        if (properties == null) {
            throw new RuntimeException("Cannot find HW decoder for " + type);
        }
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Java initDecode: " + type + " : " + width + " x " + height + ". Color: 0x" + Integer.toHexString(properties.colorFormat) + ". Use Surface: " + useSurface());
        MediaCodecVideoDecoder.runningInstance = this;
        this.mediaCodecThread = Thread.currentThread();
        try {
            this.width = width;
            this.height = height;
            this.stride = width;
            this.sliceHeight = height;
            if (useSurface()) {
                final org.webrtc.SurfaceTextureHelper surfaceTextureHelper = org.webrtc.SurfaceTextureHelper.create("Decoder SurfaceTextureHelper", MediaCodecVideoDecoder.eglBase.getEglBaseContext());
                if (surfaceTextureHelper != null) {
                    (this.textureListener = new TextureListener(surfaceTextureHelper)).setSize(width, height);
                    this.surface = new Surface(surfaceTextureHelper.getSurfaceTexture());
                }
            }
            final MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            if (!useSurface()) {
                format.setInteger("color-format", properties.colorFormat);
            }
            org.webrtc.Logging.d("MediaCodecVideoDecoder", "  Format: " + format);
            this.mediaCodec = MediaCodecVideoEncoder.createByCodecName(properties.codecName);
            if (this.mediaCodec == null) {
                org.webrtc.Logging.e("MediaCodecVideoDecoder", "Can not create media decoder");
                return false;
            }
            this.mediaCodec.configure(format, this.surface, (MediaCrypto)null, 0);
            this.mediaCodec.start();
            this.colorFormat = properties.colorFormat;
            this.outputBuffers = this.mediaCodec.getOutputBuffers();
            this.inputBuffers = this.mediaCodec.getInputBuffers();
            this.decodeStartTimeMs.clear();
            this.hasDecodedFirstFrame = false;
            this.dequeuedSurfaceOutputBuffers.clear();
            this.droppedFrames = 0;
            org.webrtc.Logging.d("MediaCodecVideoDecoder", "Input buffers: " + this.inputBuffers.length + ". Output buffers: " + this.outputBuffers.length);
            return true;
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoDecoder", "initDecode failed", e);
            return false;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private void reset(final int width, final int height) {
        if (this.mediaCodecThread == null || this.mediaCodec == null) {
            throw new RuntimeException("Incorrect reset call for non-initialized decoder.");
        }
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Java reset: " + width + " x " + height);
        this.mediaCodec.flush();
        this.width = width;
        this.height = height;
        if (this.textureListener != null) {
            this.textureListener.setSize(width, height);
        }
        this.decodeStartTimeMs.clear();
        this.dequeuedSurfaceOutputBuffers.clear();
        this.hasDecodedFirstFrame = false;
        this.droppedFrames = 0;
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private void release() {
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Java releaseDecoder. Total number of dropped frames: " + this.droppedFrames);
        this.checkOnMediaCodecThread();
        final CountDownLatch releaseDone = new CountDownLatch(1);
        final Runnable runMediaCodecRelease = new Runnable() {
            @Override
            public void run() {
                try {
                    org.webrtc.Logging.d("MediaCodecVideoDecoder", "Java releaseDecoder on release thread");
                    MediaCodecVideoDecoder.this.mediaCodec.stop();
                    MediaCodecVideoDecoder.this.mediaCodec.release();
                    org.webrtc.Logging.d("MediaCodecVideoDecoder", "Java releaseDecoder on release thread done");
                }
                catch (Exception e) {
                    org.webrtc.Logging.e("MediaCodecVideoDecoder", "Media decoder release failed", e);
                }
                releaseDone.countDown();
            }
        };
        new Thread(runMediaCodecRelease).start();
        if (!org.webrtc.ThreadUtils.awaitUninterruptibly(releaseDone, 5000L)) {
            org.webrtc.Logging.e("MediaCodecVideoDecoder", "Media decoder release timeout");
            ++MediaCodecVideoDecoder.codecErrors;
            if (MediaCodecVideoDecoder.errorCallback != null) {
                org.webrtc.Logging.e("MediaCodecVideoDecoder", "Invoke codec error callback. Errors: " + MediaCodecVideoDecoder.codecErrors);
                MediaCodecVideoDecoder.errorCallback.onMediaCodecVideoDecoderCriticalError(MediaCodecVideoDecoder.codecErrors);
            }
        }
        this.mediaCodec = null;
        this.mediaCodecThread = null;
        MediaCodecVideoDecoder.runningInstance = null;
        if (useSurface()) {
            this.surface.release();
            this.surface = null;
            this.textureListener.release();
        }
        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Java releaseDecoder done");
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private int dequeueInputBuffer() {
        this.checkOnMediaCodecThread();
        try {
            return this.mediaCodec.dequeueInputBuffer(500000L);
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoDecoder", "dequeueIntputBuffer failed", e);
            return -2;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private boolean queueInputBuffer(final int inputBufferIndex, final int size, final long presentationTimeStamUs, final long timeStampMs, final long ntpTimeStamp) {
        this.checkOnMediaCodecThread();
        try {
            this.inputBuffers[inputBufferIndex].position(0);
            this.inputBuffers[inputBufferIndex].limit(size);
            this.decodeStartTimeMs.add(new TimeStamps(SystemClock.elapsedRealtime(), timeStampMs, ntpTimeStamp));
            this.mediaCodec.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeStamUs, 0);
            return true;
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoDecoder", "decode failed", e);
            return false;
        }
    }
    
    
    @org.webrtc.CalledByNativeUnchecked
    private DecodedOutputBuffer dequeueOutputBuffer(final int dequeueTimeoutMs) {
        this.checkOnMediaCodecThread();
        if (this.decodeStartTimeMs.isEmpty()) {
            return null;
        }
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            final int result = this.mediaCodec.dequeueOutputBuffer(info, TimeUnit.MILLISECONDS.toMicros(dequeueTimeoutMs));
            switch (result) {
                case -3: {
                    this.outputBuffers = this.mediaCodec.getOutputBuffers();
                    org.webrtc.Logging.d("MediaCodecVideoDecoder", "Decoder output buffers changed: " + this.outputBuffers.length);
                    if (this.hasDecodedFirstFrame) {
                        throw new RuntimeException("Unexpected output buffer change event.");
                    }
                    continue;
                }
                case -2: {
                    final MediaFormat format = this.mediaCodec.getOutputFormat();
                    org.webrtc.Logging.d("MediaCodecVideoDecoder", "Decoder format changed: " + format.toString());
                    int newWidth;
                    int newHeight;
                    if (format.containsKey("crop-left") && format.containsKey("crop-right") && format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                        newWidth = 1 + format.getInteger("crop-right") - format.getInteger("crop-left");
                        newHeight = 1 + format.getInteger("crop-bottom") - format.getInteger("crop-top");
                    }
                    else {
                        newWidth = format.getInteger("width");
                        newHeight = format.getInteger("height");
                    }
                    if (this.hasDecodedFirstFrame && (newWidth != this.width || newHeight != this.height)) {
                        throw new RuntimeException("Unexpected size change. Configured " + this.width + "*" + this.height + ". New " + newWidth + "*" + newHeight);
                    }
                    this.width = newWidth;
                    this.height = newHeight;
                    if (this.textureListener != null) {
                        this.textureListener.setSize(this.width, this.height);
                    }
                    if (!useSurface() && format.containsKey("color-format")) {
                        this.colorFormat = format.getInteger("color-format");
                        org.webrtc.Logging.d("MediaCodecVideoDecoder", "Color: 0x" + Integer.toHexString(this.colorFormat));
                        if (!MediaCodecVideoDecoder.supportedColorList.contains(this.colorFormat)) {
                            throw new IllegalStateException("Non supported color format: " + this.colorFormat);
                        }
                    }
                    if (format.containsKey("stride")) {
                        this.stride = format.getInteger("stride");
                    }
                    if (format.containsKey("slice-height")) {
                        this.sliceHeight = format.getInteger("slice-height");
                    }
                    org.webrtc.Logging.d("MediaCodecVideoDecoder", "Frame stride and slice height: " + this.stride + " x " + this.sliceHeight);
                    this.stride = Math.max(this.width, this.stride);
                    this.sliceHeight = Math.max(this.height, this.sliceHeight);
                    continue;
                }
                case -1: {
                    return null;
                }
                default: {
                    this.hasDecodedFirstFrame = true;
                    final TimeStamps timeStamps = this.decodeStartTimeMs.remove();
                    long decodeTimeMs = SystemClock.elapsedRealtime() - timeStamps.decodeStartTimeMs;
                    if (decodeTimeMs > 200L) {
                        org.webrtc.Logging.e("MediaCodecVideoDecoder", "Very high decode time: " + decodeTimeMs + "ms" + ". Q size: " + this.decodeStartTimeMs.size() + ". Might be caused by resuming H264 decoding after a pause.");
                        decodeTimeMs = 200L;
                    }
                    return new DecodedOutputBuffer(result, info.offset, info.size, TimeUnit.MICROSECONDS.toMillis(info.presentationTimeUs), timeStamps.timeStampMs, timeStamps.ntpTimeStampMs, decodeTimeMs, SystemClock.elapsedRealtime());
                }
            }
        }
    }
    
    
    @org.webrtc.CalledByNativeUnchecked
    private DecodedTextureBuffer dequeueTextureBuffer(final int dequeueTimeoutMs) {
        this.checkOnMediaCodecThread();
        if (!useSurface()) {
            throw new IllegalStateException("dequeueTexture() called for byte buffer decoding.");
        }
        final DecodedOutputBuffer outputBuffer = this.dequeueOutputBuffer(dequeueTimeoutMs);
        if (outputBuffer != null) {
            this.dequeuedSurfaceOutputBuffers.add(outputBuffer);
        }
        this.MaybeRenderDecodedTextureBuffer();
        final DecodedTextureBuffer renderedBuffer = this.textureListener.dequeueTextureBuffer(dequeueTimeoutMs);
        if (renderedBuffer != null) {
            this.MaybeRenderDecodedTextureBuffer();
            return renderedBuffer;
        }
        if (this.dequeuedSurfaceOutputBuffers.size() >= Math.min(3, this.outputBuffers.length) || (dequeueTimeoutMs > 0 && !this.dequeuedSurfaceOutputBuffers.isEmpty())) {
            ++this.droppedFrames;
            final DecodedOutputBuffer droppedFrame = this.dequeuedSurfaceOutputBuffers.remove();
            if (dequeueTimeoutMs > 0) {
                org.webrtc.Logging.w("MediaCodecVideoDecoder", "Draining decoder. Dropping frame with TS: " + droppedFrame.presentationTimeStampMs + ". Total number of dropped frames: " + this.droppedFrames);
            }
            else {
                org.webrtc.Logging.w("MediaCodecVideoDecoder", "Too many output buffers " + this.dequeuedSurfaceOutputBuffers.size() + ". Dropping frame with TS: " + droppedFrame.presentationTimeStampMs + ". Total number of dropped frames: " + this.droppedFrames);
            }
            this.mediaCodec.releaseOutputBuffer(droppedFrame.index, false);
            return new DecodedTextureBuffer(null, droppedFrame.presentationTimeStampMs, droppedFrame.timeStampMs, droppedFrame.ntpTimeStampMs, droppedFrame.decodeTimeMs, SystemClock.elapsedRealtime() - droppedFrame.endDecodeTimeMs);
        }
        return null;
    }
    
    private void MaybeRenderDecodedTextureBuffer() {
        if (this.dequeuedSurfaceOutputBuffers.isEmpty() || this.textureListener.isWaitingForTexture()) {
            return;
        }
        final DecodedOutputBuffer buffer = this.dequeuedSurfaceOutputBuffers.remove();
        this.textureListener.addBufferToRender(buffer);
        this.mediaCodec.releaseOutputBuffer(buffer.index, true);
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private void returnDecodedOutputBuffer(final int index) throws IllegalStateException, MediaCodec.CodecException {
        this.checkOnMediaCodecThread();
        if (useSurface()) {
            throw new IllegalStateException("returnDecodedOutputBuffer() called for surface decoding.");
        }
        this.mediaCodec.releaseOutputBuffer(index, false);
    }
    
    @org.webrtc.CalledByNative
    ByteBuffer[] getInputBuffers() {
        return this.inputBuffers;
    }
    
    @org.webrtc.CalledByNative
    ByteBuffer[] getOutputBuffers() {
        return this.outputBuffers;
    }
    
    @org.webrtc.CalledByNative
    int getColorFormat() {
        return this.colorFormat;
    }
    
    @org.webrtc.CalledByNative
    int getWidth() {
        return this.width;
    }
    
    @org.webrtc.CalledByNative
    int getHeight() {
        return this.height;
    }
    
    @org.webrtc.CalledByNative
    int getStride() {
        return this.stride;
    }
    
    @org.webrtc.CalledByNative
    int getSliceHeight() {
        return this.sliceHeight;
    }
    
    private static native long nativeCreateDecoder(final String p0, final boolean p1);
    
    static {
        MediaCodecVideoDecoder.runningInstance = null;
        MediaCodecVideoDecoder.errorCallback = null;
        MediaCodecVideoDecoder.codecErrors = 0;
        MediaCodecVideoDecoder.hwDecoderDisabledTypes = new HashSet<String>();
        MediaCodecVideoDecoder.vp8HwCodecBlacklist = new String[] { "OMX.google." };
        supportedVp9HwCodecPrefixes = new String[] { "OMX.qcom.", "OMX.Exynos.", "OMX." };
        MediaCodecVideoDecoder.vp9HwCodecBlacklist = new String[] { "OMX.google." };
        MediaCodecVideoDecoder.h264HwCodecBlacklist = new String[] { "OMX.google." };
        supportedH265HwCodecPrefixes = new String[] { "OMX." };
        MediaCodecVideoDecoder.h265HwCodecBlacklist = new String[] { "OMX.google." };
        supportedColorList = Arrays.asList(19, 21, 2141391872, 2141391873, 2141391874, 2141391875, 2141391876);
    }
    
    static class HwDecoderFactory implements org.webrtc.VideoDecoderFactory
    {
        private final org.webrtc.VideoCodecInfo[] supportedHardwareCodecs;
        
        HwDecoderFactory() {
            this.supportedHardwareCodecs = getSupportedHardwareCodecs();
        }
        
        private static boolean isSameCodec(final org.webrtc.VideoCodecInfo codecA, final org.webrtc.VideoCodecInfo codecB) {
            return codecA.name.equalsIgnoreCase(codecB.name) && (!codecA.name.equalsIgnoreCase("H264") || org.webrtc.H264Utils.isSameH264Profile(codecA.params, codecB.params));
        }
        
        private static boolean isCodecSupported(final org.webrtc.VideoCodecInfo[] supportedCodecs, final org.webrtc.VideoCodecInfo codec) {
            for (final org.webrtc.VideoCodecInfo supportedCodec : supportedCodecs) {
                if (isSameCodec(supportedCodec, codec)) {
                    return true;
                }
            }
            return false;
        }
        
        private static org.webrtc.VideoCodecInfo[] getSupportedHardwareCodecs() {
            final List<org.webrtc.VideoCodecInfo> codecs = new ArrayList<org.webrtc.VideoCodecInfo>();
            if (MediaCodecVideoDecoder.isVp8HwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoDecoder", "VP8 HW Decoder supported.");
                codecs.add(new org.webrtc.VideoCodecInfo("VP8", new HashMap<String, String>()));
            }
            if (MediaCodecVideoDecoder.isVp9HwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoDecoder", "VP9 HW Decoder supported.");
                codecs.add(new org.webrtc.VideoCodecInfo("VP9", new HashMap<String, String>()));
            }
            if (MediaCodecVideoDecoder.isH264HighProfileHwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoDecoder", "H.264 High Profile HW Decoder supported.");
                codecs.add(org.webrtc.H264Utils.DEFAULT_H264_HIGH_PROFILE_CODEC);
            }
            if (MediaCodecVideoDecoder.isH264HwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoDecoder", "H.264 HW Decoder supported.");
                codecs.add(org.webrtc.H264Utils.DEFAULT_H264_BASELINE_PROFILE_CODEC);
            }
            return codecs.toArray(new org.webrtc.VideoCodecInfo[codecs.size()]);
        }
        
        @Override
        public org.webrtc.VideoCodecInfo[] getSupportedCodecs() {
            return this.supportedHardwareCodecs;
        }
        
        
        @Override
        public org.webrtc.VideoDecoder createDecoder(final org.webrtc.VideoCodecInfo codec) {
            if (!isCodecSupported(this.supportedHardwareCodecs, codec)) {
                org.webrtc.Logging.d("MediaCodecVideoDecoder", "No HW video decoder for codec " + codec.name);
                return null;
            }
            org.webrtc.Logging.d("MediaCodecVideoDecoder", "Create HW video decoder for " + codec.name);
            return new org.webrtc.WrappedNativeVideoDecoder() {
                @Override
                public long createNativeVideoDecoder() {
                    return nativeCreateDecoder(codec.name, MediaCodecVideoDecoder.useSurface());
                }
            };
        }
    }
    
    public enum VideoCodecType
    {
        VIDEO_CODEC_UNKNOWN, 
        VIDEO_CODEC_VP8, 
        VIDEO_CODEC_VP9, 
        VIDEO_CODEC_H264, 
        VIDEO_CODEC_H265;
        
        @org.webrtc.CalledByNative("VideoCodecType")
        static VideoCodecType fromNativeIndex(final int nativeIndex) {
            return values()[nativeIndex];
        }
    }
    
    private static class DecoderProperties
    {
        public final String codecName;
        public final int colorFormat;
        
        public DecoderProperties(final String codecName, final int colorFormat) {
            this.codecName = codecName;
            this.colorFormat = colorFormat;
        }
    }
    
    private static class TimeStamps
    {
        private final long decodeStartTimeMs;
        private final long timeStampMs;
        private final long ntpTimeStampMs;
        
        public TimeStamps(final long decodeStartTimeMs, final long timeStampMs, final long ntpTimeStampMs) {
            this.decodeStartTimeMs = decodeStartTimeMs;
            this.timeStampMs = timeStampMs;
            this.ntpTimeStampMs = ntpTimeStampMs;
        }
    }
    
    private static class DecodedOutputBuffer
    {
        private final int index;
        private final int offset;
        private final int size;
        private final long presentationTimeStampMs;
        private final long timeStampMs;
        private final long ntpTimeStampMs;
        private final long decodeTimeMs;
        private final long endDecodeTimeMs;
        
        public DecodedOutputBuffer(final int index, final int offset, final int size, final long presentationTimeStampMs, final long timeStampMs, final long ntpTimeStampMs, final long decodeTime, final long endDecodeTime) {
            this.index = index;
            this.offset = offset;
            this.size = size;
            this.presentationTimeStampMs = presentationTimeStampMs;
            this.timeStampMs = timeStampMs;
            this.ntpTimeStampMs = ntpTimeStampMs;
            this.decodeTimeMs = decodeTime;
            this.endDecodeTimeMs = endDecodeTime;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        int getIndex() {
            return this.index;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        int getOffset() {
            return this.offset;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        int getSize() {
            return this.size;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        long getPresentationTimestampMs() {
            return this.presentationTimeStampMs;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        long getTimestampMs() {
            return this.timeStampMs;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        long getNtpTimestampMs() {
            return this.ntpTimeStampMs;
        }
        
        @org.webrtc.CalledByNative("DecodedOutputBuffer")
        long getDecodeTimeMs() {
            return this.decodeTimeMs;
        }
    }
    
    private static class DecodedTextureBuffer
    {
        private final org.webrtc.VideoFrame.Buffer videoFrameBuffer;
        private final long presentationTimeStampMs;
        private final long timeStampMs;
        private final long ntpTimeStampMs;
        private final long decodeTimeMs;
        private final long frameDelayMs;
        
        public DecodedTextureBuffer(final org.webrtc.VideoFrame.Buffer videoFrameBuffer, final long presentationTimeStampMs, final long timeStampMs, final long ntpTimeStampMs, final long decodeTimeMs, final long frameDelay) {
            this.videoFrameBuffer = videoFrameBuffer;
            this.presentationTimeStampMs = presentationTimeStampMs;
            this.timeStampMs = timeStampMs;
            this.ntpTimeStampMs = ntpTimeStampMs;
            this.decodeTimeMs = decodeTimeMs;
            this.frameDelayMs = frameDelay;
        }
        
        @org.webrtc.CalledByNative("DecodedTextureBuffer")
        org.webrtc.VideoFrame.Buffer getVideoFrameBuffer() {
            return this.videoFrameBuffer;
        }
        
        @org.webrtc.CalledByNative("DecodedTextureBuffer")
        long getPresentationTimestampMs() {
            return this.presentationTimeStampMs;
        }
        
        @org.webrtc.CalledByNative("DecodedTextureBuffer")
        long getTimeStampMs() {
            return this.timeStampMs;
        }
        
        @org.webrtc.CalledByNative("DecodedTextureBuffer")
        long getNtpTimestampMs() {
            return this.ntpTimeStampMs;
        }
        
        @org.webrtc.CalledByNative("DecodedTextureBuffer")
        long getDecodeTimeMs() {
            return this.decodeTimeMs;
        }
        
        @org.webrtc.CalledByNative("DecodedTextureBuffer")
        long getFrameDelayMs() {
            return this.frameDelayMs;
        }
    }
    
    private class TextureListener implements org.webrtc.VideoSink
    {
        private final org.webrtc.SurfaceTextureHelper surfaceTextureHelper;
        private final Object newFrameLock;
        
        private DecodedOutputBuffer bufferToRender;
        
        private DecodedTextureBuffer renderedBuffer;
        
        public TextureListener(final org.webrtc.SurfaceTextureHelper surfaceTextureHelper) {
            this.newFrameLock = new Object();
            (this.surfaceTextureHelper = surfaceTextureHelper).startListening(this);
        }
        
        public void addBufferToRender(final DecodedOutputBuffer buffer) {
            if (this.bufferToRender != null) {
                org.webrtc.Logging.e("MediaCodecVideoDecoder", "Unexpected addBufferToRender() called while waiting for a texture.");
                throw new IllegalStateException("Waiting for a texture.");
            }
            this.bufferToRender = buffer;
        }
        
        public boolean isWaitingForTexture() {
            synchronized (this.newFrameLock) {
                return this.bufferToRender != null;
            }
        }
        
        public void setSize(final int width, final int height) {
            this.surfaceTextureHelper.setTextureSize(width, height);
        }
        
        @Override
        public void onFrame(final org.webrtc.VideoFrame frame) {
            synchronized (this.newFrameLock) {
                if (this.renderedBuffer != null) {
                    org.webrtc.Logging.e("MediaCodecVideoDecoder", "Unexpected onFrame() called while already holding a texture.");
                    throw new IllegalStateException("Already holding a texture.");
                }
                final org.webrtc.VideoFrame.Buffer buffer = frame.getBuffer();
                buffer.retain();
                this.renderedBuffer = new DecodedTextureBuffer(buffer, this.bufferToRender.presentationTimeStampMs, this.bufferToRender.timeStampMs, this.bufferToRender.ntpTimeStampMs, this.bufferToRender.decodeTimeMs, SystemClock.elapsedRealtime() - this.bufferToRender.endDecodeTimeMs);
                this.bufferToRender = null;
                this.newFrameLock.notifyAll();
            }
        }
        
        
        public DecodedTextureBuffer dequeueTextureBuffer(final int timeoutMs) {
            synchronized (this.newFrameLock) {
                if (this.renderedBuffer == null && timeoutMs > 0 && this.isWaitingForTexture()) {
                    try {
                        this.newFrameLock.wait(timeoutMs);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                final DecodedTextureBuffer returnedBuffer = this.renderedBuffer;
                this.renderedBuffer = null;
                return returnedBuffer;
            }
        }
        
        public void release() {
            this.surfaceTextureHelper.stopListening();
            synchronized (this.newFrameLock) {
                if (this.renderedBuffer != null) {
                    this.renderedBuffer.getVideoFrameBuffer().release();
                    this.renderedBuffer = null;
                }
            }
            this.surfaceTextureHelper.dispose();
        }
    }
    
    public interface MediaCodecVideoDecoderErrorCallback
    {
        void onMediaCodecVideoDecoderCriticalError(final int p0);
    }
}
