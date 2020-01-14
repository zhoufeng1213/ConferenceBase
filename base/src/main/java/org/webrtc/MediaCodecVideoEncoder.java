package org.webrtc;

import android.annotation.TargetApi;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Deprecated
@TargetApi(19)
public class MediaCodecVideoEncoder
{
    private static final String TAG = "MediaCodecVideoEncoder";
    private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
    private static final int DEQUEUE_TIMEOUT = 0;
    private static final int BITRATE_ADJUSTMENT_FPS = 30;
    private static final int MAXIMUM_INITIAL_FPS = 30;
    private static final double BITRATE_CORRECTION_SEC = 3.0;
    private static final double BITRATE_CORRECTION_MAX_SCALE = 4.0;
    private static final int BITRATE_CORRECTION_STEPS = 20;
    private static final long QCOM_VP8_KEY_FRAME_INTERVAL_ANDROID_L_MS = 15000L;
    private static final long QCOM_VP8_KEY_FRAME_INTERVAL_ANDROID_M_MS = 20000L;
    private static final long QCOM_VP8_KEY_FRAME_INTERVAL_ANDROID_N_MS = 15000L;
    
    private static MediaCodecVideoEncoder runningInstance;
    
    private static MediaCodecVideoEncoderErrorCallback errorCallback;
    private static int codecErrors;
    private static Set<String> hwEncoderDisabledTypes;
    
    private static org.webrtc.EglBase staticEglBase;
    
    private Thread mediaCodecThread;
    
    private MediaCodec mediaCodec;
    private ByteBuffer[] outputBuffers;
    
    private org.webrtc.EglBase14 eglBase;
    private int profile;
    private int width;
    private int height;
    
    private Surface inputSurface;
    
    private org.webrtc.GlRectDrawer drawer;
    private static final String VP8_MIME_TYPE = "video/x-vnd.on2.vp8";
    private static final String VP9_MIME_TYPE = "video/x-vnd.on2.vp9";
    private static final String H264_MIME_TYPE = "video/avc";
    private static final String H265_MIME_TYPE = "video/hevc";
    private static final int VIDEO_AVCProfileHigh = 8;
    private static final int VIDEO_AVCLevel3 = 256;
    private static final MediaCodecProperties qcomVp8HwProperties;
    private static final MediaCodecProperties exynosVp8HwProperties;
    private static final MediaCodecProperties intelVp8HwProperties;
    private static final MediaCodecProperties defaultVp8HwProperties;
    private static String[] vp8HwCodecBlacklist;
    private static final MediaCodecProperties qcomVp9HwProperties;
    private static final MediaCodecProperties exynosVp9HwProperties;
    private static final MediaCodecProperties defaultVp9HwProperties;
    private static final MediaCodecProperties[] vp9HwList;
    private static String[] vp9HwCodecBlacklist;
    private static final MediaCodecProperties qcomH264HwProperties;
    private static final MediaCodecProperties exynosH264HwProperties;
	private static final MediaCodecProperties rkH264HwProperties;
	private static final MediaCodecProperties googleH264HwProperties;
    private static final MediaCodecProperties mediatekH264HwProperties;
    private static final MediaCodecProperties defaultH264HwProperties;
    private static String[] h264HwCodecBlacklist;
    private static final MediaCodecProperties exynosH264HighProfileHwProperties;
	private static final MediaCodecProperties rkH264HighProfileHwProperties;
    private static final MediaCodecProperties[] h264HighProfileHwList;
    private static final MediaCodecProperties qcomH265HwProperties;
    private static final MediaCodecProperties[] h265HwList;
    private static String[] h265HwCodecBlacklist;
    private static final String[] H264_HW_EXCEPTION_MODELS;
    private static final int VIDEO_ControlRateConstant = 2;
    private static final int COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m = 2141391876;
    private static final int[] supportedColorList;
    private static final int[] supportedSurfaceColorList;
    private VideoCodecType type;
    private int colorFormat;
    private BitrateAdjustmentType bitrateAdjustmentType;
    private double bitrateAccumulator;
    private double bitrateAccumulatorMax;
    private double bitrateObservationTimeMs;
    private int bitrateAdjustmentScaleExp;
    private int targetBitrateBps;
    private int targetFps;
    private long forcedKeyFrameMs;
    private long lastKeyFrameMs;
    
    private ByteBuffer configData;
    
    public static org.webrtc.VideoEncoderFactory createFactory() {
        return new org.webrtc.DefaultVideoEncoderFactory(new HwEncoderFactory());
    }
    
    public static void setEglContext(final org.webrtc.EglBase.Context eglContext) {
        if (MediaCodecVideoEncoder.staticEglBase != null) {
            org.webrtc.Logging.w("MediaCodecVideoEncoder", "Egl context already set.");
            MediaCodecVideoEncoder.staticEglBase.release();
        }
        MediaCodecVideoEncoder.staticEglBase = org.webrtc.EglBase.create(eglContext);
    }
    
    public static void disposeEglContext() {
        if (MediaCodecVideoEncoder.staticEglBase != null) {
            MediaCodecVideoEncoder.staticEglBase.release();
            MediaCodecVideoEncoder.staticEglBase = null;
        }
    }
    
    
    static org.webrtc.EglBase.Context getEglContext() {
        return (MediaCodecVideoEncoder.staticEglBase == null) ? null : MediaCodecVideoEncoder.staticEglBase.getEglBaseContext();
    }
    
    private static MediaCodecProperties[] vp8HwList() {
        final ArrayList<MediaCodecProperties> supported_codecs = new ArrayList<MediaCodecProperties>();
        supported_codecs.add(MediaCodecVideoEncoder.qcomVp8HwProperties);
        supported_codecs.add(MediaCodecVideoEncoder.exynosVp8HwProperties);
        if (org.webrtc.PeerConnectionFactory.fieldTrialsFindFullName("WebRTC-IntelVP8").equals("Enabled")) {
            supported_codecs.add(MediaCodecVideoEncoder.intelVp8HwProperties);
        }
        supported_codecs.add(MediaCodecVideoEncoder.defaultVp8HwProperties);
        return supported_codecs.toArray(new MediaCodecProperties[supported_codecs.size()]);
    }
    
    private static final MediaCodecProperties[] h264HwList() {
        final ArrayList<MediaCodecProperties> supported_codecs = new ArrayList<MediaCodecProperties>();
        supported_codecs.add(MediaCodecVideoEncoder.rkH264HwProperties);
        supported_codecs.add(MediaCodecVideoEncoder.googleH264HwProperties);
        supported_codecs.add(MediaCodecVideoEncoder.qcomH264HwProperties);
        supported_codecs.add(MediaCodecVideoEncoder.exynosH264HwProperties);
        if (org.webrtc.PeerConnectionFactory.fieldTrialsFindFullName("WebRTC-MediaTekH264").equals("Enabled")) {
            supported_codecs.add(MediaCodecVideoEncoder.mediatekH264HwProperties);
        }
        supported_codecs.add(MediaCodecVideoEncoder.defaultH264HwProperties);
        return supported_codecs.toArray(new MediaCodecProperties[supported_codecs.size()]);
    }
    
    public static void setErrorCallback(final MediaCodecVideoEncoderErrorCallback errorCallback) {
        org.webrtc.Logging.d("MediaCodecVideoEncoder", "Set error callback");
        MediaCodecVideoEncoder.errorCallback = errorCallback;
    }
    
    public static void disableVp8HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoEncoder", "VP8 encoding is disabled by application.");
        MediaCodecVideoEncoder.hwEncoderDisabledTypes.add("video/x-vnd.on2.vp8");
    }
    
    public static void disableVp9HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoEncoder", "VP9 encoding is disabled by application.");
        MediaCodecVideoEncoder.hwEncoderDisabledTypes.add("video/x-vnd.on2.vp9");
    }
    
    public static void disableH264HwCodec() {
        org.webrtc.Logging.w("MediaCodecVideoEncoder", "H.264 encoding is disabled by application.");
        MediaCodecVideoEncoder.hwEncoderDisabledTypes.add("video/avc");
    }
    
    public static boolean isVp8HwSupported() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/x-vnd.on2.vp8") && findHwEncoder("video/x-vnd.on2.vp8", vp8HwList(), MediaCodecVideoEncoder.supportedColorList) != null;
    }
    
    
    public static EncoderProperties vp8HwEncoderProperties() {
        if (MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/x-vnd.on2.vp8")) {
            return null;
        }
        return findHwEncoder("video/x-vnd.on2.vp8", vp8HwList(), MediaCodecVideoEncoder.supportedColorList);
    }
    
    public static boolean isVp9HwSupported() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/x-vnd.on2.vp9") && findHwEncoder("video/x-vnd.on2.vp9", MediaCodecVideoEncoder.vp9HwList, MediaCodecVideoEncoder.supportedColorList) != null;
    }
    
    public static boolean isH264HwSupported() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/avc")
                && findHwEncoder("video/avc", h264HwList(), MediaCodecVideoEncoder.supportedColorList) != null;
    }
    
    public static boolean isH264HighProfileHwSupported() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/avc") && findHwEncoder("video/avc", MediaCodecVideoEncoder.h264HighProfileHwList, MediaCodecVideoEncoder.supportedColorList) != null;
    }
    
    public static boolean isVp8HwSupportedUsingTextures() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/x-vnd.on2.vp8") && findHwEncoder("video/x-vnd.on2.vp8", vp8HwList(), MediaCodecVideoEncoder.supportedSurfaceColorList) != null;
    }
    
    public static boolean isVp9HwSupportedUsingTextures() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/x-vnd.on2.vp9") && findHwEncoder("video/x-vnd.on2.vp9", MediaCodecVideoEncoder.vp9HwList, MediaCodecVideoEncoder.supportedSurfaceColorList) != null;
    }
    
    public static boolean isH264HwSupportedUsingTextures() {
        return !MediaCodecVideoEncoder.hwEncoderDisabledTypes.contains("video/avc") && findHwEncoder("video/avc", h264HwList(), MediaCodecVideoEncoder.supportedSurfaceColorList) != null;
    }
    
    private static boolean isBlacklisted(final String codecName, final String mime) {
        String[] blacklist;
        if (mime.equals("video/x-vnd.on2.vp8")) {
            blacklist = MediaCodecVideoEncoder.vp8HwCodecBlacklist;
        }
        else if (mime.equals("video/x-vnd.on2.vp9")) {
            blacklist = MediaCodecVideoEncoder.vp9HwCodecBlacklist;
        }
        else if (mime.equals("video/avc")) {
            blacklist = MediaCodecVideoEncoder.h264HwCodecBlacklist;
        }
        else {
            if (!mime.equals("video/hevc")) {
                return false;
            }
            blacklist = MediaCodecVideoEncoder.h265HwCodecBlacklist;
        }
        for (final String blacklistedCodec : blacklist) {
            if (codecName.startsWith(blacklistedCodec)) {
                return true;
            }
        }
        return false;
    }
    
    private static EncoderProperties findHwEncoder(final String mime, final MediaCodecProperties[] supportedHwCodecProperties, final int[] colorList) {
        if (Build.VERSION.SDK_INT < 19) {
            return null;
        }
        if (mime.equals("video/avc")) {
            final List<String> exceptionModels = Arrays.asList(MediaCodecVideoEncoder.H264_HW_EXCEPTION_MODELS);
            if (exceptionModels.contains(Build.MODEL)) {
                org.webrtc.Logging.w("MediaCodecVideoEncoder", "Model: " + Build.MODEL + " has black listed H.264 encoder.");
                return null;
            }
        }
        for (int i = 0; i < MediaCodecList.getCodecCount(); ++i) {
            MediaCodecInfo info = null;
            try {
                info = MediaCodecList.getCodecInfoAt(i);
            }
            catch (IllegalArgumentException e) {
                org.webrtc.Logging.e("MediaCodecVideoEncoder", "Cannot retrieve encoder codec info", e);
            }
            if (info != null) {
                if (info.isEncoder()) {
                    String name = null;
                    for (final String mimeType : info.getSupportedTypes()) {
                        if (mimeType.equals(mime)) {
                            name = info.getName();
                            org.webrtc.Logging.d("MediaCodecVideoEncoder", "Found codec name: " + name);
                            break;
                        }
                    }
                    if (name != null) {
                        if (!isBlacklisted(name, mime)) {
                            org.webrtc.Logging.v("MediaCodecVideoEncoder", "Found candidate encoder " + name);
                            boolean supportedCodec = false;
                            BitrateAdjustmentType bitrateAdjustmentType = BitrateAdjustmentType.NO_ADJUSTMENT;
                            for (final MediaCodecProperties codecProperties : supportedHwCodecProperties) {
                                if (name.startsWith(codecProperties.codecPrefix)) {
                                    if (Build.VERSION.SDK_INT >= codecProperties.minSdk) {
                                        if (codecProperties.bitrateAdjustmentType != BitrateAdjustmentType.NO_ADJUSTMENT) {
                                            bitrateAdjustmentType = codecProperties.bitrateAdjustmentType;
                                            org.webrtc.Logging.w("MediaCodecVideoEncoder", "Codec " + name + " requires bitrate adjustment: " + bitrateAdjustmentType);
                                        }
                                        supportedCodec = true;
                                        break;
                                    }
                                    org.webrtc.Logging.w("MediaCodecVideoEncoder", "Codec " + name + " is disabled due to SDK version " + Build.VERSION.SDK_INT);
                                }
                            }
                            if (supportedCodec) {
                                MediaCodecInfo.CodecCapabilities capabilities;
                                try {
                                    capabilities = info.getCapabilitiesForType(mime);
                                }
                                catch (IllegalArgumentException e2) {
                                    org.webrtc.Logging.e("MediaCodecVideoEncoder", "Cannot retrieve encoder capabilities", e2);
                                    continue;
                                }
                                for (final int colorFormat : capabilities.colorFormats) {
                                    org.webrtc.Logging.v("MediaCodecVideoEncoder", "   Color: 0x" + Integer.toHexString(colorFormat));
                                }
                                for (final int supportedColorFormat : colorList) {
                                    for (final int codecColorFormat : capabilities.colorFormats) {
                                        if (codecColorFormat == supportedColorFormat) {
                                            org.webrtc.Logging.d("MediaCodecVideoEncoder", "Found target encoder for mime " + mime + " : " + name + ". Color: 0x" + Integer.toHexString(codecColorFormat) + ". Bitrate adjustment: " + bitrateAdjustmentType);
                                            return new EncoderProperties(name, codecColorFormat, bitrateAdjustmentType);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
    
    @org.webrtc.CalledByNative
    MediaCodecVideoEncoder() {
        this.bitrateAdjustmentType = BitrateAdjustmentType.NO_ADJUSTMENT;
        this.configData = null;
    }
    
    private void checkOnMediaCodecThread() {
        if (this.mediaCodecThread.getId() != Thread.currentThread().getId()) {
            throw new RuntimeException("MediaCodecVideoEncoder previously operated on " + this.mediaCodecThread + " but is now called on " + Thread.currentThread());
        }
    }
    
    public static void printStackTrace() {
        if (MediaCodecVideoEncoder.runningInstance != null && MediaCodecVideoEncoder.runningInstance.mediaCodecThread != null) {
            final StackTraceElement[] mediaCodecStackTraces = MediaCodecVideoEncoder.runningInstance.mediaCodecThread.getStackTrace();
            if (mediaCodecStackTraces.length > 0) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "MediaCodecVideoEncoder stacks trace:");
                for (final StackTraceElement stackTrace : mediaCodecStackTraces) {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", stackTrace.toString());
                }
            }
        }
    }
    
    
    static MediaCodec createByCodecName(final String codecName) {
        try {
            return MediaCodec.createByCodecName(codecName);
        }
        catch (Exception e) {
            return null;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    boolean initEncode(final VideoCodecType type, final int profile, final int width, final int height, final int kbps, int fps, final boolean useSurface) {
        org.webrtc.Logging.d("MediaCodecVideoEncoder", "Java initEncode: " + type + ". Profile: " + profile + " : " + width + " x " + height + ". @ " + kbps + " kbps. Fps: " + fps + ". Encode from texture : " + useSurface);
        this.profile = profile;
        this.width = width;
        this.height = height;
        if (this.mediaCodecThread != null) {
            throw new RuntimeException("Forgot to release()?");
        }
        EncoderProperties properties = null;
        String mime = null;
        int keyFrameIntervalSec = 0;
        boolean configureH264HighProfile = false;
        if (type == VideoCodecType.VIDEO_CODEC_VP8) {
            mime = "video/x-vnd.on2.vp8";
            properties = findHwEncoder("video/x-vnd.on2.vp8", vp8HwList(), useSurface ? MediaCodecVideoEncoder.supportedSurfaceColorList : MediaCodecVideoEncoder.supportedColorList);
            keyFrameIntervalSec = 100;
        }
        else if (type == VideoCodecType.VIDEO_CODEC_VP9) {
            mime = "video/x-vnd.on2.vp9";
            properties = findHwEncoder("video/x-vnd.on2.vp9", MediaCodecVideoEncoder.vp9HwList, useSurface ? MediaCodecVideoEncoder.supportedSurfaceColorList : MediaCodecVideoEncoder.supportedColorList);
            keyFrameIntervalSec = 100;
        }
        else if (type == VideoCodecType.VIDEO_CODEC_H264) {
            mime = "video/avc";
            properties = findHwEncoder("video/avc", h264HwList(), useSurface ? MediaCodecVideoEncoder.supportedSurfaceColorList : MediaCodecVideoEncoder.supportedColorList);
            if (profile == H264Profile.CONSTRAINED_HIGH.getValue()) {
                final EncoderProperties h264HighProfileProperties = findHwEncoder("video/avc", MediaCodecVideoEncoder.h264HighProfileHwList, useSurface ? MediaCodecVideoEncoder.supportedSurfaceColorList : MediaCodecVideoEncoder.supportedColorList);
                if (h264HighProfileProperties != null) {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "High profile H.264 encoder supported.");
                    configureH264HighProfile = true;
                }
                else {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "High profile H.264 encoder requested, but not supported. Use baseline.");
                }
            }
            keyFrameIntervalSec = 20;
        }
        else {
            if (type != VideoCodecType.VIDEO_CODEC_H265) {
                throw new RuntimeException("initEncode: Non-supported codec " + type);
            }
            mime = "video/hevc";
            properties = findHwEncoder("video/hevc", MediaCodecVideoEncoder.h265HwList, useSurface ? MediaCodecVideoEncoder.supportedSurfaceColorList : MediaCodecVideoEncoder.supportedColorList);
            keyFrameIntervalSec = 20;
        }
        if (properties == null) {
            throw new RuntimeException("Can not find HW encoder for " + type);
        }
        MediaCodecVideoEncoder.runningInstance = this;
        this.colorFormat = properties.colorFormat;
        this.bitrateAdjustmentType = properties.bitrateAdjustmentType;
        if (this.bitrateAdjustmentType == BitrateAdjustmentType.FRAMERATE_ADJUSTMENT) {
            fps = 30;
        }
        else {
            fps = Math.min(fps, 30);
        }
        this.forcedKeyFrameMs = 0L;
        this.lastKeyFrameMs = -1L;
        if (type == VideoCodecType.VIDEO_CODEC_VP8 && properties.codecName.startsWith(MediaCodecVideoEncoder.qcomVp8HwProperties.codecPrefix)) {
            if (Build.VERSION.SDK_INT == 21 || Build.VERSION.SDK_INT == 22) {
                this.forcedKeyFrameMs = 15000L;
            }
            else if (Build.VERSION.SDK_INT == 23) {
                this.forcedKeyFrameMs = 20000L;
            }
            else if (Build.VERSION.SDK_INT > 23) {
                this.forcedKeyFrameMs = 15000L;
            }
        }
        org.webrtc.Logging.d("MediaCodecVideoEncoder", "Color format: " + this.colorFormat + ". Bitrate adjustment: " + this.bitrateAdjustmentType + ". Key frame interval: " + this.forcedKeyFrameMs + " . Initial fps: " + fps);
        this.targetBitrateBps = 1000 * kbps;
        this.targetFps = fps;
        this.bitrateAccumulatorMax = this.targetBitrateBps / 8.0;
        this.bitrateAccumulator = 0.0;
        this.bitrateObservationTimeMs = 0.0;
        this.bitrateAdjustmentScaleExp = 0;
        this.mediaCodecThread = Thread.currentThread();
        try {
            final MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger("bitrate", this.targetBitrateBps);
            format.setInteger("bitrate-mode", 2);
            format.setInteger("color-format", properties.colorFormat);
            format.setInteger("frame-rate", this.targetFps);
            format.setInteger("i-frame-interval", keyFrameIntervalSec);
            if (configureH264HighProfile) {
                format.setInteger("profile", 8);
                format.setInteger("level", 256);
            }
            org.webrtc.Logging.d("MediaCodecVideoEncoder", "  Format: " + format);
            this.mediaCodec = createByCodecName(properties.codecName);
            this.type = type;
            if (this.mediaCodec == null) {
                org.webrtc.Logging.e("MediaCodecVideoEncoder", "Can not create media encoder");
                this.release();
                return false;
            }
            this.mediaCodec.configure(format, (Surface)null, (MediaCrypto)null, 1);
            if (useSurface) {
                this.eglBase = new org.webrtc.EglBase14((org.webrtc.EglBase14.Context)getEglContext(), org.webrtc.EglBase.CONFIG_RECORDABLE);
                this.inputSurface = this.mediaCodec.createInputSurface();
                this.eglBase.createSurface(this.inputSurface);
                this.drawer = new org.webrtc.GlRectDrawer();
            }
            this.mediaCodec.start();
            this.outputBuffers = this.mediaCodec.getOutputBuffers();
            org.webrtc.Logging.d("MediaCodecVideoEncoder", "Output buffers: " + this.outputBuffers.length);
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "initEncode failed", e);
            this.release();
            return false;
        }
        return true;
    }
    
    @org.webrtc.CalledByNativeUnchecked
    ByteBuffer[] getInputBuffers() {
        final ByteBuffer[] inputBuffers = this.mediaCodec.getInputBuffers();
        org.webrtc.Logging.d("MediaCodecVideoEncoder", "Input buffers: " + inputBuffers.length);
        return inputBuffers;
    }
    
    void checkKeyFrameRequired(final boolean requestedKeyFrame, final long presentationTimestampUs) {
        final long presentationTimestampMs = (presentationTimestampUs + 500L) / 1000L;
        if (this.lastKeyFrameMs < 0L) {
            this.lastKeyFrameMs = presentationTimestampMs;
        }
        boolean forcedKeyFrame = false;
        if (!requestedKeyFrame && this.forcedKeyFrameMs > 0L && presentationTimestampMs > this.lastKeyFrameMs + this.forcedKeyFrameMs) {
            forcedKeyFrame = true;
        }
        if (requestedKeyFrame || forcedKeyFrame) {
            if (requestedKeyFrame) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "Sync frame request");
            }
            else {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "Sync frame forced");
            }
            final Bundle b = new Bundle();
            b.putInt("request-sync", 0);
            this.mediaCodec.setParameters(b);
            this.lastKeyFrameMs = presentationTimestampMs;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    boolean encodeBuffer(final boolean isKeyframe, final int inputBuffer, final int size, final long presentationTimestampUs) {
        this.checkOnMediaCodecThread();
        try {
            this.checkKeyFrameRequired(isKeyframe, presentationTimestampUs);
            this.mediaCodec.queueInputBuffer(inputBuffer, 0, size, presentationTimestampUs, 0);
            return true;
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "encodeBuffer failed", e);
            return false;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    boolean encodeFrame(final long nativeEncoder, final boolean isKeyframe, final org.webrtc.VideoFrame frame, final int bufferIndex, final long presentationTimestampUs) {
        this.checkOnMediaCodecThread();
        try {
            this.checkKeyFrameRequired(isKeyframe, presentationTimestampUs);
            final org.webrtc.VideoFrame.Buffer buffer = frame.getBuffer();
            if (buffer instanceof org.webrtc.VideoFrame.TextureBuffer) {
                final org.webrtc.VideoFrame.TextureBuffer textureBuffer = (org.webrtc.VideoFrame.TextureBuffer)buffer;
                this.eglBase.makeCurrent();
                GLES20.glClear(16384);
                org.webrtc.VideoFrameDrawer.drawTexture(this.drawer, textureBuffer, new Matrix(), this.width, this.height, 0, 0, this.width, this.height);
                this.eglBase.swapBuffers(TimeUnit.MICROSECONDS.toNanos(presentationTimestampUs));
            }
            else {
                final org.webrtc.VideoFrame.I420Buffer i420Buffer = buffer.toI420();
                final int chromaHeight = (this.height + 1) / 2;
                final ByteBuffer dataY = i420Buffer.getDataY();
                final ByteBuffer dataU = i420Buffer.getDataU();
                final ByteBuffer dataV = i420Buffer.getDataV();
                final int strideY = i420Buffer.getStrideY();
                final int strideU = i420Buffer.getStrideU();
                final int strideV = i420Buffer.getStrideV();
                if (dataY.capacity() < strideY * this.height) {
                    throw new RuntimeException("Y-plane buffer size too small.");
                }
                if (dataU.capacity() < strideU * chromaHeight) {
                    throw new RuntimeException("U-plane buffer size too small.");
                }
                if (dataV.capacity() < strideV * chromaHeight) {
                    throw new RuntimeException("V-plane buffer size too small.");
                }
                nativeFillInputBuffer(nativeEncoder, bufferIndex, dataY, strideY, dataU, strideU, dataV, strideV);
                i420Buffer.release();
                final int yuvSize = this.width * this.height * 3 / 2;
                this.mediaCodec.queueInputBuffer(bufferIndex, 0, yuvSize, presentationTimestampUs, 0);
            }
            return true;
        }
        catch (RuntimeException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "encodeFrame failed", e);
            return false;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    void release() {
        org.webrtc.Logging.d("MediaCodecVideoEncoder", "Java releaseEncoder");
        this.checkOnMediaCodecThread();
        class CaughtException
        {
            Exception e;
        }
        final CaughtException caughtException = new CaughtException();
        boolean stopHung = false;
        if (this.mediaCodec != null) {
            final CountDownLatch releaseDone = new CountDownLatch(1);
            final Runnable runMediaCodecRelease = new Runnable() {
                @Override
                public void run() {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "Java releaseEncoder on release thread");
                    try {
                        MediaCodecVideoEncoder.this.mediaCodec.stop();
                    }
                    catch (Exception e) {
                        org.webrtc.Logging.e("MediaCodecVideoEncoder", "Media encoder stop failed", e);
                    }
                    try {
                        MediaCodecVideoEncoder.this.mediaCodec.release();
                    }
                    catch (Exception e) {
                        org.webrtc.Logging.e("MediaCodecVideoEncoder", "Media encoder release failed", e);
                        caughtException.e = e;
                    }
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "Java releaseEncoder on release thread done");
                    releaseDone.countDown();
                }
            };
            new Thread(runMediaCodecRelease).start();
            if (!org.webrtc.ThreadUtils.awaitUninterruptibly(releaseDone, 5000L)) {
                org.webrtc.Logging.e("MediaCodecVideoEncoder", "Media encoder release timeout");
                stopHung = true;
            }
            this.mediaCodec = null;
        }
        this.mediaCodecThread = null;
        if (this.drawer != null) {
            this.drawer.release();
            this.drawer = null;
        }
        if (this.eglBase != null) {
            this.eglBase.release();
            this.eglBase = null;
        }
        if (this.inputSurface != null) {
            this.inputSurface.release();
            this.inputSurface = null;
        }
        MediaCodecVideoEncoder.runningInstance = null;
        if (stopHung) {
            ++MediaCodecVideoEncoder.codecErrors;
            if (MediaCodecVideoEncoder.errorCallback != null) {
                org.webrtc.Logging.e("MediaCodecVideoEncoder", "Invoke codec error callback. Errors: " + MediaCodecVideoEncoder.codecErrors);
                MediaCodecVideoEncoder.errorCallback.onMediaCodecVideoEncoderCriticalError(MediaCodecVideoEncoder.codecErrors);
            }
            throw new RuntimeException("Media encoder release timeout.");
        }
        if (caughtException.e != null) {
            final RuntimeException runtimeException = new RuntimeException(caughtException.e);
            runtimeException.setStackTrace(org.webrtc.ThreadUtils.concatStackTraces(caughtException.e.getStackTrace(), runtimeException.getStackTrace()));
            throw runtimeException;
        }
        org.webrtc.Logging.d("MediaCodecVideoEncoder", "Java releaseEncoder done");
    }
    
    @org.webrtc.CalledByNativeUnchecked
    private boolean setRates(final int kbps, final int frameRate) {
        this.checkOnMediaCodecThread();
        int codecBitrateBps = 1000 * kbps;
        if (this.bitrateAdjustmentType == BitrateAdjustmentType.DYNAMIC_ADJUSTMENT) {
            this.bitrateAccumulatorMax = codecBitrateBps / 8.0;
            if (this.targetBitrateBps > 0 && codecBitrateBps < this.targetBitrateBps) {
                this.bitrateAccumulator = this.bitrateAccumulator * codecBitrateBps / this.targetBitrateBps;
            }
        }
        this.targetBitrateBps = codecBitrateBps;
        this.targetFps = frameRate;
        if (this.bitrateAdjustmentType == BitrateAdjustmentType.FRAMERATE_ADJUSTMENT && this.targetFps > 0) {
            codecBitrateBps = 30 * this.targetBitrateBps / this.targetFps;
            org.webrtc.Logging.v("MediaCodecVideoEncoder", "setRates: " + kbps + " -> " + codecBitrateBps / 1000 + " kbps. Fps: " + this.targetFps);
        }
        else if (this.bitrateAdjustmentType == BitrateAdjustmentType.DYNAMIC_ADJUSTMENT) {
            org.webrtc.Logging.v("MediaCodecVideoEncoder", "setRates: " + kbps + " kbps. Fps: " + this.targetFps + ". ExpScale: " + this.bitrateAdjustmentScaleExp);
            if (this.bitrateAdjustmentScaleExp != 0) {
                codecBitrateBps *= (int)this.getBitrateScale(this.bitrateAdjustmentScaleExp);
            }
        }
        else {
            org.webrtc.Logging.v("MediaCodecVideoEncoder", "setRates: " + kbps + " kbps. Fps: " + this.targetFps);
        }
        try {
            final Bundle params = new Bundle();
            params.putInt("video-bitrate", codecBitrateBps);
            this.mediaCodec.setParameters(params);
            return true;
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "setRates failed", e);
            return false;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    int dequeueInputBuffer() {
        this.checkOnMediaCodecThread();
        try {
            return this.mediaCodec.dequeueInputBuffer(0L);
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "dequeueIntputBuffer failed", e);
            return -2;
        }
    }
    
    
    @org.webrtc.CalledByNativeUnchecked
    OutputBufferInfo dequeueOutputBuffer() {
        this.checkOnMediaCodecThread();
        try {
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int result = this.mediaCodec.dequeueOutputBuffer(info, 0L);
            if (result >= 0) {
                final boolean isConfigFrame = (info.flags & 0x2) != 0x0;
                if (isConfigFrame) {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "Config frame generated. Offset: " + info.offset + ". Size: " + info.size);
                    this.configData = ByteBuffer.allocateDirect(info.size);
                    this.outputBuffers[result].position(info.offset);
                    this.outputBuffers[result].limit(info.offset + info.size);
                    this.configData.put(this.outputBuffers[result]);
                    String spsData = "";
                    for (int i = 0; i < ((info.size < 8) ? info.size : 8); ++i) {
                        spsData = spsData + Integer.toHexString(this.configData.get(i) & 0xFF) + " ";
                    }
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", spsData);
                    this.mediaCodec.releaseOutputBuffer(result, false);
                    result = this.mediaCodec.dequeueOutputBuffer(info, 0L);
                }
            }
            if (result >= 0) {
                final ByteBuffer outputBuffer = this.outputBuffers[result].duplicate();
                outputBuffer.position(info.offset);
                outputBuffer.limit(info.offset + info.size);
                this.reportEncodedFrame(info.size);
                final boolean isKeyFrame = (info.flags & 0x1) != 0x0;
                if (isKeyFrame) {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "Sync frame generated");
                }
                if (isKeyFrame && (this.type == VideoCodecType.VIDEO_CODEC_H264 || this.type == VideoCodecType.VIDEO_CODEC_H265)) {
                    org.webrtc.Logging.d("MediaCodecVideoEncoder", "Appending config frame of size " + this.configData.capacity() + " to output buffer with offset " + info.offset + ", size " + info.size);
                    final ByteBuffer keyFrameBuffer = ByteBuffer.allocateDirect(this.configData.capacity() + info.size);
                    this.configData.rewind();
                    keyFrameBuffer.put(this.configData);
                    keyFrameBuffer.put(outputBuffer);
                    keyFrameBuffer.position(0);
                    return new OutputBufferInfo(result, keyFrameBuffer, isKeyFrame, info.presentationTimeUs);
                }
                return new OutputBufferInfo(result, outputBuffer.slice(), isKeyFrame, info.presentationTimeUs);
            }
            else {
                if (result == -3) {
                    this.outputBuffers = this.mediaCodec.getOutputBuffers();
                    return this.dequeueOutputBuffer();
                }
                if (result == -2) {
                    return this.dequeueOutputBuffer();
                }
                if (result == -1) {
                    return null;
                }
                throw new RuntimeException("dequeueOutputBuffer: " + result);
            }
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "dequeueOutputBuffer failed", e);
            return new OutputBufferInfo(-1, null, false, -1L);
        }
    }
    
    private double getBitrateScale(final int bitrateAdjustmentScaleExp) {
        return Math.pow(4.0, bitrateAdjustmentScaleExp / 20.0);
    }
    
    private void reportEncodedFrame(final int size) {
        if (this.targetFps == 0 || this.bitrateAdjustmentType != BitrateAdjustmentType.DYNAMIC_ADJUSTMENT) {
            return;
        }
        final double expectedBytesPerFrame = this.targetBitrateBps / (8.0 * this.targetFps);
        this.bitrateAccumulator += size - expectedBytesPerFrame;
        this.bitrateObservationTimeMs += 1000.0 / this.targetFps;
        final double bitrateAccumulatorCap = 3.0 * this.bitrateAccumulatorMax;
        this.bitrateAccumulator = Math.min(this.bitrateAccumulator, bitrateAccumulatorCap);
        this.bitrateAccumulator = Math.max(this.bitrateAccumulator, -bitrateAccumulatorCap);
        if (this.bitrateObservationTimeMs > 3000.0) {
            org.webrtc.Logging.d("MediaCodecVideoEncoder", "Acc: " + (int)this.bitrateAccumulator + ". Max: " + (int)this.bitrateAccumulatorMax + ". ExpScale: " + this.bitrateAdjustmentScaleExp);
            boolean bitrateAdjustmentScaleChanged = false;
            if (this.bitrateAccumulator > this.bitrateAccumulatorMax) {
                final int bitrateAdjustmentInc = (int)(this.bitrateAccumulator / this.bitrateAccumulatorMax + 0.5);
                this.bitrateAdjustmentScaleExp -= bitrateAdjustmentInc;
                this.bitrateAccumulator = this.bitrateAccumulatorMax;
                bitrateAdjustmentScaleChanged = true;
            }
            else if (this.bitrateAccumulator < -this.bitrateAccumulatorMax) {
                final int bitrateAdjustmentInc = (int)(-this.bitrateAccumulator / this.bitrateAccumulatorMax + 0.5);
                this.bitrateAdjustmentScaleExp += bitrateAdjustmentInc;
                this.bitrateAccumulator = -this.bitrateAccumulatorMax;
                bitrateAdjustmentScaleChanged = true;
            }
            if (bitrateAdjustmentScaleChanged) {
                this.bitrateAdjustmentScaleExp = Math.min(this.bitrateAdjustmentScaleExp, 20);
                this.bitrateAdjustmentScaleExp = Math.max(this.bitrateAdjustmentScaleExp, -20);
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "Adjusting bitrate scale to " + this.bitrateAdjustmentScaleExp + ". Value: " + this.getBitrateScale(this.bitrateAdjustmentScaleExp));
                this.setRates(this.targetBitrateBps / 1000, this.targetFps);
            }
            this.bitrateObservationTimeMs = 0.0;
        }
    }
    
    @org.webrtc.CalledByNativeUnchecked
    boolean releaseOutputBuffer(final int index) {
        this.checkOnMediaCodecThread();
        try {
            this.mediaCodec.releaseOutputBuffer(index, false);
            return true;
        }
        catch (IllegalStateException e) {
            org.webrtc.Logging.e("MediaCodecVideoEncoder", "releaseOutputBuffer failed", e);
            return false;
        }
    }
    
    @org.webrtc.CalledByNative
    int getColorFormat() {
        return this.colorFormat;
    }
    
    @org.webrtc.CalledByNative
    static boolean isTextureBuffer(final org.webrtc.VideoFrame.Buffer buffer) {
        return buffer instanceof org.webrtc.VideoFrame.TextureBuffer;
    }
    
    private static native void nativeFillInputBuffer(final long p0, final int p1, final ByteBuffer p2, final int p3, final ByteBuffer p4, final int p5, final ByteBuffer p6, final int p7);
    
    private static native long nativeCreateEncoder(final org.webrtc.VideoCodecInfo p0, final boolean p1);
    
    static {
        MediaCodecVideoEncoder.runningInstance = null;
        MediaCodecVideoEncoder.errorCallback = null;
        MediaCodecVideoEncoder.codecErrors = 0;
        MediaCodecVideoEncoder.hwEncoderDisabledTypes = new HashSet<String>();
        qcomVp8HwProperties = new MediaCodecProperties("OMX.qcom.", 19, BitrateAdjustmentType.NO_ADJUSTMENT);
        exynosVp8HwProperties = new MediaCodecProperties("OMX.Exynos.", 23, BitrateAdjustmentType.DYNAMIC_ADJUSTMENT);
        intelVp8HwProperties = new MediaCodecProperties("OMX.Intel.", 21, BitrateAdjustmentType.NO_ADJUSTMENT);
        defaultVp8HwProperties = new MediaCodecProperties("OMX.", 19, BitrateAdjustmentType.NO_ADJUSTMENT);
        MediaCodecVideoEncoder.vp8HwCodecBlacklist = new String[] { "OMX.google." };
        qcomVp9HwProperties = new MediaCodecProperties("OMX.qcom.", 24, BitrateAdjustmentType.NO_ADJUSTMENT);
        exynosVp9HwProperties = new MediaCodecProperties("OMX.Exynos.", 24, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
        defaultVp9HwProperties = new MediaCodecProperties("OMX.", 23, BitrateAdjustmentType.NO_ADJUSTMENT);
        vp9HwList = new MediaCodecProperties[] { MediaCodecVideoEncoder.qcomVp9HwProperties, MediaCodecVideoEncoder.exynosVp9HwProperties, MediaCodecVideoEncoder.defaultVp9HwProperties };
        MediaCodecVideoEncoder.vp9HwCodecBlacklist = new String[] { "OMX.google." };
        qcomH264HwProperties = new MediaCodecProperties("OMX.qcom.", 19, BitrateAdjustmentType.NO_ADJUSTMENT);
        exynosH264HwProperties = new MediaCodecProperties("OMX.Exynos.", 21, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
		rkH264HwProperties = new MediaCodecProperties("OMX.rk.", 21, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
        googleH264HwProperties = new MediaCodecProperties("OMX.google.", 21, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
        mediatekH264HwProperties = new MediaCodecProperties("OMX.MTK.", 27, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
        defaultH264HwProperties = new MediaCodecProperties("OMX.", 19, BitrateAdjustmentType.NO_ADJUSTMENT);
        MediaCodecVideoEncoder.h264HwCodecBlacklist = new String[] { "OMX.google." };
        exynosH264HighProfileHwProperties = new MediaCodecProperties("OMX.Exynos.", 23, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
		rkH264HighProfileHwProperties = new MediaCodecProperties("OMX.rk.", 19, BitrateAdjustmentType.FRAMERATE_ADJUSTMENT);
        h264HighProfileHwList = new MediaCodecProperties[] { MediaCodecVideoEncoder.exynosH264HighProfileHwProperties, MediaCodecVideoEncoder.defaultH264HwProperties,rkH264HighProfileHwProperties };
        qcomH265HwProperties = new MediaCodecProperties("OMX.qcom.", 19, BitrateAdjustmentType.NO_ADJUSTMENT);
        h265HwList = new MediaCodecProperties[] { MediaCodecVideoEncoder.qcomH265HwProperties };
        MediaCodecVideoEncoder.h265HwCodecBlacklist = new String[] { "OMX.google." };
        H264_HW_EXCEPTION_MODELS = new String[] { "SAMSUNG-SGH-I337", "Nexus 7", "Nexus 4" };
        supportedColorList = new int[] { 19, 21, 2141391872, 2141391876 };
        supportedSurfaceColorList = new int[] { 2130708361 };
    }
    
    static class HwEncoderFactory implements org.webrtc.VideoEncoderFactory
    {
        private final org.webrtc.VideoCodecInfo[] supportedHardwareCodecs;
        
        HwEncoderFactory() {
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
            if (MediaCodecVideoEncoder.isVp8HwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "VP8 HW Encoder supported.");
                codecs.add(new org.webrtc.VideoCodecInfo("VP8", new HashMap<String, String>()));
            }
            if (MediaCodecVideoEncoder.isVp9HwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "VP9 HW Encoder supported.");
                codecs.add(new org.webrtc.VideoCodecInfo("VP9", new HashMap<String, String>()));
            }
            if (MediaCodecVideoDecoder.isH264HighProfileHwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "H.264 High Profile HW Encoder supported.");
                codecs.add(org.webrtc.H264Utils.DEFAULT_H264_HIGH_PROFILE_CODEC);
            }
            if (MediaCodecVideoEncoder.isH264HwSupported()) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "H.264 HW Encoder supported.");
                codecs.add(org.webrtc.H264Utils.DEFAULT_H264_BASELINE_PROFILE_CODEC);
            }
            return codecs.toArray(new org.webrtc.VideoCodecInfo[codecs.size()]);
        }
        
        @Override
        public org.webrtc.VideoCodecInfo[] getSupportedCodecs() {
            return this.supportedHardwareCodecs;
        }
        
        
        @Override
        public org.webrtc.VideoEncoder createEncoder(final org.webrtc.VideoCodecInfo info) {
            if (!isCodecSupported(this.supportedHardwareCodecs, info)) {
                org.webrtc.Logging.d("MediaCodecVideoEncoder", "No HW video encoder for codec " + info.name);
                return null;
            }
            org.webrtc.Logging.d("MediaCodecVideoEncoder", "Create HW video encoder for " + info.name);
            return new org.webrtc.WrappedNativeVideoEncoder() {
                @Override
                public long createNativeVideoEncoder() {
                    return nativeCreateEncoder(info, MediaCodecVideoEncoder.staticEglBase instanceof org.webrtc.EglBase14);
                }
                
                @Override
                public boolean isHardwareEncoder() {
                    return true;
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
    
    public enum BitrateAdjustmentType
    {
        NO_ADJUSTMENT, 
        FRAMERATE_ADJUSTMENT, 
        DYNAMIC_ADJUSTMENT;
    }
    
    public enum H264Profile
    {
        CONSTRAINED_BASELINE(0), 
        BASELINE(1), 
        MAIN(2), 
        CONSTRAINED_HIGH(3), 
        HIGH(4);
        
        private final int value;
        
        private H264Profile(final int value) {
            this.value = value;
        }
        
        public int getValue() {
            return this.value;
        }
    }
    
    private static class MediaCodecProperties
    {
        public final String codecPrefix;
        public final int minSdk;
        public final BitrateAdjustmentType bitrateAdjustmentType;
        
        MediaCodecProperties(final String codecPrefix, final int minSdk, final BitrateAdjustmentType bitrateAdjustmentType) {
            this.codecPrefix = codecPrefix;
            this.minSdk = minSdk;
            this.bitrateAdjustmentType = bitrateAdjustmentType;
        }
    }
    
    public static class EncoderProperties
    {
        public final String codecName;
        public final int colorFormat;
        public final BitrateAdjustmentType bitrateAdjustmentType;
        
        public EncoderProperties(final String codecName, final int colorFormat, final BitrateAdjustmentType bitrateAdjustmentType) {
            this.codecName = codecName;
            this.colorFormat = colorFormat;
            this.bitrateAdjustmentType = bitrateAdjustmentType;
        }
    }
    
    static class OutputBufferInfo
    {
        public final int index;
        public final ByteBuffer buffer;
        public final boolean isKeyFrame;
        public final long presentationTimestampUs;
        
        public OutputBufferInfo(final int index, final ByteBuffer buffer, final boolean isKeyFrame, final long presentationTimestampUs) {
            this.index = index;
            this.buffer = buffer;
            this.isKeyFrame = isKeyFrame;
            this.presentationTimestampUs = presentationTimestampUs;
        }
        
        @org.webrtc.CalledByNative("OutputBufferInfo")
        int getIndex() {
            return this.index;
        }
        
        @org.webrtc.CalledByNative("OutputBufferInfo")
        ByteBuffer getBuffer() {
            return this.buffer;
        }
        
        @org.webrtc.CalledByNative("OutputBufferInfo")
        boolean isKeyFrame() {
            return this.isKeyFrame;
        }
        
        @org.webrtc.CalledByNative("OutputBufferInfo")
        long getPresentationTimestampUs() {
            return this.presentationTimestampUs;
        }
    }
    
    public interface MediaCodecVideoEncoderErrorCallback
    {
        void onMediaCodecVideoEncoderCriticalError(final int p0);
    }
}
