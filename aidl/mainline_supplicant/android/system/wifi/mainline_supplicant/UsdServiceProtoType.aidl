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

/**
 * Service protocols that use USD.
 */
@Backing(type="int")
enum UsdServiceProtoType {
    /**
     * Unknown service type.
     */
    UNKNOWN = 0,

    /**
     * Generic service.
     */
    GENERIC = 1,

    /**
     * CSA (Connectivity Standards Alliance) Matter.
     *
     * Note: CSA Matter is an open-source, royalty-free standard for smart home technology that
     * allows devices to work with any Matter-certified ecosystem.
     */
    CSA_MATTER = 2,
}
