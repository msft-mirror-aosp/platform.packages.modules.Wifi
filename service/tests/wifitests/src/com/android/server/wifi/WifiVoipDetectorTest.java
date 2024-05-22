/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wifi;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.test.TestLooper;
import android.telephony.CallAttributes;
import android.telephony.CallQuality;
import android.telephony.PreciseCallState;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.hal.WifiChip;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiVoipDetectorTest}.
 */
@SmallTest
public class WifiVoipDetectorTest extends WifiBaseTest {

    private static final CallAttributes TEST_VOWIFI_CALL_ATT =
            new CallAttributes(new PreciseCallState(),
                    TelephonyManager.NETWORK_TYPE_IWLAN, new CallQuality());
    private static final CallAttributes TEST_LTE_CALL_ATT =
            new CallAttributes(new PreciseCallState(),
                    TelephonyManager.NETWORK_TYPE_LTE, new CallQuality());

    private static final String TEST_PRIMARY_INTERFACE_NAME = "wlan0";
    private static final String TEST_SECONDARY_INTERFACE_NAME = "wlan1";

    @Mock private Context mContext;
    @Mock private WifiInjector mWifiInjector;
    @Mock private AudioManager mAudioManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private WifiNative mWifiNative;
    @Mock private WifiCarrierInfoManager mWifiCarrierInfoManager;

    private WifiVoipDetector mWifiVoipDetector;
    private TestLooper mLooper;
    private ArgumentCaptor<WifiVoipDetector.WifiCallingStateListener> mTelephonyCallbackCaptor;
    private ArgumentCaptor<AudioManager.OnModeChangedListener> mAudioModeChangedListeneCaptor;

    @Before
    public void setUp() throws Exception {
        assumeTrue(SdkLevel.isAtLeastV());
        MockitoAnnotations.initMocks(this);
        mTelephonyCallbackCaptor =
                ArgumentCaptor.forClass(WifiVoipDetector.WifiCallingStateListener.class);
        mAudioModeChangedListeneCaptor =
                ArgumentCaptor.forClass(AudioManager.OnModeChangedListener.class);
        mLooper = new TestLooper();
        when(mWifiInjector.makeTelephonyManager()).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(AudioManager.class)).thenReturn(mAudioManager);
        when(mWifiInjector.getWifiNative()).thenReturn(mWifiNative);
        when(mWifiNative.setVoipMode(anyInt())).thenReturn(true);
        mWifiVoipDetector = new WifiVoipDetector(mContext,
                new Handler(mLooper.getLooper()), mWifiInjector, mWifiCarrierInfoManager);
    }

    private void resetWifiNativeAndReSetupforMock() {
        reset(mWifiNative);
        when(mWifiNative.setVoipMode(anyInt())).thenReturn(true);
    }

    @Test
    public void testNotifyWifiConnectedDisconnectedInPrimaryClientMode() {
        assumeTrue(SdkLevel.isAtLeastV());
        mWifiVoipDetector.notifyWifiConnected(true, true, TEST_PRIMARY_INTERFACE_NAME);
        verify(mTelephonyManager).registerTelephonyCallback(any(),
                mTelephonyCallbackCaptor.capture());
        verify(mAudioManager).addOnModeChangedListener(any(),
                mAudioModeChangedListeneCaptor.capture());
        // Init should do nothing
        verify(mWifiNative, never()).setVoipMode(anyInt());
        // deinit should do nothing
        mWifiVoipDetector.notifyWifiConnected(false, true, TEST_PRIMARY_INTERFACE_NAME);
        verify(mWifiNative, never()).setVoipMode(anyInt());
        // Init again when VoIP call is on
        when(mWifiCarrierInfoManager.isWifiCallingAvailable()).thenReturn(true);
        mWifiVoipDetector.notifyWifiConnected(true, true, TEST_PRIMARY_INTERFACE_NAME);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_VOICE);
        // Test VoWifi call off -> switch to VoLte
        mTelephonyCallbackCaptor.getValue().onCallAttributesChanged(TEST_LTE_CALL_ATT);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_OFF);
        resetWifiNativeAndReSetupforMock();
        // Test VoWifi Call
        mTelephonyCallbackCaptor.getValue().onCallAttributesChanged(TEST_VOWIFI_CALL_ATT);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_VOICE);
        resetWifiNativeAndReSetupforMock();
        // Test VoWifi call off -> switch to VoLte
        mTelephonyCallbackCaptor.getValue().onCallAttributesChanged(TEST_LTE_CALL_ATT);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_OFF);
        resetWifiNativeAndReSetupforMock();
        // Test MODE_IN_COMMUNICATION to trigger voice mode
        mAudioModeChangedListeneCaptor.getValue().onModeChanged(AudioManager.MODE_IN_COMMUNICATION);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_VOICE);
        resetWifiNativeAndReSetupforMock();
        // Test MODE_NORMAL off WifiChip.WIFI_VOIP_MODE_OFF
        mAudioModeChangedListeneCaptor.getValue().onModeChanged(AudioManager.MODE_NORMAL);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_OFF);
        resetWifiNativeAndReSetupforMock();
        // Test MODE_COMMUNICATION_REDIRECT  to trigger voice mode
        mAudioModeChangedListeneCaptor.getValue().onModeChanged(
                AudioManager.MODE_COMMUNICATION_REDIRECT);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_VOICE);
        resetWifiNativeAndReSetupforMock();
        // Do nothing when mode change between two OTT modes.
        mAudioModeChangedListeneCaptor.getValue().onModeChanged(AudioManager.MODE_IN_COMMUNICATION);
        verify(mWifiNative, never()).setVoipMode(anyInt());
        mWifiVoipDetector.notifyWifiConnected(false, true, TEST_PRIMARY_INTERFACE_NAME);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_OFF);
        // Test OTT on when wifi is disconnected, should do nothing since it doesn't relate to wifi.
        resetWifiNativeAndReSetupforMock();
        mAudioModeChangedListeneCaptor.getValue().onModeChanged(
                AudioManager.MODE_COMMUNICATION_REDIRECT);
        verify(mWifiNative, never()).setVoipMode(anyInt());
        // Wifi is connected and trigger Voice mode.
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_COMMUNICATION_REDIRECT);
        mWifiVoipDetector.notifyWifiConnected(true, true, TEST_PRIMARY_INTERFACE_NAME);
        verify(mWifiNative).setVoipMode(WifiChip.WIFI_VOIP_MODE_VOICE);
    }

    @Test
    public void testNotifyWifiConnectedDisconnectedInSecondaryClientMode() {
        assumeTrue(SdkLevel.isAtLeastV());
        mWifiVoipDetector.notifyWifiConnected(true, false, TEST_SECONDARY_INTERFACE_NAME);
        verify(mTelephonyManager, never()).registerTelephonyCallback(any(),
                mTelephonyCallbackCaptor.capture());
        verify(mAudioManager, never()).addOnModeChangedListener(any(),
                mAudioModeChangedListeneCaptor.capture());
        verify(mWifiNative, never()).setVoipMode(anyInt());
    }

    @Test
    public void testNotifyWifiConnectedDisconnectedInPrimarySecondaryClientModeSwitch() {
        assumeTrue(SdkLevel.isAtLeastV());
        assumeTrue(SdkLevel.isAtLeastV());
        mWifiVoipDetector.notifyWifiConnected(true, true, TEST_PRIMARY_INTERFACE_NAME);
        verify(mTelephonyManager).registerTelephonyCallback(any(),
                mTelephonyCallbackCaptor.capture());
        verify(mAudioManager).addOnModeChangedListener(any(),
                mAudioModeChangedListeneCaptor.capture());
        verify(mWifiNative, never()).setVoipMode(anyInt());

        mWifiVoipDetector.notifyWifiConnected(true, false, TEST_SECONDARY_INTERFACE_NAME);
        mWifiVoipDetector.notifyWifiConnected(false, false, TEST_SECONDARY_INTERFACE_NAME);
        verify(mTelephonyManager, never()).unregisterTelephonyCallback(any());
        verify(mAudioManager, never()).removeOnModeChangedListener(any());
        // MBB use case
        // Secondary network is connected.
        mWifiVoipDetector.notifyWifiConnected(true, false, TEST_SECONDARY_INTERFACE_NAME);
        // Primary switch role to secondary and disconnected
        mWifiVoipDetector.notifyWifiConnected(false, false, TEST_PRIMARY_INTERFACE_NAME);
        verify(mTelephonyManager, never()).unregisterTelephonyCallback(any());
        verify(mAudioManager, never()).removeOnModeChangedListener(any());
        // Secondary switch role to primary and disconnected
        mWifiVoipDetector.notifyWifiConnected(false, true, TEST_SECONDARY_INTERFACE_NAME);
        // Verify unregister
        verify(mTelephonyManager).unregisterTelephonyCallback(any());
        verify(mAudioManager).removeOnModeChangedListener(any());
    }
}
