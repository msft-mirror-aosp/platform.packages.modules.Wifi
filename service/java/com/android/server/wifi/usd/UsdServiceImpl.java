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

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.usd.IUsdManager;
import android.net.wifi.usd.UsdManager;
import android.os.Binder;
import android.util.Log;

import com.android.server.wifi.RunnerHandler;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.util.WifiPermissionsUtil;

/**
 * Implementation of the IUsdManager.
 */
public class UsdServiceImpl extends IUsdManager.Stub {
    private static final String TAG = UsdServiceImpl.class.getName();
    private final Context mContext;
    private RunnerHandler mHandler;
    private WifiInjector mWifiInjector;
    WifiPermissionsUtil mWifiPermissionsUtil;


    /**
     * Constructor
     */
    public UsdServiceImpl(Context context) {
        mContext = context;
    }

    /**
     * Start the service
     */
    public void start(@NonNull WifiInjector wifiInjector) {
        mWifiInjector = wifiInjector;
        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        Log.i(TAG, "start");
    }

    /**
     * Start/initialize portions of the service which require the boot stage to be complete.
     */
    public void startLate() {
        Log.i(TAG, "startLate");
    }

    /**
     * Proxy for the final native call of the parent class. Enables mocking of
     * the function.
     */
    public int getMockableCallingUid() {
        return Binder.getCallingUid();
    }

    /**
     * See {@link UsdManager#isSubscriberSupported()}
     */
    @Override
    public boolean isSubscriberSupported() {
        int uid = getMockableCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("App not allowed to use USD (uid = " + uid + ")");
        }
        // Subscriber is not supported.
        return false;
    }

    /**
     * See {@link UsdManager#isPublisherSupported()}
     */
    @Override
    public boolean isPublisherSupported() {
        int uid = getMockableCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("App not allowed to use USD (uid = " + uid + ")");
        }
        // Publisher is not supported.
        return false;
    }

}
