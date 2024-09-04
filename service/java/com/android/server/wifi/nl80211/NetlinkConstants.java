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

package com.android.server.wifi.nl80211;

/**
 * Constants used by Netlink and Nl80211.
 */
public class NetlinkConstants {
    // Netlink protocols. See kernel/uapi/linux/netlink.h
    public static final int NETLINK_GENERIC = 16;

    // Control message types. See kernel/uapi/linux/genetlink.h
    public static final short GENL_ID_CTRL = 0x10;

    // Control message commands. See kernel/uapi/linux/genetlink.h
    public static final short CTRL_CMD_NEWFAMILY = 1;
    public static final short CTRL_CMD_GETFAMILY = 3;

    // Control message attributes. See kernel/uapi/linux/genetlink.h
    public static final short CTRL_ATTR_FAMILY_ID = 1;
    public static final short CTRL_ATTR_FAMILY_NAME = 2;

    public static final short CTRL_ATTR_MCAST_GRP_NAME = 1;
    public static final short CTRL_ATTR_MCAST_GRP_ID = 2;

    public static final short CTRL_ATTR_MCAST_GROUPS = 7;

    // Nl80211 strings for initialization. See kernel/uapi/linux/nl80211.h
    public static final String NL80211_GENL_NAME = "nl80211";
    public static final String NL80211_MULTICAST_GROUP_SCAN = "scan";
    public static final String NL80211_MULTICAST_GROUP_REG = "regulatory";
    public static final String NL80211_MULTICAST_GROUP_MLME = "mlme";
}
