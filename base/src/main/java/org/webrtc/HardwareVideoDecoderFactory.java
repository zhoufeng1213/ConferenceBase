package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

public class HardwareVideoDecoderFactory implements VideoDecoderFactory
{
    private static final String TAG = "HardwareVideoDecoderFactory";
    private final EglBase.Context sharedContext;

    @Deprecated
    public HardwareVideoDecoderFactory() {
        this(null);
    }

    public HardwareVideoDecoderFactory(final EglBase.Context sharedContext) {
        this.sharedContext = sharedContext;
    }

    @Override
    public VideoDecoder createDecoder(final VideoCodecInfo codecType) {
        final VideoCodecType type = VideoCodecType.valueOf(codecType.getName());
        final MediaCodecInfo info = this.findCodecForType(type);
        if (info == null) {
            return null;
        }
        final MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(type.mimeType());
        return new HardwareVideoDecoder(new MediaCodecWrapperFactoryImpl(), info.getName(), type, MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, capabilities), this.sharedContext);
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
        if (Build.VERSION.SDK_INT < 19) {
            return null;
        }
        for (int i = 0; i < MediaCodecList.getCodecCount(); ++i) {
            MediaCodecInfo info = null;
            try {
                info = MediaCodecList.getCodecInfoAt(i);
            }
            catch (IllegalArgumentException e) {
                Logging.e("HardwareVideoDecoderFactory", "Cannot retrieve encoder codec info", e);
            }
            if (info != null) {
                if (!info.isEncoder()) {
                    if (this.isSupportedCodec(info, type)) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSupportedCodec(final MediaCodecInfo info, final VideoCodecType type) {
        return MediaCodecUtils.codecSupportsType(info, type) && MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, info.getCapabilitiesForType(type.mimeType())) != null && this.isHardwareSupported(info, type);
    }

    private boolean isHardwareSupported(final MediaCodecInfo info, final VideoCodecType type) {
        final String name = info.getName();
        switch (type) {
            case VP8: {
                return name.startsWith("OMX.qcom.") || name.startsWith("OMX.Intel.") || name.startsWith("OMX.Exynos.") || name.startsWith("OMX.Nvidia.") || name.startsWith("OMX.hisi.");
            }
            case VP9: {
                return name.startsWith("OMX.qcom.") || name.startsWith("OMX.Exynos.") || name.startsWith("OMX.hisi.");
            }
            case H264: {
                return name.startsWith("OMX.qcom.")
                        || name.startsWith("OMX.Intel.")
                        || name.startsWith("OMX.Exynos.")
                        || name.startsWith("OMX.hisi.")
                        || name.startsWith("OMX.rk.")
                        ;
            }
            case H265: {
                return name.startsWith("OMX.qcom.") || name.startsWith("OMX.Intel.") || name.startsWith("OMX.Exynos.") || name.startsWith("OMX.hisi.");
            }
            default: {
                return false;
            }
        }
    }

    private boolean isH264HighProfileSupported(final MediaCodecInfo info) {
        final String name = info.getName();
        return (Build.VERSION.SDK_INT >= 21 && name.startsWith("OMX.qcom."))
                || (Build.VERSION.SDK_INT >= 23 && name.startsWith("OMX.Exynos."))
                || (Build.VERSION.SDK_INT >= 21 && name.startsWith("OMX.rk."))
                ;
    }
}
