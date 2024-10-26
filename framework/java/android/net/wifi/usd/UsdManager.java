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

package android.net.wifi.usd;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.net.wifi.flags.Flags;
import android.os.Build;

/**
 * This class provides the APIs for managing Unsynchronized Service Discovery (USD). USD is a
 * mechanism that allows devices to discover services offered by other devices without requiring
 * prior time and channel synchronization. This feature is especially useful for quickly finding
 * services on new devices entering the range.
 *
 * <p>A publisher device makes its services discoverable, and a subscriber device actively
 * or passively searches for those services. Publishers in USD operate continuously, switching
 * between single and multiple channel states to advertise their services. When a subscriber
 * device receives a relevant service advertisement, it sends a follow-up message to the
 * publisher, temporarily pausing the publisher on its current channel to facilitate further
 * communication.
 *
 * <p>Once the discovery of device and service is complete, the subscriber and publisher perform
 * further service discovery in which they exchange follow-up messages. The follow-up messages
 * carry the service specific information useful for device and service configuration.
 *
 * <p>Note: This implementation adhere with Wi-Fi Aware Specification Version 4.0.
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@SystemService(Context.WIFI_USD_SERVICE)
@SystemApi
@FlaggedApi(Flags.FLAG_USD)
public class UsdManager {
    private final Context mContext;
    private final IUsdManager mService;
    private static final String TAG = UsdManager.class.getName();

    /** @hide */
    public UsdManager(@NonNull Context context, @NonNull IUsdManager service) {
        mContext = context;
        mService = service;
    }
}
