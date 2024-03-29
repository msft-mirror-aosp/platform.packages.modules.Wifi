/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Utils for manipulating XML data. This is essentially a wrapper over XmlUtils provided by core.
 * The utility provides methods to write/parse section headers and write/parse values.
 * This utility is designed for formatting the XML into the following format:
 * <Document Header>
 *  <Section 1 Header>
 *   <Value 1>
 *   <Value 2>
 *   ...
 *   <Sub Section 1 Header>
 *    <Value 1>
 *    <Value 2>
 *    ...
 *   </Sub Section 1 Header>
 *  </Section 1 Header>
 * </Document Header>
 *
 * Note: These utility methods are meant to be used for:
 * 1. Backup/restore wifi network data to/from cloud.
 * 2. Persisting wifi network data to/from disk.
 */
public class XmlUtil {
    private static final String TAG = "WifiXmlUtil";

    /**
     * Ensure that the XML stream is at a start tag or the end of document.
     *
     * @throws XmlPullParserException if parsing errors occur.
     */
    private static void gotoStartTag(XmlPullParser in)
            throws XmlPullParserException, IOException {
        int type = in.getEventType();
        while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
            type = in.next();
        }
    }

    /**
     * Ensure that the XML stream is at an end tag or the end of document.
     *
     * @throws XmlPullParserException if parsing errors occur.
     */
    private static void gotoEndTag(XmlPullParser in)
            throws XmlPullParserException, IOException {
        int type = in.getEventType();
        while (type != XmlPullParser.END_TAG && type != XmlPullParser.END_DOCUMENT) {
            type = in.next();
        }
    }

    /**
     * Start processing the XML stream at the document header.
     *
     * @param in         XmlPullParser instance pointing to the XML stream.
     * @param headerName expected name for the start tag.
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static void gotoDocumentStart(XmlPullParser in, String headerName)
            throws XmlPullParserException, IOException {
        XmlUtilHelper.beginDocument(in, headerName);
    }

    /**
     * Move the XML stream to the next section header or indicate if there are no more sections.
     * The provided outerDepth is used to find sub sections within that depth.
     *
     * Use this to move across sections if the ordering of sections are variable. The returned name
     * can be used to decide what section is next.
     *
     * @param in         XmlPullParser instance pointing to the XML stream.
     * @param headerName An array of one string, used to return the name of the next section.
     * @param outerDepth Find section within this depth.
     * @return {@code true} if a next section is found, {@code false} if there are no more sections.
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static boolean gotoNextSectionOrEnd(
            XmlPullParser in, String[] headerName, int outerDepth)
            throws XmlPullParserException, IOException {
        if (XmlUtilHelper.nextElementWithin(in, outerDepth)) {
            headerName[0] = in.getName();
            return true;
        }
        return false;
    }

    /**
     * Move the XML stream to the next section header or indicate if there are no more sections.
     * If a section, exists ensure that the name matches the provided name.
     * The provided outerDepth is used to find sub sections within that depth.
     *
     * Use this to move across repeated sections until the end.
     *
     * @param in           XmlPullParser instance pointing to the XML stream.
     * @param expectedName expected name for the section header.
     * @param outerDepth   Find section within this depth.
     * @return {@code true} if a next section is found, {@code false} if there are no more sections.
     * @throws XmlPullParserException if the section header name does not match |expectedName|,
     *                                or if parsing errors occur.
     */
    public static boolean gotoNextSectionWithNameOrEnd(
            XmlPullParser in, String expectedName, int outerDepth)
            throws XmlPullParserException, IOException {
        String[] headerName = new String[1];
        if (gotoNextSectionOrEnd(in, headerName, outerDepth)) {
            if (headerName[0].equals(expectedName)) {
                return true;
            }
            throw new XmlPullParserException(
                    "Next section name does not match expected name: " + expectedName);
        }
        return false;
    }

    /**
     * Move the XML stream to the next section header and ensure that the name matches the provided
     * name.
     * The provided outerDepth is used to find sub sections within that depth.
     *
     * Use this to move across sections if the ordering of sections are fixed.
     *
     * @param in           XmlPullParser instance pointing to the XML stream.
     * @param expectedName expected name for the section header.
     * @param outerDepth   Find section within this depth.
     * @throws XmlPullParserException if the section header name does not match |expectedName|,
     *                                there are no more sections or if parsing errors occur.
     */
    public static void gotoNextSectionWithName(
            XmlPullParser in, String expectedName, int outerDepth)
            throws XmlPullParserException, IOException {
        if (!gotoNextSectionWithNameOrEnd(in, expectedName, outerDepth)) {
            throw new XmlPullParserException("Section not found. Expected: " + expectedName);
        }
    }

    /**
     * Checks if the stream is at the end of a section of values. This moves the stream to next tag
     * and checks if it finds an end tag at the specified depth.
     *
     * @param in           XmlPullParser instance pointing to the XML stream.
     * @param sectionDepth depth of the start tag of this section. Used to match the end tag.
     * @return {@code true} if a end tag at the provided depth is found, {@code false} otherwise
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static boolean isNextSectionEnd(XmlPullParser in, int sectionDepth)
            throws XmlPullParserException, IOException {
        return !XmlUtilHelper.nextElementWithin(in, sectionDepth);
    }

    /**
     * Read the current value in the XML stream using core XmlUtils and stores the retrieved
     * value name in the string provided. This method reads the value contained in current start
     * tag.
     * Note: Because there could be genuine null values being read from the XML, this method raises
     * an exception to indicate errors.
     *
     * @param in        XmlPullParser instance pointing to the XML stream.
     * @param valueName An array of one string, used to return the name attribute
     *                  of the value's tag.
     * @return value retrieved from the XML stream.
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static Object readCurrentValue(XmlPullParser in, String[] valueName)
            throws XmlPullParserException, IOException {
        Object value = XmlUtilHelper.readValueXml(in, valueName);
        // XmlUtils.readValue does not always move the stream to the end of the tag. So, move
        // it to the end tag before returning from here.
        gotoEndTag(in);
        return value;
    }

    /**
     * Read the next value in the XML stream using core XmlUtils and ensure that it matches the
     * provided name. This method moves the stream to the next start tag and reads the value
     * contained in it.
     * Note: Because there could be genuine null values being read from the XML, this method raises
     * an exception to indicate errors.
     *
     * @param in XmlPullParser instance pointing to the XML stream.
     * @return value retrieved from the XML stream.
     * @throws XmlPullParserException if the value read does not match |expectedName|,
     *                                or if parsing errors occur.
     */
    public static Object readNextValueWithName(XmlPullParser in, String expectedName)
            throws XmlPullParserException, IOException {
        String[] valueName = new String[1];
        XmlUtilHelper.nextElement(in);
        Object value = readCurrentValue(in, valueName);
        if (valueName[0].equals(expectedName)) {
            return value;
        }
        throw new XmlPullParserException(
                "Value not found. Expected: " + expectedName + ", but got: " + valueName[0]);
    }

    /**
     * Write the XML document start with the provided document header name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the start tag.
     */
    public static void writeDocumentStart(XmlSerializer out, String headerName)
            throws IOException {
        out.startDocument(null, true);
        out.startTag(null, headerName);
    }

    /**
     * Write the XML document end with the provided document header name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the end tag.
     */
    public static void writeDocumentEnd(XmlSerializer out, String headerName)
            throws IOException {
        out.endTag(null, headerName);
        out.endDocument();
    }

    /**
     * Write a section start header tag with the provided section name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the start tag.
     */
    public static void writeNextSectionStart(XmlSerializer out, String headerName)
            throws IOException {
        out.startTag(null, headerName);
    }

    /**
     * Write a section end header tag with the provided section name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the end tag.
     */
    public static void writeNextSectionEnd(XmlSerializer out, String headerName)
            throws IOException {
        out.endTag(null, headerName);
    }

    /**
     * Write the value with the provided name in the XML stream using core XmlUtils.
     *
     * @param out   XmlSerializer instance pointing to the XML stream.
     * @param name  name of the value.
     * @param value value to be written.
     */
    public static void writeNextValue(XmlSerializer out, String name, Object value)
            throws XmlPullParserException, IOException {
        XmlUtilHelper.writeValueXml(value, name, out);
    }

    /**
     * Utility class to serialize and deserialize {@link WifiConfiguration} object to XML &
     * vice versa.
     * This is used by both {@link com.android.server.wifi.WifiConfigStore} &
     * {@link com.android.server.wifi.WifiBackupRestore} modules.
     * The |writeConfigurationToXml| has 2 versions, one for backup and one for config store.
     * There is only 1 version of |parseXmlToConfiguration| for both backup & config store.
     * The parse method is written so that any element added/deleted in future revisions can
     * be easily handled.
     */
    public static class WifiConfigurationXmlUtil {
        /**
         * List of XML tags corresponding to WifiConfiguration object elements.
         */
        public static final String XML_TAG_SSID = "SSID";
        public static final String XML_TAG_BSSID = "BSSID";
        public static final String XML_TAG_CONFIG_KEY = "ConfigKey";
        public static final String XML_TAG_PRE_SHARED_KEY = "PreSharedKey";
        public static final String XML_TAG_WEP_KEYS = "WEPKeys";
        public static final String XML_TAG_WEP_TX_KEY_INDEX = "WEPTxKeyIndex";
        public static final String XML_TAG_HIDDEN_SSID = "HiddenSSID";
        public static final String XML_TAG_REQUIRE_PMF = "RequirePMF";
        public static final String XML_TAG_ALLOWED_KEY_MGMT = "AllowedKeyMgmt";
        public static final String XML_TAG_ALLOWED_PROTOCOLS = "AllowedProtocols";
        public static final String XML_TAG_ALLOWED_AUTH_ALGOS = "AllowedAuthAlgos";
        public static final String XML_TAG_ALLOWED_GROUP_CIPHERS = "AllowedGroupCiphers";
        public static final String XML_TAG_ALLOWED_PAIRWISE_CIPHERS = "AllowedPairwiseCiphers";
        public static final String XML_TAG_ALLOWED_GROUP_MGMT_CIPHERS = "AllowedGroupMgmtCiphers";
        public static final String XML_TAG_ALLOWED_SUITE_B_CIPHERS = "AllowedSuiteBCiphers";
        public static final String XML_TAG_SHARED = "Shared";
        public static final String XML_TAG_STATUS = "Status";
        public static final String XML_TAG_FQDN = "FQDN";
        public static final String XML_TAG_PROVIDER_FRIENDLY_NAME = "ProviderFriendlyName";
        public static final String XML_TAG_LINKED_NETWORKS_LIST = "LinkedNetworksList";
        public static final String XML_TAG_DEFAULT_GW_MAC_ADDRESS = "DefaultGwMacAddress";
        public static final String XML_TAG_VALIDATED_INTERNET_ACCESS = "ValidatedInternetAccess";
        public static final String XML_TAG_NO_INTERNET_ACCESS_EXPECTED = "NoInternetAccessExpected";
        public static final String XML_TAG_METERED_HINT = "MeteredHint";
        public static final String XML_TAG_METERED_OVERRIDE = "MeteredOverride";
        public static final String XML_TAG_USE_EXTERNAL_SCORES = "UseExternalScores";
        public static final String XML_TAG_CREATOR_UID = "CreatorUid";
        public static final String XML_TAG_CREATOR_NAME = "CreatorName";
        public static final String XML_TAG_LAST_UPDATE_UID = "LastUpdateUid";
        public static final String XML_TAG_LAST_UPDATE_NAME = "LastUpdateName";
        public static final String XML_TAG_LAST_CONNECT_UID = "LastConnectUid";
        public static final String XML_TAG_IS_LEGACY_PASSPOINT_CONFIG = "IsLegacyPasspointConfig";
        public static final String XML_TAG_ROAMING_CONSORTIUM_OIS = "RoamingConsortiumOIs";
        public static final String XML_TAG_RANDOMIZED_MAC_ADDRESS = "RandomizedMacAddress";
        public static final String XML_TAG_MAC_RANDOMIZATION_SETTING = "MacRandomizationSetting";
        public static final String XML_TAG_CARRIER_ID = "CarrierId";
        public static final String XML_TAG_IS_AUTO_JOIN = "AutoJoinEnabled";
        public static final String XML_TAG_IS_TRUSTED = "Trusted";
        private static final String XML_TAG_IS_MOST_RECENTLY_CONNECTED = "IsMostRecentlyConnected";

        /**
         * Write WepKeys to the XML stream.
         * WepKeys array is intialized in WifiConfiguration constructor, but all of the elements
         * are set to null. User may chose to set any one of the key elements in WifiConfiguration.
         * XmlUtils serialization doesn't handle this array of nulls well .
         * So, write empty strings if some of the keys are not initialized and null if all of
         * the elements are empty.
         */
        private static void writeWepKeysToXml(XmlSerializer out, String[] wepKeys)
                throws XmlPullParserException, IOException {
            String[] wepKeysToWrite = new String[wepKeys.length];
            boolean hasWepKey = false;
            for (int i = 0; i < wepKeys.length; i++) {
                if (wepKeys[i] == null) {
                    wepKeysToWrite[i] = new String();
                } else {
                    wepKeysToWrite[i] = wepKeys[i];
                    hasWepKey = true;
                }
            }
            if (hasWepKey) {
                XmlUtil.writeNextValue(out, XML_TAG_WEP_KEYS, wepKeysToWrite);
            } else {
                XmlUtil.writeNextValue(out, XML_TAG_WEP_KEYS, null);
            }
        }

        /**
         * Write preshared key to the XML stream.
         *
         * If encryptionUtil is null or if encryption fails for some reason, the pre-shared
         * key is stored in plaintext, else the encrypted psk is stored.
         */
        private static void writePreSharedKeyToXml(
                XmlSerializer out, String preSharedKey,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            EncryptedData encryptedData = null;
            if (encryptionUtil != null) {
                if (preSharedKey != null) {
                    encryptedData = encryptionUtil.encrypt(preSharedKey.getBytes());
                    if (encryptedData == null) {
                        // We silently fail encryption failures!
                        Log.wtf(TAG, "Encryption of preSharedKey failed");
                    }
                }
            }
            if (encryptedData != null) {
                XmlUtil.writeNextSectionStart(out, XML_TAG_PRE_SHARED_KEY);
                EncryptedDataXmlUtil.writeToXml(out, encryptedData);
                XmlUtil.writeNextSectionEnd(out, XML_TAG_PRE_SHARED_KEY);
            } else {
                XmlUtil.writeNextValue(out, XML_TAG_PRE_SHARED_KEY, preSharedKey);
            }
        }

        /**
         * Write the Configuration data elements that are common for backup & config store to the
         * XML stream.
         *
         * @param out XmlSerializer instance pointing to the XML stream.
         * @param configuration WifiConfiguration object to be serialized.
         * @param encryptionUtil Instance of {@link EncryptedDataXmlUtil}. Backup/restore stores
         *                       keys unencrypted.
         */
        public static void writeCommonElementsToXml(
                XmlSerializer out, WifiConfiguration configuration,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            XmlUtil.writeNextValue(out, XML_TAG_CONFIG_KEY, configuration.getKey());
            XmlUtil.writeNextValue(out, XML_TAG_SSID, configuration.SSID);
            writePreSharedKeyToXml(out, configuration.preSharedKey, encryptionUtil);
            writeWepKeysToXml(out, configuration.wepKeys);
            XmlUtil.writeNextValue(out, XML_TAG_WEP_TX_KEY_INDEX, configuration.wepTxKeyIndex);
            XmlUtil.writeNextValue(out, XML_TAG_HIDDEN_SSID, configuration.hiddenSSID);
            XmlUtil.writeNextValue(out, XML_TAG_REQUIRE_PMF, configuration.requirePmf);
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_KEY_MGMT,
                    configuration.allowedKeyManagement.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_PROTOCOLS,
                    configuration.allowedProtocols.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_AUTH_ALGOS,
                    configuration.allowedAuthAlgorithms.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_GROUP_CIPHERS,
                    configuration.allowedGroupCiphers.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_PAIRWISE_CIPHERS,
                    configuration.allowedPairwiseCiphers.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_GROUP_MGMT_CIPHERS,
                    configuration.allowedGroupManagementCiphers.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_SUITE_B_CIPHERS,
                    configuration.allowedSuiteBCiphers.toByteArray());
            XmlUtil.writeNextValue(out, XML_TAG_SHARED, configuration.shared);
            XmlUtil.writeNextValue(out, XML_TAG_IS_AUTO_JOIN, configuration.allowAutojoin);
        }

        /**
         * Write the Configuration data elements for backup from the provided Configuration to the
         * XML stream.
         * Note: This is a subset of the elements serialized for config store.
         *
         * @param out           XmlSerializer instance pointing to the XML stream.
         * @param configuration WifiConfiguration object to be serialized.
         */
        public static void writeToXmlForBackup(XmlSerializer out, WifiConfiguration configuration)
                throws XmlPullParserException, IOException {
            writeCommonElementsToXml(out, configuration, null);
            XmlUtil.writeNextValue(out, XML_TAG_METERED_OVERRIDE, configuration.meteredOverride);
        }

        /**
         * Write the Configuration data elements for config store from the provided Configuration
         * to the XML stream.
         *
         * @param out XmlSerializer instance pointing to the XML stream.
         * @param configuration WifiConfiguration object to be serialized.
         * @param encryptionUtil Instance of {@link EncryptedDataXmlUtil}.
         */
        public static void writeToXmlForConfigStore(
                XmlSerializer out, WifiConfiguration configuration,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            writeCommonElementsToXml(out, configuration, encryptionUtil);
            XmlUtil.writeNextValue(out, XML_TAG_IS_TRUSTED, configuration.trusted);
            XmlUtil.writeNextValue(out, XML_TAG_BSSID, configuration.BSSID);
            XmlUtil.writeNextValue(out, XML_TAG_STATUS, configuration.status);
            XmlUtil.writeNextValue(out, XML_TAG_FQDN, configuration.FQDN);
            XmlUtil.writeNextValue(
                    out, XML_TAG_PROVIDER_FRIENDLY_NAME, configuration.providerFriendlyName);
            XmlUtil.writeNextValue(
                    out, XML_TAG_LINKED_NETWORKS_LIST, configuration.linkedConfigurations);
            XmlUtil.writeNextValue(
                    out, XML_TAG_DEFAULT_GW_MAC_ADDRESS, configuration.defaultGwMacAddress);
            XmlUtil.writeNextValue(
                    out, XML_TAG_VALIDATED_INTERNET_ACCESS, configuration.validatedInternetAccess);
            XmlUtil.writeNextValue(
                    out, XML_TAG_NO_INTERNET_ACCESS_EXPECTED,
                    configuration.noInternetAccessExpected);
            XmlUtil.writeNextValue(out, XML_TAG_METERED_HINT, configuration.meteredHint);
            XmlUtil.writeNextValue(out, XML_TAG_METERED_OVERRIDE, configuration.meteredOverride);
            XmlUtil.writeNextValue(
                    out, XML_TAG_USE_EXTERNAL_SCORES, configuration.useExternalScores);
            XmlUtil.writeNextValue(out, XML_TAG_CREATOR_UID, configuration.creatorUid);
            XmlUtil.writeNextValue(out, XML_TAG_CREATOR_NAME, configuration.creatorName);
            XmlUtil.writeNextValue(out, XML_TAG_LAST_UPDATE_UID, configuration.lastUpdateUid);
            XmlUtil.writeNextValue(out, XML_TAG_LAST_UPDATE_NAME, configuration.lastUpdateName);
            XmlUtil.writeNextValue(out, XML_TAG_LAST_CONNECT_UID, configuration.lastConnectUid);
            XmlUtil.writeNextValue(
                    out, XML_TAG_IS_LEGACY_PASSPOINT_CONFIG,
                    configuration.isLegacyPasspointConfig);
            XmlUtil.writeNextValue(
                    out, XML_TAG_ROAMING_CONSORTIUM_OIS, configuration.roamingConsortiumIds);
            XmlUtil.writeNextValue(out, XML_TAG_RANDOMIZED_MAC_ADDRESS,
                    configuration.getRandomizedMacAddress().toString());
            XmlUtil.writeNextValue(out, XML_TAG_MAC_RANDOMIZATION_SETTING,
                    configuration.macRandomizationSetting);
            XmlUtil.writeNextValue(out, XML_TAG_CARRIER_ID, configuration.carrierId);
            XmlUtil.writeNextValue(out, XML_TAG_IS_MOST_RECENTLY_CONNECTED,
                    configuration.isMostRecentlyConnected);
        }

        /**
         * Populate wepKeys array elements only if they were non-empty in the backup data.
         *
         * @throws XmlPullParserException if parsing errors occur.
         */
        private static void populateWepKeysFromXmlValue(Object value, String[] wepKeys)
                throws XmlPullParserException, IOException {
            String[] wepKeysInData = (String[]) value;
            if (wepKeysInData == null) {
                return;
            }
            if (wepKeysInData.length != wepKeys.length) {
                throw new XmlPullParserException(
                        "Invalid Wep Keys length: " + wepKeysInData.length);
            }
            for (int i = 0; i < wepKeys.length; i++) {
                if (wepKeysInData[i].isEmpty()) {
                    wepKeys[i] = null;
                } else {
                    wepKeys[i] = wepKeysInData[i];
                }
            }
        }

        /**
         * Parses the configuration data elements from the provided XML stream to a
         * WifiConfiguration object.
         * Note: This is used for parsing both backup data and config store data. Looping through
         * the tags make it easy to add or remove elements in the future versions if needed.
         *
         * @param in XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @param shouldExpectEncryptedCredentials Whether to expect encrypted credentials or not.
         * @param encryptionUtil Instance of {@link EncryptedDataXmlUtil}.
         * @return Pair<Config key, WifiConfiguration object> if parsing is successful,
         * null otherwise.
         */
        public static Pair<String, WifiConfiguration> parseFromXml(
                XmlPullParser in, int outerTagDepth, boolean shouldExpectEncryptedCredentials,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            WifiConfiguration configuration = new WifiConfiguration();
            String configKeyInData = null;
            boolean macRandomizationSettingExists = false;

            // Loop through and parse out all the elements from the stream within this section.
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                if (in.getAttributeValue(null, "name") != null) {
                    // Value elements.
                    String[] valueName = new String[1];
                    Object value = XmlUtil.readCurrentValue(in, valueName);
                    if (valueName[0] == null) {
                        throw new XmlPullParserException("Missing value name");
                    }
                    switch (valueName[0]) {
                        case XML_TAG_CONFIG_KEY:
                            configKeyInData = (String) value;
                            break;
                        case XML_TAG_SSID:
                            configuration.SSID = (String) value;
                            break;
                        case XML_TAG_BSSID:
                            configuration.BSSID = (String) value;
                            break;
                        case XML_TAG_PRE_SHARED_KEY:
                            configuration.preSharedKey = (String) value;
                            break;
                        case XML_TAG_WEP_KEYS:
                            populateWepKeysFromXmlValue(value, configuration.wepKeys);
                            break;
                        case XML_TAG_WEP_TX_KEY_INDEX:
                            configuration.wepTxKeyIndex = (int) value;
                            break;
                        case XML_TAG_HIDDEN_SSID:
                            configuration.hiddenSSID = (boolean) value;
                            break;
                        case XML_TAG_REQUIRE_PMF:
                            configuration.requirePmf = (boolean) value;
                            break;
                        case XML_TAG_ALLOWED_KEY_MGMT:
                            byte[] allowedKeyMgmt = (byte[]) value;
                            configuration.allowedKeyManagement = BitSet.valueOf(allowedKeyMgmt);
                            break;
                        case XML_TAG_ALLOWED_PROTOCOLS:
                            byte[] allowedProtocols = (byte[]) value;
                            configuration.allowedProtocols = BitSet.valueOf(allowedProtocols);
                            break;
                        case XML_TAG_ALLOWED_AUTH_ALGOS:
                            byte[] allowedAuthAlgorithms = (byte[]) value;
                            configuration.allowedAuthAlgorithms = BitSet.valueOf(
                                    allowedAuthAlgorithms);
                            break;
                        case XML_TAG_ALLOWED_GROUP_CIPHERS:
                            byte[] allowedGroupCiphers = (byte[]) value;
                            configuration.allowedGroupCiphers = BitSet.valueOf(allowedGroupCiphers);
                            break;
                        case XML_TAG_ALLOWED_PAIRWISE_CIPHERS:
                            byte[] allowedPairwiseCiphers = (byte[]) value;
                            configuration.allowedPairwiseCiphers =
                                    BitSet.valueOf(allowedPairwiseCiphers);
                            break;
                        case XML_TAG_ALLOWED_GROUP_MGMT_CIPHERS:
                            byte[] allowedGroupMgmtCiphers = (byte[]) value;
                            configuration.allowedGroupManagementCiphers =
                                    BitSet.valueOf(allowedGroupMgmtCiphers);
                            break;
                        case XML_TAG_ALLOWED_SUITE_B_CIPHERS:
                            byte[] allowedSuiteBCiphers = (byte[]) value;
                            configuration.allowedSuiteBCiphers =
                                    BitSet.valueOf(allowedSuiteBCiphers);
                            break;
                        case XML_TAG_SHARED:
                            configuration.shared = (boolean) value;
                            break;
                        case XML_TAG_STATUS:
                            int status = (int) value;
                            // Any network which was CURRENT before reboot needs
                            // to be restored to ENABLED.
                            if (status == WifiConfiguration.Status.CURRENT) {
                                status = WifiConfiguration.Status.ENABLED;
                            }
                            configuration.status = status;
                            break;
                        case XML_TAG_FQDN:
                            configuration.FQDN = (String) value;
                            break;
                        case XML_TAG_PROVIDER_FRIENDLY_NAME:
                            configuration.providerFriendlyName = (String) value;
                            break;
                        case XML_TAG_LINKED_NETWORKS_LIST:
                            configuration.linkedConfigurations = (HashMap<String, Integer>) value;
                            break;
                        case XML_TAG_DEFAULT_GW_MAC_ADDRESS:
                            configuration.defaultGwMacAddress = (String) value;
                            break;
                        case XML_TAG_VALIDATED_INTERNET_ACCESS:
                            configuration.validatedInternetAccess = (boolean) value;
                            break;
                        case XML_TAG_NO_INTERNET_ACCESS_EXPECTED:
                            configuration.noInternetAccessExpected = (boolean) value;
                            break;
                        case XML_TAG_METERED_HINT:
                            configuration.meteredHint = (boolean) value;
                            break;
                        case XML_TAG_METERED_OVERRIDE:
                            configuration.meteredOverride = (int) value;
                            break;
                        case XML_TAG_USE_EXTERNAL_SCORES:
                            configuration.useExternalScores = (boolean) value;
                            break;
                        case XML_TAG_CREATOR_UID:
                            configuration.creatorUid = (int) value;
                            break;
                        case XML_TAG_CREATOR_NAME:
                            configuration.creatorName = (String) value;
                            break;
                        case XML_TAG_LAST_UPDATE_UID:
                            configuration.lastUpdateUid = (int) value;
                            break;
                        case XML_TAG_LAST_UPDATE_NAME:
                            configuration.lastUpdateName = (String) value;
                            break;
                        case XML_TAG_LAST_CONNECT_UID:
                            configuration.lastConnectUid = (int) value;
                            break;
                        case XML_TAG_IS_LEGACY_PASSPOINT_CONFIG:
                            configuration.isLegacyPasspointConfig = (boolean) value;
                            break;
                        case XML_TAG_ROAMING_CONSORTIUM_OIS:
                            configuration.roamingConsortiumIds = (long[]) value;
                            break;
                        case XML_TAG_RANDOMIZED_MAC_ADDRESS:
                            configuration.setRandomizedMacAddress(
                                    MacAddress.fromString((String) value));
                            break;
                        case XML_TAG_MAC_RANDOMIZATION_SETTING:
                            configuration.macRandomizationSetting = (int) value;
                            macRandomizationSettingExists = true;
                            break;
                        case XML_TAG_CARRIER_ID:
                            configuration.carrierId = (int) value;
                            break;
                        case XML_TAG_IS_AUTO_JOIN:
                            configuration.allowAutojoin = (boolean) value;
                            break;
                        case XML_TAG_IS_TRUSTED:
                            configuration.trusted = (boolean) value;
                            break;
                        case XML_TAG_IS_MOST_RECENTLY_CONNECTED:
                            configuration.isMostRecentlyConnected = (boolean) value;
                            break;
                        default:
                            Log.w(TAG, "Ignoring unknown value name found: " + valueName[0]);
                            break;
                    }
                } else {
                    String tagName = in.getName();
                    if (tagName == null) {
                        throw new XmlPullParserException("Unexpected null tag found");
                    }
                    switch (tagName) {
                        case XML_TAG_PRE_SHARED_KEY:
                            if (!shouldExpectEncryptedCredentials || encryptionUtil == null) {
                                throw new XmlPullParserException(
                                        "Encrypted preSharedKey section not expected");
                            }
                            EncryptedData encryptedData =
                                    EncryptedDataXmlUtil.parseFromXml(in, outerTagDepth + 1);
                            byte[] preSharedKeyBytes = encryptionUtil.decrypt(encryptedData);
                            if (preSharedKeyBytes == null) {
                                Log.wtf(TAG, "Decryption of preSharedKey failed");
                            } else {
                                configuration.preSharedKey = new String(preSharedKeyBytes);
                            }
                            break;
                        default:
                            Log.w(TAG, "Ignoring unknown tag found: " + tagName);
                            break;
                    }
                }
            }
            if (!macRandomizationSettingExists) {
                configuration.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
            }
            return Pair.create(configKeyInData, configuration);
        }
    }

    /**
     * Utility class to serialize and deseriaize {@link IpConfiguration} object to XML & vice versa.
     * This is used by both {@link com.android.server.wifi.WifiConfigStore} &
     * {@link com.android.server.wifi.WifiBackupRestore} modules.
     */
    public static class IpConfigurationXmlUtil {

        /**
         * List of XML tags corresponding to IpConfiguration object elements.
         */
        public static final String XML_TAG_IP_ASSIGNMENT = "IpAssignment";
        public static final String XML_TAG_LINK_ADDRESS = "LinkAddress";
        public static final String XML_TAG_LINK_PREFIX_LENGTH = "LinkPrefixLength";
        public static final String XML_TAG_GATEWAY_ADDRESS = "GatewayAddress";
        public static final String XML_TAG_DNS_SERVER_ADDRESSES = "DNSServers";
        public static final String XML_TAG_PROXY_SETTINGS = "ProxySettings";
        public static final String XML_TAG_PROXY_HOST = "ProxyHost";
        public static final String XML_TAG_PROXY_PORT = "ProxyPort";
        public static final String XML_TAG_PROXY_PAC_FILE = "ProxyPac";
        public static final String XML_TAG_PROXY_EXCLUSION_LIST = "ProxyExclusionList";

        private static List<String> parseProxyExclusionListString(
                @Nullable String exclusionListString) {
            if (exclusionListString == null) {
                return Collections.emptyList();
            } else {
                return Arrays.asList(exclusionListString.toLowerCase(Locale.ROOT).split(","));
            }
        }

        private static String generateProxyExclusionListString(@NonNull String[] exclusionList) {
            return TextUtils.join(",", exclusionList);
        }

        /**
         * Write the static IP configuration data elements to XML stream.
         */
        private static void writeStaticIpConfigurationToXml(
                XmlSerializer out, StaticIpConfiguration staticIpConfiguration)
                throws XmlPullParserException, IOException {
            if (staticIpConfiguration.getIpAddress() != null) {
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_ADDRESS,
                        staticIpConfiguration.getIpAddress().getAddress().getHostAddress());
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_PREFIX_LENGTH,
                        staticIpConfiguration.getIpAddress().getPrefixLength());
            } else {
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_ADDRESS, null);
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_PREFIX_LENGTH, null);
            }
            if (staticIpConfiguration.getGateway() != null) {
                XmlUtil.writeNextValue(
                        out, XML_TAG_GATEWAY_ADDRESS,
                        staticIpConfiguration.getGateway().getHostAddress());
            } else {
                XmlUtil.writeNextValue(
                        out, XML_TAG_GATEWAY_ADDRESS, null);

            }
            // Create a string array of DNS server addresses
            String[] dnsServers = new String[staticIpConfiguration.getDnsServers().size()];
            int dnsServerIdx = 0;
            for (InetAddress inetAddr : staticIpConfiguration.getDnsServers()) {
                dnsServers[dnsServerIdx++] = inetAddr.getHostAddress();
            }
            XmlUtil.writeNextValue(
                    out, XML_TAG_DNS_SERVER_ADDRESSES, dnsServers);
        }

        /**
         * Write the IP configuration data elements from the provided Configuration to the XML
         * stream.
         *
         * @param out             XmlSerializer instance pointing to the XML stream.
         * @param ipConfiguration IpConfiguration object to be serialized.
         */
        public static void writeToXml(XmlSerializer out, IpConfiguration ipConfiguration)
                throws XmlPullParserException, IOException {
            // Write IP assignment settings
            XmlUtil.writeNextValue(out, XML_TAG_IP_ASSIGNMENT,
                    ipConfiguration.getIpAssignment().toString());
            switch (ipConfiguration.getIpAssignment()) {
                case STATIC:
                    writeStaticIpConfigurationToXml(
                            out, ipConfiguration.getStaticIpConfiguration());
                    break;
                case DHCP:
                case UNASSIGNED:
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown ip assignment type: "
                            + ipConfiguration.getIpAssignment());
                    break;
            }

            // Write proxy settings
            XmlUtil.writeNextValue(
                    out, XML_TAG_PROXY_SETTINGS,
                    ipConfiguration.getProxySettings().toString());
            switch (ipConfiguration.getProxySettings()) {
                case STATIC:
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_HOST,
                            ipConfiguration.getHttpProxy().getHost());
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_PORT,
                            ipConfiguration.getHttpProxy().getPort());
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_EXCLUSION_LIST,
                            generateProxyExclusionListString(
                                    ipConfiguration.getHttpProxy().getExclusionList()));
                    break;
                case PAC:
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_PAC_FILE,
                            ipConfiguration.getHttpProxy().getPacFileUrl().toString());
                    break;
                case NONE:
                case UNASSIGNED:
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown proxy settings type: "
                            + ipConfiguration.getProxySettings());
                    break;
            }
        }

        /**
         * Parse out the static IP configuration from the XML stream.
         */
        private static StaticIpConfiguration parseStaticIpConfigurationFromXml(XmlPullParser in)
                throws XmlPullParserException, IOException {
            StaticIpConfiguration.Builder builder = new StaticIpConfiguration.Builder();

            String linkAddressString =
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_LINK_ADDRESS);
            Integer linkPrefixLength =
                    (Integer) XmlUtil.readNextValueWithName(in, XML_TAG_LINK_PREFIX_LENGTH);
            if (linkAddressString != null && linkPrefixLength != null) {
                LinkAddress linkAddress = new LinkAddress(
                        InetAddresses.parseNumericAddress(linkAddressString),
                        linkPrefixLength);
                if (linkAddress.getAddress() instanceof Inet4Address) {
                    builder.setIpAddress(linkAddress);
                } else {
                    Log.w(TAG, "Non-IPv4 address: " + linkAddress);
                }
            }
            String gatewayAddressString =
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_GATEWAY_ADDRESS);
            if (gatewayAddressString != null) {
                InetAddress gateway =
                        InetAddresses.parseNumericAddress(gatewayAddressString);
                RouteInfo route = new RouteInfo(null, gateway, null, RouteInfo.RTN_UNICAST);
                if (route.isDefaultRoute()
                        && route.getDestination().getAddress() instanceof Inet4Address) {
                    builder.setGateway(gateway);
                } else {
                    Log.w(TAG, "Non-IPv4 default route: " + route);
                }
            }
            String[] dnsServerAddressesString =
                    (String[]) XmlUtil.readNextValueWithName(in, XML_TAG_DNS_SERVER_ADDRESSES);
            if (dnsServerAddressesString != null) {
                List<InetAddress> dnsServerAddresses = new ArrayList<>();
                for (String dnsServerAddressString : dnsServerAddressesString) {
                    InetAddress dnsServerAddress =
                            InetAddresses.parseNumericAddress(dnsServerAddressString);
                    dnsServerAddresses.add(dnsServerAddress);
                }
                builder.setDnsServers(dnsServerAddresses);
            }
            return builder.build();
        }

        /**
         * Parses the IP configuration data elements from the provided XML stream to an
         * IpConfiguration object.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return IpConfiguration object if parsing is successful, null otherwise.
         */
        public static IpConfiguration parseFromXml(XmlPullParser in, int outerTagDepth)
                throws XmlPullParserException, IOException {
            IpConfiguration ipConfiguration = new IpConfiguration();

            // Parse out the IP assignment info first.
            String ipAssignmentString =
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_IP_ASSIGNMENT);
            IpAssignment ipAssignment = IpAssignment.valueOf(ipAssignmentString);
            ipConfiguration.setIpAssignment(ipAssignment);
            switch (ipAssignment) {
                case STATIC:
                    ipConfiguration.setStaticIpConfiguration(parseStaticIpConfigurationFromXml(in));
                    break;
                case DHCP:
                case UNASSIGNED:
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown ip assignment type: " + ipAssignment);
                    break;
            }

            // Parse out the proxy settings next.
            String proxySettingsString =
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_SETTINGS);
            ProxySettings proxySettings = ProxySettings.valueOf(proxySettingsString);
            ipConfiguration.setProxySettings(proxySettings);
            switch (proxySettings) {
                case STATIC:
                    String proxyHost =
                            (String) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_HOST);
                    int proxyPort =
                            (int) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_PORT);
                    String proxyExclusionList =
                            (String) XmlUtil.readNextValueWithName(
                                    in, XML_TAG_PROXY_EXCLUSION_LIST);
                    ipConfiguration.setHttpProxy(
                            ProxyInfo.buildDirectProxy(
                                    proxyHost, proxyPort,
                                    parseProxyExclusionListString(proxyExclusionList)));
                    break;
                case PAC:
                    String proxyPacFile =
                            (String) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_PAC_FILE);
                    ipConfiguration.setHttpProxy(
                            ProxyInfo.buildPacProxy(Uri.parse(proxyPacFile)));
                    break;
                case NONE:
                case UNASSIGNED:
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown proxy settings type: " + proxySettings);
                    break;
            }
            return ipConfiguration;
        }
    }

    /**
     * Utility class to serialize and deseriaize {@link NetworkSelectionStatus} object to XML &
     * vice versa. This is used by {@link com.android.server.wifi.WifiConfigStore} module.
     */
    public static class NetworkSelectionStatusXmlUtil {

        /**
         * List of XML tags corresponding to NetworkSelectionStatus object elements.
         */
        public static final String XML_TAG_SELECTION_STATUS = "SelectionStatus";
        public static final String XML_TAG_DISABLE_REASON = "DisableReason";
        public static final String XML_TAG_CONNECT_CHOICE = "ConnectChoice";
        public static final String XML_TAG_HAS_EVER_CONNECTED = "HasEverConnected";

        /**
         * Write the NetworkSelectionStatus data elements from the provided status to the XML
         * stream.
         *
         * @param out             XmlSerializer instance pointing to the XML stream.
         * @param selectionStatus NetworkSelectionStatus object to be serialized.
         */
        public static void writeToXml(XmlSerializer out, NetworkSelectionStatus selectionStatus)
                throws XmlPullParserException, IOException {
            XmlUtil.writeNextValue(
                    out, XML_TAG_SELECTION_STATUS, selectionStatus.getNetworkStatusString());
            XmlUtil.writeNextValue(
                    out, XML_TAG_DISABLE_REASON,
                    selectionStatus.getNetworkSelectionDisableReasonString());
            XmlUtil.writeNextValue(out, XML_TAG_CONNECT_CHOICE, selectionStatus.getConnectChoice());
            XmlUtil.writeNextValue(
                    out, XML_TAG_HAS_EVER_CONNECTED, selectionStatus.hasEverConnected());
        }

        /**
         * Parses the NetworkSelectionStatus data elements from the provided XML stream to a
         * NetworkSelectionStatus object.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return NetworkSelectionStatus object if parsing is successful, null otherwise.
         */
        public static NetworkSelectionStatus parseFromXml(XmlPullParser in, int outerTagDepth)
                throws XmlPullParserException, IOException {
            NetworkSelectionStatus selectionStatus = new NetworkSelectionStatus();
            String statusString = "";
            String disableReasonString = "";

            // Loop through and parse out all the elements from the stream within this section.
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (valueName[0] == null) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_SELECTION_STATUS:
                        statusString = (String) value;
                        break;
                    case XML_TAG_DISABLE_REASON:
                        disableReasonString = (String) value;
                        break;
                    case XML_TAG_CONNECT_CHOICE:
                        selectionStatus.setConnectChoice((String) value);
                        break;
                    case XML_TAG_HAS_EVER_CONNECTED:
                        selectionStatus.setHasEverConnected((boolean) value);
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown value name found: " + valueName[0]);
                        break;
                }
            }
            // Now figure out the network selection status codes from |selectionStatusString| &
            // |disableReasonString|.
            int status =
                    Arrays.asList(NetworkSelectionStatus.QUALITY_NETWORK_SELECTION_STATUS)
                            .indexOf(statusString);
            int disableReason =
                    NetworkSelectionStatus.getDisableReasonByString(disableReasonString);

            // If either of the above codes are invalid or if the network was temporarily disabled
            // (blacklisted), restore the status as enabled. We don't want to persist blacklists
            // across reboots.
            if (status == -1 || disableReason == -1 ||
                    status == NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED) {
                status = NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
                disableReason = NetworkSelectionStatus.DISABLED_NONE;
            }
            selectionStatus.setNetworkSelectionStatus(status);
            selectionStatus.setNetworkSelectionDisableReason(disableReason);
            return selectionStatus;
        }
    }

    /**
     * Utility class to serialize and deseriaize {@link WifiEnterpriseConfig} object to XML &
     * vice versa. This is used by {@link com.android.server.wifi.WifiConfigStore} module.
     */
    public static class WifiEnterpriseConfigXmlUtil {

        /**
         * List of XML tags corresponding to WifiEnterpriseConfig object elements.
         */
        public static final String XML_TAG_IDENTITY = "Identity";
        public static final String XML_TAG_ANON_IDENTITY = "AnonIdentity";
        public static final String XML_TAG_PASSWORD = "Password";
        public static final String XML_TAG_CLIENT_CERT = "ClientCert";
        public static final String XML_TAG_CA_CERT = "CaCert";
        public static final String XML_TAG_SUBJECT_MATCH = "SubjectMatch";
        public static final String XML_TAG_ENGINE = "Engine";
        public static final String XML_TAG_ENGINE_ID = "EngineId";
        public static final String XML_TAG_PRIVATE_KEY_ID = "PrivateKeyId";
        public static final String XML_TAG_ALT_SUBJECT_MATCH = "AltSubjectMatch";
        public static final String XML_TAG_DOM_SUFFIX_MATCH = "DomSuffixMatch";
        public static final String XML_TAG_CA_PATH = "CaPath";
        public static final String XML_TAG_EAP_METHOD = "EapMethod";
        public static final String XML_TAG_PHASE2_METHOD = "Phase2Method";
        public static final String XML_TAG_PLMN = "PLMN";
        public static final String XML_TAG_REALM = "Realm";
        public static final String XML_TAG_OCSP = "Ocsp";
        public static final String XML_TAG_WAPI_CERT_SUITE = "WapiCertSuite";
        public static final String XML_TAG_APP_INSTALLED_ROOT_CA_CERT = "AppInstalledRootCaCert";
        public static final String XML_TAG_APP_INSTALLED_PRIVATE_KEY = "AppInstalledPrivateKey";

        /**
         * Write password key to the XML stream.
         *
         * If encryptionUtil is null or if encryption fails for some reason, the password is stored
         * in plaintext, else the encrypted psk is stored.
         */
        private static void writePasswordToXml(
                XmlSerializer out, String password,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            EncryptedData encryptedData = null;
            if (encryptionUtil != null) {
                if (password != null) {
                    encryptedData = encryptionUtil.encrypt(password.getBytes());
                    if (encryptedData == null) {
                        // We silently fail encryption failures!
                        Log.wtf(TAG, "Encryption of password failed");
                    }
                }
            }
            if (encryptedData != null) {
                XmlUtil.writeNextSectionStart(out, XML_TAG_PASSWORD);
                EncryptedDataXmlUtil.writeToXml(out, encryptedData);
                XmlUtil.writeNextSectionEnd(out, XML_TAG_PASSWORD);
            } else {
                XmlUtil.writeNextValue(out, XML_TAG_PASSWORD, password);
            }
        }

        /**
         * Write the WifiEnterpriseConfig data elements from the provided config to the XML
         * stream.
         *
         * @param out XmlSerializer instance pointing to the XML stream.
         * @param enterpriseConfig WifiEnterpriseConfig object to be serialized.
         * @param encryptionUtil Instance of {@link EncryptedDataXmlUtil}.
         */
        public static void writeToXml(XmlSerializer out, WifiEnterpriseConfig enterpriseConfig,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            XmlUtil.writeNextValue(out, XML_TAG_IDENTITY,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.IDENTITY_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_ANON_IDENTITY,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.ANON_IDENTITY_KEY));
            writePasswordToXml(
                    out, enterpriseConfig.getFieldValue(WifiEnterpriseConfig.PASSWORD_KEY),
                    encryptionUtil);
            XmlUtil.writeNextValue(out, XML_TAG_CLIENT_CERT,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.CLIENT_CERT_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_CA_CERT,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.CA_CERT_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_SUBJECT_MATCH,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.SUBJECT_MATCH_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_ENGINE,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.ENGINE_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_ENGINE_ID,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_PRIVATE_KEY_ID,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_ALT_SUBJECT_MATCH,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.ALTSUBJECT_MATCH_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_DOM_SUFFIX_MATCH,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.DOM_SUFFIX_MATCH_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_CA_PATH,
                    enterpriseConfig.getFieldValue(WifiEnterpriseConfig.CA_PATH_KEY));
            XmlUtil.writeNextValue(out, XML_TAG_EAP_METHOD, enterpriseConfig.getEapMethod());
            XmlUtil.writeNextValue(out, XML_TAG_PHASE2_METHOD, enterpriseConfig.getPhase2Method());
            XmlUtil.writeNextValue(out, XML_TAG_PLMN, enterpriseConfig.getPlmn());
            XmlUtil.writeNextValue(out, XML_TAG_REALM, enterpriseConfig.getRealm());
            XmlUtil.writeNextValue(out, XML_TAG_OCSP, enterpriseConfig.getOcsp());
            XmlUtil.writeNextValue(out,
                    XML_TAG_WAPI_CERT_SUITE, enterpriseConfig.getWapiCertSuite());
            XmlUtil.writeNextValue(out, XML_TAG_APP_INSTALLED_ROOT_CA_CERT,
                    enterpriseConfig.isAppInstalledCaCert());
            XmlUtil.writeNextValue(out, XML_TAG_APP_INSTALLED_PRIVATE_KEY,
                    enterpriseConfig.isAppInstalledDeviceKeyAndCert());
        }

        /**
         * Parses the data elements from the provided XML stream to a WifiEnterpriseConfig object.
         *
         * @param in XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @param shouldExpectEncryptedCredentials Whether to expect encrypted credentials or not.
         * @param encryptionUtil Instance of {@link EncryptedDataXmlUtil}.
         * @return WifiEnterpriseConfig object if parsing is successful, null otherwise.
         */
        public static WifiEnterpriseConfig parseFromXml(XmlPullParser in, int outerTagDepth,
                boolean shouldExpectEncryptedCredentials,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();

            // Loop through and parse out all the elements from the stream within this section.
            while (XmlUtilHelper.nextElementWithin(in, outerTagDepth)) {
                if (in.getAttributeValue(null, "name") != null) {
                    // Value elements.
                    String[] valueName = new String[1];
                    Object value = XmlUtil.readCurrentValue(in, valueName);
                    if (valueName[0] == null) {
                        throw new XmlPullParserException("Missing value name");
                    }
                    switch (valueName[0]) {
                        case XML_TAG_IDENTITY:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.IDENTITY_KEY, (String) value);
                            break;
                        case XML_TAG_ANON_IDENTITY:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.ANON_IDENTITY_KEY, (String) value);
                            break;
                        case XML_TAG_PASSWORD:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.PASSWORD_KEY, (String) value);
                            if (shouldExpectEncryptedCredentials
                                    && !TextUtils.isEmpty(enterpriseConfig.getFieldValue(
                                            WifiEnterpriseConfig.PASSWORD_KEY))) {
                                // Indicates that encryption of password failed when it was last
                                // written.
                                Log.e(TAG, "password value not expected");
                            }
                            break;
                        case XML_TAG_CLIENT_CERT:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.CLIENT_CERT_KEY, (String) value);
                            break;
                        case XML_TAG_CA_CERT:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.CA_CERT_KEY, (String) value);
                            break;
                        case XML_TAG_SUBJECT_MATCH:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.SUBJECT_MATCH_KEY, (String) value);
                            break;
                        case XML_TAG_ENGINE:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.ENGINE_KEY, (String) value);
                            break;
                        case XML_TAG_ENGINE_ID:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.ENGINE_ID_KEY, (String) value);
                            break;
                        case XML_TAG_PRIVATE_KEY_ID:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, (String) value);
                            break;
                        case XML_TAG_ALT_SUBJECT_MATCH:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.ALTSUBJECT_MATCH_KEY, (String) value);
                            break;
                        case XML_TAG_DOM_SUFFIX_MATCH:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.DOM_SUFFIX_MATCH_KEY, (String) value);
                            break;
                        case XML_TAG_CA_PATH:
                            enterpriseConfig.setFieldValue(
                                    WifiEnterpriseConfig.CA_PATH_KEY, (String) value);
                            break;
                        case XML_TAG_OCSP:
                            enterpriseConfig.setOcsp((int) value);
                            break;
                        case XML_TAG_EAP_METHOD:
                            enterpriseConfig.setEapMethod((int) value);
                            break;
                        case XML_TAG_PHASE2_METHOD:
                            enterpriseConfig.setPhase2Method((int) value);
                            break;
                        case XML_TAG_PLMN:
                            enterpriseConfig.setPlmn((String) value);
                            break;
                        case XML_TAG_REALM:
                            enterpriseConfig.setRealm((String) value);
                            break;
                        case XML_TAG_WAPI_CERT_SUITE:
                            enterpriseConfig.setWapiCertSuite((String) value);
                            break;
                        case XML_TAG_APP_INSTALLED_ROOT_CA_CERT:
                            enterpriseConfig.initIsAppInstalledCaCert((boolean) value);
                            break;
                        case XML_TAG_APP_INSTALLED_PRIVATE_KEY:
                            enterpriseConfig.initIsAppInstalledDeviceKeyAndCert((boolean) value);
                            break;
                        default:
                            Log.w(TAG, "Ignoring unknown value name found: " + valueName[0]);
                            break;
                    }
                } else {
                    String tagName = in.getName();
                    if (tagName == null) {
                        throw new XmlPullParserException("Unexpected null tag found");
                    }
                    switch (tagName) {
                        case XML_TAG_PASSWORD:
                            if (!shouldExpectEncryptedCredentials || encryptionUtil == null) {
                                throw new XmlPullParserException(
                                        "encrypted password section not expected");
                            }
                            EncryptedData encryptedData =
                                    EncryptedDataXmlUtil.parseFromXml(in, outerTagDepth + 1);
                            byte[] passwordBytes = encryptionUtil.decrypt(encryptedData);
                            if (passwordBytes == null) {
                                Log.wtf(TAG, "Decryption of password failed");
                            } else {
                                enterpriseConfig.setFieldValue(
                                        WifiEnterpriseConfig.PASSWORD_KEY,
                                        new String(passwordBytes));
                            }
                            break;
                        default:
                            Log.w(TAG, "Ignoring unknown tag name found: " + tagName);
                            break;
                    }
                }
            }
            return enterpriseConfig;
        }
    }

    /**
     * Utility class to serialize and deseriaize {@link EncryptedData} object to XML &
     * vice versa. This is used by {@link com.android.server.wifi.WifiConfigStore} module.
     */
    public static class EncryptedDataXmlUtil {
        /**
         * List of XML tags corresponding to EncryptedData object elements.
         */
        private static final String XML_TAG_ENCRYPTED_DATA = "EncryptedData";
        private static final String XML_TAG_IV = "IV";

        /**
         * Write the NetworkSelectionStatus data elements from the provided status to the XML
         * stream.
         *
         * @param out           XmlSerializer instance pointing to the XML stream.
         * @param encryptedData EncryptedData object to be serialized.
         */
        public static void writeToXml(XmlSerializer out, EncryptedData encryptedData)
                throws XmlPullParserException, IOException {
            XmlUtil.writeNextValue(
                    out, XML_TAG_ENCRYPTED_DATA, encryptedData.getEncryptedData());
            XmlUtil.writeNextValue(out, XML_TAG_IV, encryptedData.getIv());
        }

        /**
         * Parses the EncryptedData data elements from the provided XML stream to a
         * EncryptedData object.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return EncryptedData object if parsing is successful, null otherwise.
         */
        public static EncryptedData parseFromXml(XmlPullParser in, int outerTagDepth)
                throws XmlPullParserException, IOException {
            byte[] encryptedData = null;
            byte[] iv = null;

            // Loop through and parse out all the elements from the stream within this section.
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (valueName[0] == null) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_ENCRYPTED_DATA:
                        encryptedData = (byte[]) value;
                        break;
                    case XML_TAG_IV:
                        iv = (byte[]) value;
                        break;
                    default:
                        Log.e(TAG, "Unknown value name found: " + valueName[0]);
                        break;
                }
            }
            return new EncryptedData(encryptedData, iv);
        }
    }

    public static boolean nextElementWithin(XmlPullParser parser, int outerDepth)
            throws IOException, XmlPullParserException {
        return XmlUtilHelper.nextElementWithin(parser, outerDepth);
    }

    /**
     * Utility class to serialize and deseriaize {@link SoftApConfiguration} object to XML
     * & vice versa. This is used by both {@link com.android.server.wifi.SoftApStore}  modules.
     */
    public static class SoftApConfigurationXmlUtil {
        /**
         * List of XML tags corresponding to SoftApConfiguration object elements.
         */
        public static final String XML_TAG_CLIENT_MACADDRESS = "ClientMacAddress";

        /**
         * Parses the client list from the provided XML stream to a ArrayList object.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return ArrayList object if parsing is successful, null otherwise.
         */
        public static List<MacAddress> parseClientListFromXml(XmlPullParser in,
                int outerTagDepth) throws XmlPullParserException, IOException,
                IllegalArgumentException {
            List<MacAddress> clientList = new ArrayList<>();
            // Loop through and parse out all the elements from the stream within this section.
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (valueName[0] == null) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_CLIENT_MACADDRESS:
                        MacAddress client = MacAddress.fromString((String) value);
                        clientList.add(client);
                        break;
                    default:
                        Log.e(TAG, "Unknown value name found: " + valueName[0]);
                        break;
                }
            }
            return clientList;
        }

        /**
         * Write the SoftApConfiguration client control list data elements
         * from the provided list to the XML stream.
         *
         * @param out           XmlSerializer instance pointing to the XML stream.
         * @param clientList Client list object to be serialized.
         */
        public static void writeClientListToXml(XmlSerializer out, List<MacAddress> clientList)
                throws XmlPullParserException, IOException {
            for (MacAddress mac: clientList) {
                XmlUtil.writeNextValue(out, XML_TAG_CLIENT_MACADDRESS, mac.toString());
            }
        }
    }
}

