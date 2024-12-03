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

package android.system.wifi.mainline_supplicant;

import android.system.wifi.mainline_supplicant.UsdMessageInfo;
import android.system.wifi.mainline_supplicant.UsdServiceProtoType;

/**
 * Callbacks for Unsynchronized Service Discovery (USD) operations.
 */
interface IUsdCallback {
    /**
     * Information about a USD discovery session with a specific peer.
     */
    @VintfStability
    parcelable UsdServiceDiscoveryInfo {
        /**
         * Identifier for this device.
         */
        int ownId;

        /**
         * Identifier for the discovered peer device.
         */
        int peerId;

        /**
         * MAC address of the discovered peer device.
         */
        byte[6] peerMacAddress;

        /**
         * Match filter from the discovery packet (publish or subscribe) which caused service
         * discovery.
         */
        byte[] matchFilter;

        /**
         * Service protocol that is being used (ex. Generic, CSA Matter).
         */
        UsdServiceProtoType serviceProtoType;

        /**
         * Arbitrary service specific information communicated in discovery packets.
         * There is no semantic meaning to these bytes. They are passed-through from publisher to
         * subscriber as-is with no parsing.
         */
        byte[] serviceSpecificInfo;

        /**
         * Whether Further Service Discovery (FSD) is enabled.
         */
        boolean isFsd;
    }

    /**
     * Codes indicating the status of USD operations.
     */
    @Backing(type="int")
    enum UsdReasonCode {
        /**
         * Unknown failure occurred.
         */
        FAILURE_UNKNOWN = 0,

        /**
         * The operation timed out.
         */
        TIMEOUT = 1,

        /**
         * The operation was requested by the user.
         */
        USER_REQUESTED = 2,

        /**
         * Invalid arguments were provided.
         */
        INVALID_ARGS = 3
    }
}
