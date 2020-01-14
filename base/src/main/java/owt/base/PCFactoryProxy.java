/*
 * Copyright (C) 2018 Intel Corporation
 * SPDX-License-Identifier: Apache-2.0
 */
package owt.base;

import android.annotation.SuppressLint;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import static owt.base.ContextInitialization.context;
import static owt.base.ContextInitialization.localContext;
import static owt.base.ContextInitialization.remoteContext;

//import org.webrtc.audio.LegacyAudioDeviceModule;

final class PCFactoryProxy {
    static int networkIgnoreMask = 0;
    // Enable H.264 high profile by default.
    static String fieldTrials = "/WebRTC-H264HighProfile/Enabled/";

    static VideoEncoderFactory encoderFactory = null;
    static VideoDecoderFactory decoderFactory = null;
    static AudioDeviceModule adm = null;
    @SuppressLint("StaticFieldLeak")
    private static PeerConnectionFactory peerConnectionFactory;

    static PeerConnectionFactory instance() {
        if (peerConnectionFactory == null) {
            PeerConnectionFactory.InitializationOptions initializationOptions =
                    PeerConnectionFactory.InitializationOptions.builder(context)
                            .setFieldTrials(fieldTrials)
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(initializationOptions);
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.networkIgnoreMask = networkIgnoreMask;
            options.disableNetworkMonitor = true;

            adm = JavaAudioDeviceModule.builder(context)
                    .setSamplesReadyCallback(null)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .setAudioRecordErrorCallback(null)
                    .setAudioTrackErrorCallback(null)
                    .createAudioDeviceModule();

            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(adm)
                    .setVideoEncoderFactory(
                            encoderFactory == null
                                    ? new DefaultVideoEncoderFactory(localContext, true, true)
                                    : encoderFactory)
                    .setVideoDecoderFactory(
                            decoderFactory == null
                                    ? new DefaultVideoDecoderFactory(remoteContext)
                                    : decoderFactory)
                    .createPeerConnectionFactory();
        }
        return peerConnectionFactory;
    }
}
