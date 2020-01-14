package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HardwareVideoEncoderFactory implements VideoEncoderFactory
{
    private static final String TAG = "HardwareVideoEncoderFactory";
    private static final int QCOM_VP8_KEY_FRAME_INTERVAL_ANDROID_L_MS = 15000;
    private static final int QCOM_VP8_KEY_FRAME_INTERVAL_ANDROID_M_MS = 20000;
    private static final int QCOM_VP8_KEY_FRAME_INTERVAL_ANDROID_N_MS = 15000;
    private static final List<String> H264_HW_EXCEPTION_MODELS;

    private final EglBase14.Context sharedContext;
    private final boolean enableIntelVp8Encoder;
    private final boolean enableH264HighProfile;

    public HardwareVideoEncoderFactory(final EglBase.Context sharedContext, final boolean enableIntelVp8Encoder, final boolean enableH264HighProfile) {
        if (sharedContext instanceof EglBase14.Context) {
            this.sharedContext = (EglBase14.Context)sharedContext;
        }
        else {
            Logging.w("HardwareVideoEncoderFactory", "No shared EglBase.Context.  Encoders will not use texture mode.");
            this.sharedContext = null;
        }
        this.enableIntelVp8Encoder = enableIntelVp8Encoder;
        this.enableH264HighProfile = enableH264HighProfile;
    }

    @Deprecated
    public HardwareVideoEncoderFactory(final boolean enableIntelVp8Encoder, final boolean enableH264HighProfile) {
        this(null, enableIntelVp8Encoder, enableH264HighProfile);
    }

    @Override
    public VideoEncoder createEncoder(final VideoCodecInfo input) {
        final VideoCodecType type = VideoCodecType.valueOf(input.name);
        final MediaCodecInfo info = this.findCodecForType(type);
        if (info == null) {
            return null;
        }
        final String codecName = info.getName();
        final String mime = type.mimeType();
        final Integer surfaceColorFormat = MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, info.getCapabilitiesForType(mime));
        final Integer yuvColorFormat = MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, info.getCapabilitiesForType(mime));
        if (type == VideoCodecType.H264) {
            final boolean isHighProfile = H264Utils.isSameH264Profile(input.params, MediaCodecUtils.getCodecProperties(type, true));
            final boolean isBaselineProfile = H264Utils.isSameH264Profile(input.params, MediaCodecUtils.getCodecProperties(type, false));
            if (!isHighProfile && !isBaselineProfile) {
                return null;
            }
            if (isHighProfile && !this.isH264HighProfileSupported(info)) {
                return null;
            }
        }
        return new HardwareVideoEncoder(new MediaCodecWrapperFactoryImpl(), codecName, type, surfaceColorFormat, yuvColorFormat, input.params, this.getKeyFrameIntervalSec(type), this.getForcedKeyFrameIntervalMs(type, codecName), this.createBitrateAdjuster(type, codecName), this.sharedContext);
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        final List<VideoCodecInfo> supportedCodecInfos = new ArrayList<VideoCodecInfo>();
        for (final VideoCodecType type : new VideoCodecType[] { VideoCodecType.VP8, VideoCodecType.VP9, VideoCodecType.H264, VideoCodecType.H265 }) {
            final MediaCodecInfo codec = this.findCodecForType(type);
            if (codec != null) {
                final String name = type.name();
                if (type == VideoCodecType.H264 && this.isH264HighProfileSupported(codec)) {
                    supportedCodecInfos.add(new VideoCodecInfo(name, MediaCodecUtils.getCodecProperties(type, true)));
                }
                supportedCodecInfos.add(new VideoCodecInfo(name, MediaCodecUtils.getCodecProperties(type, false)));
            }
        }
        return supportedCodecInfos.toArray(new VideoCodecInfo[supportedCodecInfos.size()]);
    }

    private MediaCodecInfo findCodecForType(final VideoCodecType type) {
        for (int i = 0; i < MediaCodecList.getCodecCount(); ++i) {
            MediaCodecInfo info = null;
            try {
                info = MediaCodecList.getCodecInfoAt(i);
            }
            catch (IllegalArgumentException e) {
                Logging.e("HardwareVideoEncoderFactory", "Cannot retrieve encoder codec info", e);
            }
            if (info != null) {
                if (info.isEncoder()) {
                    if (this.isSupportedCodec(info, type)) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSupportedCodec(final MediaCodecInfo info, final VideoCodecType type) {
        return MediaCodecUtils.codecSupportsType(info, type) && MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, info.getCapabilitiesForType(type.mimeType())) != null && this.isHardwareSupportedInCurrentSdk(info, type);
    }

    private boolean isHardwareSupportedInCurrentSdk(final MediaCodecInfo info, final VideoCodecType type) {
        switch (type) {
            case VP8: {
                return this.isHardwareSupportedInCurrentSdkVp8(info);
            }
            case VP9: {
                return this.isHardwareSupportedInCurrentSdkVp9(info);
            }
            case H264: {
                return this.isHardwareSupportedInCurrentSdkH264(info);
            }
            case H265: {
                return this.isHardwareSupportedInCurrentSdkH265(info);
            }
            default: {
                return false;
            }
        }
    }

    private boolean isHardwareSupportedInCurrentSdkVp8(final MediaCodecInfo info) {
        final String name = info.getName();
        return (name.startsWith("OMX.qcom.") && Build.VERSION.SDK_INT >= 19) || (name.startsWith("OMX.Exynos.") && Build.VERSION.SDK_INT >= 23) || (name.startsWith("OMX.Intel.") && Build.VERSION.SDK_INT >= 21 && this.enableIntelVp8Encoder) || (name.startsWith("OMX.hisi.") && Build.VERSION.SDK_INT >= 19);
    }

    private boolean isHardwareSupportedInCurrentSdkVp9(final MediaCodecInfo info) {
        final String name = info.getName();
        return (name.startsWith("OMX.qcom.") || name.startsWith("OMX.Exynos.") || name.startsWith("OMX.hisi.")) && Build.VERSION.SDK_INT >= 24;
    }

    private boolean isHardwareSupportedInCurrentSdkH264(final MediaCodecInfo info) {
        if (HardwareVideoEncoderFactory.H264_HW_EXCEPTION_MODELS.contains(Build.MODEL)) {
            return false;
        }
        final String name = info.getName();
        return (name.startsWith("OMX.qcom.") && Build.VERSION.SDK_INT >= 19)
                || (name.startsWith("OMX.Exynos.") && Build.VERSION.SDK_INT >= 21)
                || (name.startsWith("OMX.hisi.") && Build.VERSION.SDK_INT >= 19)
                || (name.startsWith("OMX.rk.") && Build.VERSION.SDK_INT >= 21)
                || (name.startsWith("OMX.google.") && Build.VERSION.SDK_INT >= 21)
                ;
    }

    private boolean isHardwareSupportedInCurrentSdkH265(final MediaCodecInfo info) {
        return this.isHardwareSupportedInCurrentSdkH264(info);
    }

    private int getKeyFrameIntervalSec(final VideoCodecType type) {
        switch (type) {
            case VP8:
            case VP9: {
                return 100;
            }
            case H264: {
                return 20;
            }
            case H265: {
                return 20;
            }
            default: {
                throw new IllegalArgumentException("Unsupported VideoCodecType " + type);
            }
        }
    }

    private int getForcedKeyFrameIntervalMs(final VideoCodecType type, final String codecName) {
        if (type == VideoCodecType.VP8 && codecName.startsWith("OMX.qcom.")) {
            if (Build.VERSION.SDK_INT == 21 || Build.VERSION.SDK_INT == 22) {
                return 15000;
            }
            if (Build.VERSION.SDK_INT == 23) {
                return 20000;
            }
            if (Build.VERSION.SDK_INT > 23) {
                return 15000;
            }
        }
        return 0;
    }

    private BitrateAdjuster createBitrateAdjuster(final VideoCodecType type, final String codecName) {
        if (!codecName.startsWith("OMX.Exynos.")) {
            return new BaseBitrateAdjuster();
        }
        if (type == VideoCodecType.VP8) {
            return new DynamicBitrateAdjuster();
        }
        return new FramerateBitrateAdjuster();
    }

    private boolean isH264HighProfileSupported(final MediaCodecInfo info) {
        return this.enableH264HighProfile && Build.VERSION.SDK_INT > 23 && info.getName().startsWith("OMX.Exynos.");
    }

    static {
        H264_HW_EXCEPTION_MODELS = Arrays.asList("SAMSUNG-SGH-I337", "Nexus 7", "Nexus 4");
    }
}
