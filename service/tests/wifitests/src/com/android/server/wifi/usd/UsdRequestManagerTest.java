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

package com.android.server.wifi.usd;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.AlarmManager;
import android.net.wifi.usd.Characteristics;
import android.net.wifi.usd.Config;
import android.net.wifi.usd.IPublishSessionCallback;
import android.net.wifi.usd.ISubscribeSessionCallback;
import android.net.wifi.usd.PublishConfig;
import android.net.wifi.usd.SubscribeConfig;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.Clock;
import com.android.server.wifi.SupplicantStaIfaceHal;
import com.android.server.wifi.SupplicantStaIfaceHal.UsdCapabilitiesInternal;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiThreadRunner;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test for {@link UsdRequestManager}.
 */
@SmallTest
public class UsdRequestManagerTest extends WifiBaseTest {
    private UsdRequestManager mUsdRequestManager;
    private UsdNativeManager mUsdNativeManager;
    @Mock
    private WifiThreadRunner mWifiThreadRunner;
    private static final String USD_INTERFACE_NAME = "wlan0";
    private static final int USD_REQUEST_COMMAND_ID = 100;
    private static final String USD_TEST_SERVICE_NAME = "UsdTest";
    private static final int USD_TEST_PERIOD_MILLIS = 200;
    private static final int USD_TTL_SEC = 3000;
    @Mock
    private Clock mClock;
    @Mock
    ISubscribeSessionCallback mSubscribeSessionCallback;
    @Mock
    IPublishSessionCallback mPublishSessionCallback;

    private SupplicantStaIfaceHal.UsdCapabilitiesInternal mUsdCapabilities;
    @Mock
    private WifiNative mWifiNative;
    @Mock
    private IBinder mAppBinder;
    private InOrder mInOrderAppBinder;
    @Mock
    private AlarmManager mAlarmManager;
    private byte[] mSsi = new byte[]{1, 2, 3};
    private int[] mFreqs = new int[]{2437};
    private List<byte[]> mFilter;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mUsdCapabilities = getMockUsdCapabilities();
        mUsdNativeManager = new UsdNativeManager(mWifiNative);
        when(mWifiNative.getUsdCapabilities()).thenReturn(mUsdCapabilities);
        mUsdRequestManager = new UsdRequestManager(mUsdNativeManager, mWifiThreadRunner,
                USD_INTERFACE_NAME, mClock, mAlarmManager);
        UsdCapabilitiesInternal mockUsdCapabilities = getMockUsdCapabilities();
        mFilter = new ArrayList<>();
        mFilter.add(new byte[]{10, 11});
        mFilter.add(new byte[]{12, 13, 14});
        mInOrderAppBinder = inOrder(mAppBinder);
        when(mUsdNativeManager.getUsdCapabilities()).thenReturn(mockUsdCapabilities);

    }

    private UsdCapabilitiesInternal getMockUsdCapabilities() {
        return new UsdCapabilitiesInternal(true, true, 1024, 255,
                255, 1, 1);
    }

    /**
     * Test {@link UsdRequestManager#getCharacteristics()}.
     */
    @Test
    public void testUsdGetCharacteristics() {
        Characteristics characteristics = mUsdRequestManager.getCharacteristics();
        assertEquals(mUsdCapabilities.maxNumSubscribeSessions,
                characteristics.getMaxNumberOfSubscribeSessions());
        assertEquals(mUsdCapabilities.maxNumPublishSessions,
                characteristics.getMaxNumberOfPublishSessions());
        assertEquals(mUsdCapabilities.maxServiceNameLengthBytes,
                characteristics.getMaxServiceNameLength());
        assertEquals(mUsdCapabilities.maxMatchFilterLengthBytes,
                characteristics.getMaxMatchFilterLength());
        assertEquals(mUsdCapabilities.maxLocalSsiLengthBytes,
                characteristics.getMaxServiceSpecificInfoLength());
    }

    /**
     * Test USD subscribe.
     */
    @Test
    public void testUsdSubscribe() throws RemoteException {
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder(USD_TEST_SERVICE_NAME)
                .setQueryPeriodMillis(USD_TEST_PERIOD_MILLIS)
                .setOperatingFrequenciesMhz(mFreqs)
                .setRxMatchFilter(mFilter)
                .setTxMatchFilter(mFilter)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
                .setServiceSpecificInfo(mSsi)
                .setServiceProtoType(Config.SERVICE_PROTO_TYPE_CSA_MATTER)
                .setQueryPeriodMillis(USD_TEST_PERIOD_MILLIS)
                .setTtlSeconds(USD_TTL_SEC).build();
        when(mSubscribeSessionCallback.asBinder()).thenReturn(mAppBinder);
        when(mWifiNative.startUsdSubscribe(USD_INTERFACE_NAME, USD_REQUEST_COMMAND_ID,
                subscribeConfig)).thenReturn(true);
        mUsdRequestManager.subscribe(subscribeConfig, mSubscribeSessionCallback);
        mInOrderAppBinder.verify(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());
        verify(mWifiNative).startUsdSubscribe(USD_INTERFACE_NAME, USD_REQUEST_COMMAND_ID,
                subscribeConfig);
    }

    /**
     * Test USD publish.
     */
    @Test
    public void testUsdPublish() throws RemoteException {
        PublishConfig publishConfig = new PublishConfig.Builder(USD_TEST_SERVICE_NAME)
                .setAnnouncementPeriodMillis(USD_TEST_PERIOD_MILLIS)
                .setEventsEnabled(true)
                .setOperatingFrequenciesMhz(mFreqs)
                .setRxMatchFilter(mFilter)
                .setTxMatchFilter(mFilter)
                .setServiceProtoType(Config.SERVICE_PROTO_TYPE_CSA_MATTER)
                .setServiceSpecificInfo(mSsi)
                .setSolicitedTransmissionType(Config.TRANSMISSION_TYPE_UNICAST)
                .setTtlSeconds(USD_TTL_SEC)
                .build();
        when(mPublishSessionCallback.asBinder()).thenReturn(mAppBinder);
        when(mWifiNative.startUsdPublish(USD_INTERFACE_NAME, USD_REQUEST_COMMAND_ID,
                publishConfig)).thenReturn(true);
        mUsdRequestManager.publish(publishConfig, mPublishSessionCallback);
        mInOrderAppBinder.verify(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());
        verify(mWifiNative).startUsdPublish(USD_INTERFACE_NAME, USD_REQUEST_COMMAND_ID,
                publishConfig);
    }
}
