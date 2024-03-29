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

package com.android.server.wifi;

import static android.net.wifi.WifiConfiguration.MeteredOverride;

import static java.lang.StrictMath.toIntExact;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.wifi.EAPConstants;
import android.net.wifi.IOnWifiUsabilityStatsListener;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiUsabilityStatsEntry.ProbeStatus;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.aware.WifiAwareMetrics;
import com.android.server.wifi.hotspot2.ANQPNetworkKey;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.PasspointProvider;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.p2p.WifiP2pMetrics;
import com.android.server.wifi.proto.WifiStatsLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.proto.nano.WifiMetricsProto.CarrierWifiMetrics;
import com.android.server.wifi.proto.nano.WifiMetricsProto.ConnectToNetworkNotificationAndActionCount;
import com.android.server.wifi.proto.nano.WifiMetricsProto.DeviceMobilityStatePnoScanStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.ExperimentValues;
import com.android.server.wifi.proto.nano.WifiMetricsProto.FirstConnectAfterBootStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.FirstConnectAfterBootStats.Attempt;
import com.android.server.wifi.proto.nano.WifiMetricsProto.HealthMonitorMetrics;
import com.android.server.wifi.proto.nano.WifiMetricsProto.InitPartialScanStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.LinkProbeStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.LinkProbeStats.ExperimentProbeCounts;
import com.android.server.wifi.proto.nano.WifiMetricsProto.LinkProbeStats.LinkProbeFailureReasonCount;
import com.android.server.wifi.proto.nano.WifiMetricsProto.LinkSpeedCount;
import com.android.server.wifi.proto.nano.WifiMetricsProto.MeteredNetworkStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.NetworkDisableReason;
import com.android.server.wifi.proto.nano.WifiMetricsProto.NetworkSelectionExperimentDecisions;
import com.android.server.wifi.proto.nano.WifiMetricsProto.PasspointProfileTypeCount;
import com.android.server.wifi.proto.nano.WifiMetricsProto.PasspointProvisionStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.PasspointProvisionStats.ProvisionFailureCount;
import com.android.server.wifi.proto.nano.WifiMetricsProto.PnoScanMetrics;
import com.android.server.wifi.proto.nano.WifiMetricsProto.SoftApConnectedClientsEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.StaEvent.ConfigInfo;
import com.android.server.wifi.proto.nano.WifiMetricsProto.TargetNetworkInfo;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserActionEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserReactionToApprovalUiEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserReactionToApprovalUiEvent.UserReaction;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiLinkLayerUsageStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiLockStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiNetworkRequestApiLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiNetworkSuggestionApiLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiNetworkSuggestionApiLog.SuggestionAppCount;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiStatus;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiToggleStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiUsabilityStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiUsabilityStatsEntry;
import com.android.server.wifi.rtt.RttMetrics;
import com.android.server.wifi.scanner.KnownBandsChannelHelper;
import com.android.server.wifi.util.ExternalCallbackTracker;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.IntCounter;
import com.android.server.wifi.util.IntHistogram;
import com.android.server.wifi.util.MetricsUtils;
import com.android.server.wifi.util.ObjectCounter;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.wifi.resources.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Provides storage for wireless connectivity metrics, as they are generated.
 * Metrics logged by this class include:
 *   Aggregated connection stats (num of connections, num of failures, ...)
 *   Discrete connection event stats (time, duration, failure codes, ...)
 *   Router details (technology type, authentication type, ...)
 *   Scan stats
 */
public class WifiMetrics {
    private static final String TAG = "WifiMetrics";
    private static final boolean DBG = false;
    /**
     * Clamp the RSSI poll counts to values between [MIN,MAX]_RSSI_POLL
     */
    private static final int MAX_RSSI_POLL = 0;
    private static final int MIN_RSSI_POLL = -127;
    public static final int MAX_RSSI_DELTA = 127;
    public static final int MIN_RSSI_DELTA = -127;
    /** Minimum link speed (Mbps) to count for link_speed_counts */
    public static final int MIN_LINK_SPEED_MBPS = 0;
    /** Maximum time period between ScanResult and RSSI poll to generate rssi delta datapoint */
    public static final long TIMEOUT_RSSI_DELTA_MILLIS =  3000;
    private static final int MIN_WIFI_SCORE = 0;
    private static final int MAX_WIFI_SCORE = ConnectedScore.WIFI_MAX_SCORE;
    private static final int MIN_WIFI_USABILITY_SCORE = 0; // inclusive
    private static final int MAX_WIFI_USABILITY_SCORE = 100; // inclusive
    @VisibleForTesting
    static final int LOW_WIFI_SCORE = 50; // Mobile data score
    @VisibleForTesting
    static final int LOW_WIFI_USABILITY_SCORE = 50; // Mobile data score
    private final Object mLock = new Object();
    private static final int MAX_CONNECTION_EVENTS = 256;
    // Largest bucket in the NumConnectableNetworkCount histogram,
    // anything large will be stored in this bucket
    public static final int MAX_CONNECTABLE_SSID_NETWORK_BUCKET = 20;
    public static final int MAX_CONNECTABLE_BSSID_NETWORK_BUCKET = 50;
    public static final int MAX_TOTAL_SCAN_RESULT_SSIDS_BUCKET = 100;
    public static final int MAX_TOTAL_SCAN_RESULTS_BUCKET = 250;
    public static final int MAX_TOTAL_PASSPOINT_APS_BUCKET = 50;
    public static final int MAX_TOTAL_PASSPOINT_UNIQUE_ESS_BUCKET = 20;
    public static final int MAX_PASSPOINT_APS_PER_UNIQUE_ESS_BUCKET = 50;
    public static final int MAX_TOTAL_80211MC_APS_BUCKET = 20;
    private static final int CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER = 1000;
    // Max limit for number of soft AP related events, extra events will be dropped.
    private static final int MAX_NUM_SOFT_AP_EVENTS = 256;
    // Maximum number of WifiIsUnusableEvent
    public static final int MAX_UNUSABLE_EVENTS = 20;
    // Minimum time wait before generating next WifiIsUnusableEvent from data stall
    public static final int MIN_DATA_STALL_WAIT_MS = 120 * 1000; // 2 minutes
    // Max number of WifiUsabilityStatsEntry elements to store in the ringbuffer.
    public static final int MAX_WIFI_USABILITY_STATS_ENTRIES_LIST_SIZE = 40;
    // Max number of WifiUsabilityStats elements to store for each type.
    public static final int MAX_WIFI_USABILITY_STATS_LIST_SIZE_PER_TYPE = 10;
    // Max number of WifiUsabilityStats per labeled type to upload to server
    public static final int MAX_WIFI_USABILITY_STATS_PER_TYPE_TO_UPLOAD = 2;
    public static final int NUM_WIFI_USABILITY_STATS_ENTRIES_PER_WIFI_GOOD = 100;
    public static final int MIN_WIFI_GOOD_USABILITY_STATS_PERIOD_MS = 1000 * 3600; // 1 hour
    // Histogram for WifiConfigStore IO duration times. Indicates the following 5 buckets (in ms):
    //   < 50
    //   [50, 100)
    //   [100, 150)
    //   [150, 200)
    //   [200, 300)
    //   >= 300
    private static final int[] WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS =
            {50, 100, 150, 200, 300};
    // Minimum time wait before generating a LABEL_GOOD stats after score breaching low.
    public static final int MIN_SCORE_BREACH_TO_GOOD_STATS_WAIT_TIME_MS = 60 * 1000; // 1 minute
    // Maximum time that a score breaching low event stays valid.
    public static final int VALIDITY_PERIOD_OF_SCORE_BREACH_LOW_MS = 90 * 1000; // 1.5 minutes

    private Clock mClock;
    private boolean mScreenOn;
    private int mWifiState;
    private WifiAwareMetrics mWifiAwareMetrics;
    private RttMetrics mRttMetrics;
    private final PnoScanMetrics mPnoScanMetrics = new PnoScanMetrics();
    private final WifiLinkLayerUsageStats mWifiLinkLayerUsageStats = new WifiLinkLayerUsageStats();
    private final ExperimentValues mExperimentValues = new ExperimentValues();
    private Handler mHandler;
    private ScoringParams mScoringParams;
    private WifiConfigManager mWifiConfigManager;
    private BssidBlocklistMonitor mBssidBlocklistMonitor;
    private WifiNetworkSelector mWifiNetworkSelector;
    private PasspointManager mPasspointManager;
    private Context mContext;
    private FrameworkFacade mFacade;
    private WifiDataStall mWifiDataStall;
    private WifiLinkLayerStats mLastLinkLayerStats;
    private WifiHealthMonitor mWifiHealthMonitor;
    private WifiScoreCard mWifiScoreCard;
    private String mLastBssid;
    private int mLastFrequency = -1;
    private int mSeqNumInsideFramework = 0;
    private int mLastWifiUsabilityScore = -1;
    private int mLastWifiUsabilityScoreNoReset = -1;
    private int mLastPredictionHorizonSec = -1;
    private int mLastPredictionHorizonSecNoReset = -1;
    private int mSeqNumToFramework = -1;
    @ProbeStatus private int mProbeStatusSinceLastUpdate =
            android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
    private int mProbeElapsedTimeSinceLastUpdateMs = -1;
    private int mProbeMcsRateSinceLastUpdate = -1;
    private long mScoreBreachLowTimeMillis = -1;

    public static final int MAX_STA_EVENTS = 768;
    @VisibleForTesting static final int MAX_USER_ACTION_EVENTS = 200;
    private LinkedList<StaEventWithTime> mStaEventList = new LinkedList<>();
    private LinkedList<UserActionEventWithTime> mUserActionEventList = new LinkedList<>();
    private WifiStatusBuilder mWifiStatusBuilder = new WifiStatusBuilder();
    private int mLastPollRssi = -127;
    private int mLastPollLinkSpeed = -1;
    private int mLastPollRxLinkSpeed = -1;
    private int mLastPollFreq = -1;
    private int mLastScore = -1;
    private boolean mAdaptiveConnectivityEnabled = true;

    /**
     * Metrics are stored within an instance of the WifiLog proto during runtime,
     * The ConnectionEvent, SystemStateEntries & ScanReturnEntries metrics are stored during
     * runtime in member lists of this WifiMetrics class, with the final WifiLog proto being pieced
     * together at dump-time
     */
    private final WifiMetricsProto.WifiLog mWifiLogProto = new WifiMetricsProto.WifiLog();
    /**
     * Session information that gets logged for every Wifi connection attempt.
     */
    private final List<ConnectionEvent> mConnectionEventList = new ArrayList<>();
    /**
     * The latest started (but un-ended) connection attempt
     */
    private ConnectionEvent mCurrentConnectionEvent;
    /**
     * Count of number of times each scan return code, indexed by WifiLog.ScanReturnCode
     */
    private final SparseIntArray mScanReturnEntries = new SparseIntArray();
    /**
     * Mapping of system state to the counts of scans requested in that wifi state * screenOn
     * combination. Indexed by WifiLog.WifiState * (1 + screenOn)
     */
    private final SparseIntArray mWifiSystemStateEntries = new SparseIntArray();
    /** Mapping of channel frequency to its RSSI distribution histogram **/
    private final Map<Integer, SparseIntArray> mRssiPollCountsMap = new HashMap<>();
    /** Mapping of RSSI scan-poll delta values to counts. */
    private final SparseIntArray mRssiDeltaCounts = new SparseIntArray();
    /** Mapping of link speed values to LinkSpeedCount objects. */
    private final SparseArray<LinkSpeedCount> mLinkSpeedCounts = new SparseArray<>();

    private final IntCounter mTxLinkSpeedCount2g = new IntCounter();
    private final IntCounter mTxLinkSpeedCount5gLow = new IntCounter();
    private final IntCounter mTxLinkSpeedCount5gMid = new IntCounter();
    private final IntCounter mTxLinkSpeedCount5gHigh = new IntCounter();
    private final IntCounter mTxLinkSpeedCount6gLow = new IntCounter();
    private final IntCounter mTxLinkSpeedCount6gMid = new IntCounter();
    private final IntCounter mTxLinkSpeedCount6gHigh = new IntCounter();

    private final IntCounter mRxLinkSpeedCount2g = new IntCounter();
    private final IntCounter mRxLinkSpeedCount5gLow = new IntCounter();
    private final IntCounter mRxLinkSpeedCount5gMid = new IntCounter();
    private final IntCounter mRxLinkSpeedCount5gHigh = new IntCounter();
    private final IntCounter mRxLinkSpeedCount6gLow = new IntCounter();
    private final IntCounter mRxLinkSpeedCount6gMid = new IntCounter();
    private final IntCounter mRxLinkSpeedCount6gHigh = new IntCounter();

    /** RSSI of the scan result for the last connection event*/
    private int mScanResultRssi = 0;
    /** Boot-relative timestamp when the last candidate scanresult was received, used to calculate
        RSSI deltas. -1 designates no candidate scanResult being tracked */
    private long mScanResultRssiTimestampMillis = -1;
    /** Mapping of alert reason to the respective alert count. */
    private final SparseIntArray mWifiAlertReasonCounts = new SparseIntArray();
    /**
     * Records the getElapsedSinceBootMillis (in seconds) that represents the beginning of data
     * capture for for this WifiMetricsProto
     */
    private long mRecordStartTimeSec;
    /** Mapping of Wifi Scores to counts */
    private final SparseIntArray mWifiScoreCounts = new SparseIntArray();
    /** Mapping of Wifi Usability Scores to counts */
    private final SparseIntArray mWifiUsabilityScoreCounts = new SparseIntArray();
    /** Mapping of SoftApManager start SoftAp return codes to counts */
    private final SparseIntArray mSoftApManagerReturnCodeCounts = new SparseIntArray();

    private final SparseIntArray mTotalSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mTotalBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenOrSavedSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenOrSavedBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedPasspointProviderProfilesInScanHistogram =
            new SparseIntArray();
    private final SparseIntArray mAvailableSavedPasspointProviderBssidsInScanHistogram =
            new SparseIntArray();

    private final IntCounter mInstalledPasspointProfileTypeForR1 = new IntCounter();
    private final IntCounter mInstalledPasspointProfileTypeForR2 = new IntCounter();

    /** Mapping of "Connect to Network" notifications to counts. */
    private final SparseIntArray mConnectToNetworkNotificationCount = new SparseIntArray();
    /** Mapping of "Connect to Network" notification user actions to counts. */
    private final SparseIntArray mConnectToNetworkNotificationActionCount = new SparseIntArray();
    private int mOpenNetworkRecommenderBlacklistSize = 0;
    private boolean mIsWifiNetworksAvailableNotificationOn = false;
    private int mNumOpenNetworkConnectMessageFailedToSend = 0;
    private int mNumOpenNetworkRecommendationUpdates = 0;
    /** List of soft AP events related to number of connected clients in tethered mode */
    private final List<SoftApConnectedClientsEvent> mSoftApEventListTethered = new ArrayList<>();
    /** List of soft AP events related to number of connected clients in local only mode */
    private final List<SoftApConnectedClientsEvent> mSoftApEventListLocalOnly = new ArrayList<>();

    private final SparseIntArray mObservedHotspotR1ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR3ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR1EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR3EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR1ApsPerEssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2ApsPerEssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR3ApsPerEssInScanHistogram = new SparseIntArray();

    private final SparseIntArray mObserved80211mcApInScanHistogram = new SparseIntArray();

    // link probing stats
    private final IntCounter mLinkProbeSuccessRssiCounts = new IntCounter(-85, -65);
    private final IntCounter mLinkProbeFailureRssiCounts = new IntCounter(-85, -65);
    private final IntCounter mLinkProbeSuccessLinkSpeedCounts = new IntCounter();
    private final IntCounter mLinkProbeFailureLinkSpeedCounts = new IntCounter();

    private static final int[] LINK_PROBE_TIME_SINCE_LAST_TX_SUCCESS_SECONDS_HISTOGRAM_BUCKETS =
            {5, 15, 45, 135};
    private final IntHistogram mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram =
            new IntHistogram(LINK_PROBE_TIME_SINCE_LAST_TX_SUCCESS_SECONDS_HISTOGRAM_BUCKETS);
    private final IntHistogram mLinkProbeFailureSecondsSinceLastTxSuccessHistogram =
            new IntHistogram(LINK_PROBE_TIME_SINCE_LAST_TX_SUCCESS_SECONDS_HISTOGRAM_BUCKETS);

    private static final int[] LINK_PROBE_ELAPSED_TIME_MS_HISTOGRAM_BUCKETS =
            {5, 10, 15, 20, 25, 50, 100, 200, 400, 800};
    private final IntHistogram mLinkProbeSuccessElapsedTimeMsHistogram = new IntHistogram(
            LINK_PROBE_ELAPSED_TIME_MS_HISTOGRAM_BUCKETS);
    private final IntCounter mLinkProbeFailureReasonCounts = new IntCounter();
    private final MeteredNetworkStatsBuilder mMeteredNetworkStatsBuilder =
            new MeteredNetworkStatsBuilder();

    /**
     * Maps a String link probe experiment ID to the number of link probes that were sent for this
     * experiment.
     */
    private final ObjectCounter<String> mLinkProbeExperimentProbeCounts = new ObjectCounter<>();
    private int mLinkProbeStaEventCount = 0;
    @VisibleForTesting static final int MAX_LINK_PROBE_STA_EVENTS = MAX_STA_EVENTS / 4;

    private final LinkedList<WifiUsabilityStatsEntry> mWifiUsabilityStatsEntriesList =
            new LinkedList<>();
    private final LinkedList<WifiUsabilityStats> mWifiUsabilityStatsListBad = new LinkedList<>();
    private final LinkedList<WifiUsabilityStats> mWifiUsabilityStatsListGood = new LinkedList<>();
    private int mWifiUsabilityStatsCounter = 0;
    private final Random mRand = new Random();
    private final ExternalCallbackTracker<IOnWifiUsabilityStatsListener> mOnWifiUsabilityListeners;

    private final SparseArray<DeviceMobilityStatePnoScanStats> mMobilityStatePnoStatsMap =
            new SparseArray<>();
    private int mCurrentDeviceMobilityState;
    /**
     * The timestamp of the start of the current device mobility state.
     */
    private long mCurrentDeviceMobilityStateStartMs;
    /**
     * The timestamp of when the PNO scan started in the current device mobility state.
     */
    private long mCurrentDeviceMobilityStatePnoScanStartMs;

    /** Wifi power metrics*/
    private WifiPowerMetrics mWifiPowerMetrics;

    /** Wifi Wake metrics */
    private final WifiWakeMetrics mWifiWakeMetrics = new WifiWakeMetrics();

    /** Wifi P2p metrics */
    private final WifiP2pMetrics mWifiP2pMetrics;

    /** DPP */
    private final DppMetrics mDppMetrics;

    /** WifiConfigStore read duration histogram. */
    private SparseIntArray mWifiConfigStoreReadDurationHistogram = new SparseIntArray();

    /** WifiConfigStore write duration histogram. */
    private SparseIntArray mWifiConfigStoreWriteDurationHistogram = new SparseIntArray();

    /** New  API surface metrics */
    private final WifiNetworkRequestApiLog mWifiNetworkRequestApiLog =
            new WifiNetworkRequestApiLog();
    private static final int[] NETWORK_REQUEST_API_MATCH_SIZE_HISTOGRAM_BUCKETS =
            {0, 1, 5, 10};
    private final IntHistogram mWifiNetworkRequestApiMatchSizeHistogram =
            new IntHistogram(NETWORK_REQUEST_API_MATCH_SIZE_HISTOGRAM_BUCKETS);

    private final WifiNetworkSuggestionApiLog mWifiNetworkSuggestionApiLog =
            new WifiNetworkSuggestionApiLog();
    private static final int[] NETWORK_SUGGESTION_API_LIST_SIZE_HISTOGRAM_BUCKETS =
            {5, 20, 50, 100, 500};
    private final IntHistogram mWifiNetworkSuggestionApiListSizeHistogram =
            new IntHistogram(NETWORK_SUGGESTION_API_LIST_SIZE_HISTOGRAM_BUCKETS);
    private final IntCounter mWifiNetworkSuggestionApiAppTypeCounter = new IntCounter();
    private final List<UserReaction> mUserApprovalSuggestionAppUiReactionList =
            new ArrayList<>();
    private final List<UserReaction> mUserApprovalCarrierUiReactionList =
            new ArrayList<>();

    private final WifiLockStats mWifiLockStats = new WifiLockStats();
    private static final int[] WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS =
            {1, 10, 60, 600, 3600};
    private final WifiToggleStats mWifiToggleStats = new WifiToggleStats();
    private BssidBlocklistStats mBssidBlocklistStats = new BssidBlocklistStats();

    private final IntHistogram mWifiLockHighPerfAcqDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);
    private final IntHistogram mWifiLockLowLatencyAcqDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);

    private final IntHistogram mWifiLockHighPerfActiveSessionDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);
    private final IntHistogram mWifiLockLowLatencyActiveSessionDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);

    /**
     * (experiment1Id, experiment2Id) =>
     *     (sameSelectionNumChoicesCounter, differentSelectionNumChoicesCounter)
     */
    private Map<Pair<Integer, Integer>, NetworkSelectionExperimentResults>
            mNetworkSelectionExperimentPairNumChoicesCounts = new ArrayMap<>();

    private int mNetworkSelectorExperimentId;

    /**
     * Tracks the nominator for each network (i.e. which entity made the suggestion to connect).
     * This object should not be cleared.
     */
    private final SparseIntArray mNetworkIdToNominatorId = new SparseIntArray();

    /** passpoint provision success count */
    private int mNumProvisionSuccess = 0;

    /** Mapping of failure code to the respective passpoint provision failure count. */
    private final IntCounter mPasspointProvisionFailureCounts = new IntCounter();

    // Connection duration stats collected while link layer stats reports are on
    private final ConnectionDurationStats mConnectionDurationStats = new ConnectionDurationStats();

    private static final int[] CHANNEL_UTILIZATION_BUCKETS =
            {25, 50, 75, 100, 125, 150, 175, 200, 225};

    private final IntHistogram mChannelUtilizationHistogram2G =
            new IntHistogram(CHANNEL_UTILIZATION_BUCKETS);

    private final IntHistogram mChannelUtilizationHistogramAbove2G =
            new IntHistogram(CHANNEL_UTILIZATION_BUCKETS);

    private static final int[] THROUGHPUT_MBPS_BUCKETS =
            {1, 5, 10, 15, 25, 50, 100, 150, 200, 300, 450, 600, 800, 1200, 1600};
    private final IntHistogram mTxThroughputMbpsHistogram2G =
            new IntHistogram(THROUGHPUT_MBPS_BUCKETS);
    private final IntHistogram mRxThroughputMbpsHistogram2G =
            new IntHistogram(THROUGHPUT_MBPS_BUCKETS);
    private final IntHistogram mTxThroughputMbpsHistogramAbove2G =
            new IntHistogram(THROUGHPUT_MBPS_BUCKETS);
    private final IntHistogram mRxThroughputMbpsHistogramAbove2G =
            new IntHistogram(THROUGHPUT_MBPS_BUCKETS);

    // Init partial scan metrics
    private int mInitPartialScanTotalCount;
    private int mInitPartialScanSuccessCount;
    private int mInitPartialScanFailureCount;
    private static final int[] INIT_PARTIAL_SCAN_HISTOGRAM_BUCKETS =
            {1, 3, 5, 10};
    private final IntHistogram mInitPartialScanSuccessHistogram =
            new IntHistogram(INIT_PARTIAL_SCAN_HISTOGRAM_BUCKETS);
    private final IntHistogram mInitPartialScanFailureHistogram =
            new IntHistogram(INIT_PARTIAL_SCAN_HISTOGRAM_BUCKETS);

    // Wi-Fi off metrics
    private final WifiOffMetrics mWifiOffMetrics = new WifiOffMetrics();

    private final SoftApConfigLimitationMetrics mSoftApConfigLimitationMetrics =
            new SoftApConfigLimitationMetrics();

    private final CarrierWifiMetrics mCarrierWifiMetrics =
            new CarrierWifiMetrics();

    @Nullable
    private FirstConnectAfterBootStats mFirstConnectAfterBootStats =
            new FirstConnectAfterBootStats();
    private boolean mIsFirstConnectionAttemptComplete = false;

    @VisibleForTesting
    static class NetworkSelectionExperimentResults {
        public static final int MAX_CHOICES = 10;

        public IntCounter sameSelectionNumChoicesCounter = new IntCounter(0, MAX_CHOICES);
        public IntCounter differentSelectionNumChoicesCounter = new IntCounter(0, MAX_CHOICES);

        @Override
        public String toString() {
            return "NetworkSelectionExperimentResults{"
                    + "sameSelectionNumChoicesCounter="
                    + sameSelectionNumChoicesCounter
                    + ", differentSelectionNumChoicesCounter="
                    + differentSelectionNumChoicesCounter
                    + '}';
        }
    }

    class RouterFingerPrint {
        private WifiMetricsProto.RouterFingerPrint mRouterFingerPrintProto;
        RouterFingerPrint() {
            mRouterFingerPrintProto = new WifiMetricsProto.RouterFingerPrint();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            synchronized (mLock) {
                sb.append("mConnectionEvent.roamType=" + mRouterFingerPrintProto.roamType);
                sb.append(", mChannelInfo=" + mRouterFingerPrintProto.channelInfo);
                sb.append(", mDtim=" + mRouterFingerPrintProto.dtim);
                sb.append(", mAuthentication=" + mRouterFingerPrintProto.authentication);
                sb.append(", mHidden=" + mRouterFingerPrintProto.hidden);
                sb.append(", mRouterTechnology=" + mRouterFingerPrintProto.routerTechnology);
                sb.append(", mSupportsIpv6=" + mRouterFingerPrintProto.supportsIpv6);
                sb.append(", mEapMethod=" + mRouterFingerPrintProto.eapMethod);
                sb.append(", mAuthPhase2Method=" + mRouterFingerPrintProto.authPhase2Method);
                sb.append(", mOcspType=" + mRouterFingerPrintProto.ocspType);
                sb.append(", mPmkCache=" + mRouterFingerPrintProto.pmkCacheEnabled);
                sb.append(", mMaxSupportedTxLinkSpeedMbps=" + mRouterFingerPrintProto
                        .maxSupportedTxLinkSpeedMbps);
                sb.append(", mMaxSupportedRxLinkSpeedMbps=" + mRouterFingerPrintProto
                        .maxSupportedRxLinkSpeedMbps);
            }
            return sb.toString();
        }
        public void updateFromWifiConfiguration(WifiConfiguration config) {
            synchronized (mLock) {
                if (config != null) {
                    // Is this a hidden network
                    mRouterFingerPrintProto.hidden = config.hiddenSSID;
                    // Config may not have a valid dtimInterval set yet, in which case dtim will be zero
                    // (These are only populated from beacon frame scan results, which are returned as
                    // scan results from the chip far less frequently than Probe-responses)
                    if (config.dtimInterval > 0) {
                        mRouterFingerPrintProto.dtim = config.dtimInterval;
                    }
                    mCurrentConnectionEvent.mConfigSsid = config.SSID;
                    // Get AuthType information from config (We do this again from ScanResult after
                    // associating with BSSID)
                    if (config.allowedKeyManagement != null
                            && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
                    } else if (config.isEnterprise()) {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
                    } else {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
                    }
                    mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                            .passpoint = config.isPasspoint();
                    // If there's a ScanResult candidate associated with this config already, get it and
                    // log (more accurate) metrics from it
                    ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                    if (candidate != null) {
                        updateMetricsFromScanResult(candidate);
                    }
                    if (mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                            .authentication == WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE
                            && config.enterpriseConfig != null) {
                        int eapMethod = config.enterpriseConfig.getEapMethod();
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .eapMethod = getEapMethodProto(eapMethod);
                        int phase2Method = config.enterpriseConfig.getPhase2Method();
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authPhase2Method = getAuthPhase2MethodProto(phase2Method);
                        int ocspType = config.enterpriseConfig.getOcsp();
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .ocspType = getOcspTypeProto(ocspType);
                    }
                }
            }
        }

        public void setPmkCache(boolean isEnabled) {
            synchronized (mLock) {
                mRouterFingerPrintProto.pmkCacheEnabled = isEnabled;
            }
        }

        public void setMaxSupportedLinkSpeedMbps(int maxSupportedTxLinkSpeedMbps,
                int maxSupportedRxLinkSpeedMbps) {
            synchronized (mLock) {
                mRouterFingerPrintProto.maxSupportedTxLinkSpeedMbps = maxSupportedTxLinkSpeedMbps;
                mRouterFingerPrintProto.maxSupportedRxLinkSpeedMbps = maxSupportedRxLinkSpeedMbps;
            }
        }
    }
    private int getEapMethodProto(int eapMethod) {
        switch (eapMethod) {
            case WifiEnterpriseConfig.Eap.WAPI_CERT:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_WAPI_CERT;
            case WifiEnterpriseConfig.Eap.TLS:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_TLS;
            case WifiEnterpriseConfig.Eap.UNAUTH_TLS:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_UNAUTH_TLS;
            case WifiEnterpriseConfig.Eap.PEAP:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_PEAP;
            case WifiEnterpriseConfig.Eap.PWD:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_PWD;
            case WifiEnterpriseConfig.Eap.TTLS:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_TTLS;
            case WifiEnterpriseConfig.Eap.SIM:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_SIM;
            case WifiEnterpriseConfig.Eap.AKA:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_AKA;
            case WifiEnterpriseConfig.Eap.AKA_PRIME:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_AKA_PRIME;
            default:
                return WifiMetricsProto.RouterFingerPrint.TYPE_EAP_UNKNOWN;
        }
    }
    private int getAuthPhase2MethodProto(int phase2Method) {
        switch (phase2Method) {
            case WifiEnterpriseConfig.Phase2.PAP:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_PAP;
            case WifiEnterpriseConfig.Phase2.MSCHAP:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_MSCHAP;
            case WifiEnterpriseConfig.Phase2.MSCHAPV2:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_MSCHAPV2;
            case WifiEnterpriseConfig.Phase2.GTC:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_GTC;
            case WifiEnterpriseConfig.Phase2.SIM:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_SIM;
            case WifiEnterpriseConfig.Phase2.AKA:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_AKA;
            case WifiEnterpriseConfig.Phase2.AKA_PRIME:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_AKA_PRIME;
            default:
                return WifiMetricsProto.RouterFingerPrint.TYPE_PHASE2_NONE;
        }
    }

    private int getOcspTypeProto(int ocspType) {
        switch (ocspType) {
            case WifiEnterpriseConfig.OCSP_NONE:
                return WifiMetricsProto.RouterFingerPrint.TYPE_OCSP_NONE;
            case WifiEnterpriseConfig.OCSP_REQUEST_CERT_STATUS:
                return WifiMetricsProto.RouterFingerPrint.TYPE_OCSP_REQUEST_CERT_STATUS;
            case WifiEnterpriseConfig.OCSP_REQUIRE_CERT_STATUS:
                return WifiMetricsProto.RouterFingerPrint.TYPE_OCSP_REQUIRE_CERT_STATUS;
            case WifiEnterpriseConfig.OCSP_REQUIRE_ALL_NON_TRUSTED_CERTS_STATUS:
                return WifiMetricsProto.RouterFingerPrint
                        .TYPE_OCSP_REQUIRE_ALL_NON_TRUSTED_CERTS_STATUS;
            default:
                return WifiMetricsProto.RouterFingerPrint.TYPE_OCSP_NONE;
        }
    }

    class BssidBlocklistStats {
        public IntCounter networkSelectionFilteredBssidCount = new IntCounter();
        public int numHighMovementConnectionSkipped = 0;
        public int numHighMovementConnectionStarted = 0;

        public WifiMetricsProto.BssidBlocklistStats toProto() {
            WifiMetricsProto.BssidBlocklistStats proto = new WifiMetricsProto.BssidBlocklistStats();
            proto.networkSelectionFilteredBssidCount = networkSelectionFilteredBssidCount.toProto();
            proto.highMovementMultipleScansFeatureEnabled = mContext.getResources().getBoolean(
                    R.bool.config_wifiHighMovementNetworkSelectionOptimizationEnabled);
            proto.numHighMovementConnectionSkipped = numHighMovementConnectionSkipped;
            proto.numHighMovementConnectionStarted = numHighMovementConnectionStarted;
            return proto;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("networkSelectionFilteredBssidCount=" + networkSelectionFilteredBssidCount);
            sb.append(", highMovementMultipleScansFeatureEnabled="
                    + mContext.getResources().getBoolean(
                            R.bool.config_wifiHighMovementNetworkSelectionOptimizationEnabled));
            sb.append(", numHighMovementConnectionSkipped=" + numHighMovementConnectionSkipped);
            sb.append(", numHighMovementConnectionStarted=" + numHighMovementConnectionStarted);
            return sb.toString();
        }
    }

    class ConnectionDurationStats {
        private int mConnectionDurationCellularDataOffMs;
        private int mConnectionDurationSufficientThroughputMs;
        private int mConnectionDurationInSufficientThroughputMs;
        private int mConnectionDurationInSufficientThroughputDefaultWifiMs;

        public WifiMetricsProto.ConnectionDurationStats toProto() {
            WifiMetricsProto.ConnectionDurationStats proto =
                    new WifiMetricsProto.ConnectionDurationStats();
            proto.totalTimeSufficientThroughputMs = mConnectionDurationSufficientThroughputMs;
            proto.totalTimeInsufficientThroughputMs = mConnectionDurationInSufficientThroughputMs;
            proto.totalTimeInsufficientThroughputDefaultWifiMs =
                    mConnectionDurationInSufficientThroughputDefaultWifiMs;
            proto.totalTimeCellularDataOffMs = mConnectionDurationCellularDataOffMs;
            return proto;
        }
        public void clear() {
            mConnectionDurationCellularDataOffMs = 0;
            mConnectionDurationSufficientThroughputMs = 0;
            mConnectionDurationInSufficientThroughputMs = 0;
            mConnectionDurationInSufficientThroughputDefaultWifiMs = 0;
        }
        public void incrementDurationCount(int timeDeltaLastTwoPollsMs,
                boolean isThroughputSufficient, boolean isCellularDataAvailable,
                boolean isDefaultOnWifi) {
            if (!isCellularDataAvailable) {
                mConnectionDurationCellularDataOffMs += timeDeltaLastTwoPollsMs;
            } else {
                if (isThroughputSufficient) {
                    mConnectionDurationSufficientThroughputMs += timeDeltaLastTwoPollsMs;
                } else {
                    mConnectionDurationInSufficientThroughputMs += timeDeltaLastTwoPollsMs;
                    if (isDefaultOnWifi) {
                        mConnectionDurationInSufficientThroughputDefaultWifiMs +=
                                timeDeltaLastTwoPollsMs;
                    }
                }
            }
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("connectionDurationSufficientThroughputMs=")
                    .append(mConnectionDurationSufficientThroughputMs)
                    .append(", connectionDurationInSufficientThroughputMs=")
                    .append(mConnectionDurationInSufficientThroughputMs)
                    .append(", connectionDurationInSufficientThroughputDefaultWifiMs=")
                    .append(mConnectionDurationInSufficientThroughputDefaultWifiMs)
                    .append(", connectionDurationCellularDataOffMs=")
                    .append(mConnectionDurationCellularDataOffMs);
            return sb.toString();
        }
    }

    class WifiStatusBuilder {
        private int mNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        private boolean mConnected;
        private boolean mValidated;
        private int mRssi;
        private int mEstimatedTxKbps;
        private int mEstimatedRxKbps;
        private boolean mIsStuckDueToUserChoice;

        public void setNetworkId(int networkId) {
            mNetworkId = networkId;
        }

        public int getNetworkId() {
            return mNetworkId;
        }

        public void setConnected(boolean connected) {
            mConnected = connected;
        }

        public void setValidated(boolean validated) {
            mValidated = validated;
        }

        public void setRssi(int rssi) {
            mRssi = rssi;
        }

        public void setEstimatedTxKbps(int estimatedTxKbps) {
            mEstimatedTxKbps = estimatedTxKbps;
        }

        public void setEstimatedRxKbps(int estimatedRxKbps) {
            mEstimatedRxKbps = estimatedRxKbps;
        }

        public void setUserChoice(boolean userChoice) {
            mIsStuckDueToUserChoice = userChoice;
        }

        public WifiStatus toProto() {
            WifiStatus result = new WifiStatus();
            result.isConnected = mConnected;
            result.isValidated = mValidated;
            result.lastRssi = mRssi;
            result.estimatedTxKbps = mEstimatedTxKbps;
            result.estimatedRxKbps = mEstimatedRxKbps;
            result.isStuckDueToUserConnectChoice = mIsStuckDueToUserChoice;
            return result;
        }
    }

    private NetworkDisableReason convertToNetworkDisableReason(
            WifiConfiguration config, Set<Integer> bssidBlocklistReasons) {
        NetworkSelectionStatus status = config.getNetworkSelectionStatus();
        NetworkDisableReason result = new NetworkDisableReason();
        if (config.allowAutojoin) {
            if (!status.isNetworkEnabled()) {
                result.disableReason =
                        MetricsUtils.convertNetworkSelectionDisableReasonToWifiProtoEnum(
                                status.getNetworkSelectionDisableReason());
                if (status.isNetworkPermanentlyDisabled()) {
                    result.configPermanentlyDisabled = true;
                } else {
                    result.configTemporarilyDisabled = true;
                }
            }
        } else {
            result.disableReason = NetworkDisableReason.REASON_AUTO_JOIN_DISABLED;
            result.configPermanentlyDisabled = true;
        }

        int[] convertedBssidBlockReasons = bssidBlocklistReasons.stream()
                .mapToInt(i -> MetricsUtils.convertBssidBlocklistReasonToWifiProtoEnum(i))
                .toArray();
        if (convertedBssidBlockReasons.length > 0) {
            result.bssidDisableReasons = convertedBssidBlockReasons;
        }
        return result;
    }

    class UserActionEventWithTime {
        private UserActionEvent mUserActionEvent;
        private long mWallClockTimeMs = 0; // wall clock time for debugging only

        UserActionEventWithTime(int eventType, TargetNetworkInfo targetNetworkInfo) {
            mUserActionEvent = new UserActionEvent();
            mUserActionEvent.eventType = eventType;
            mUserActionEvent.startTimeMillis = mClock.getElapsedSinceBootMillis();
            mWallClockTimeMs = mClock.getWallClockMillis();
            mUserActionEvent.targetNetworkInfo = targetNetworkInfo;
            mUserActionEvent.wifiStatus = mWifiStatusBuilder.toProto();
        }

        UserActionEventWithTime(int eventType, int targetNetId) {
            this(eventType, null);
            if (targetNetId >= 0) {
                WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(targetNetId);
                if (config != null) {
                    TargetNetworkInfo networkInfo = new TargetNetworkInfo();
                    networkInfo.isEphemeral = config.isEphemeral();
                    networkInfo.isPasspoint = config.isPasspoint();
                    mUserActionEvent.targetNetworkInfo = networkInfo;
                    mUserActionEvent.networkDisableReason = convertToNetworkDisableReason(
                            config, mBssidBlocklistMonitor.getFailureReasonsForSsid(config.SSID));
                }
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(mWallClockTimeMs);
            sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            String eventType = "UNKNOWN";
            switch (mUserActionEvent.eventType) {
                case UserActionEvent.EVENT_FORGET_WIFI:
                    eventType = "EVENT_FORGET_WIFI";
                    break;
                case UserActionEvent.EVENT_DISCONNECT_WIFI:
                    eventType = "EVENT_DISCONNECT_WIFI";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_METERED:
                    eventType = "EVENT_CONFIGURE_METERED_STATUS_METERED";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_UNMETERED:
                    eventType = "EVENT_CONFIGURE_METERED_STATUS_UNMETERED";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_AUTO:
                    eventType = "EVENT_CONFIGURE_METERED_STATUS_AUTO";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_ON:
                    eventType = "EVENT_CONFIGURE_MAC_RANDOMIZATION_ON";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_OFF:
                    eventType = "EVENT_CONFIGURE_MAC_RANDOMIZATION_OFF";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_ON:
                    eventType = "EVENT_CONFIGURE_AUTO_CONNECT_ON";
                    break;
                case UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_OFF:
                    eventType = "EVENT_CONFIGURE_AUTO_CONNECT_OFF";
                    break;
                case UserActionEvent.EVENT_TOGGLE_WIFI_ON:
                    eventType = "EVENT_TOGGLE_WIFI_ON";
                    break;
                case UserActionEvent.EVENT_TOGGLE_WIFI_OFF:
                    eventType = "EVENT_TOGGLE_WIFI_OFF";
                    break;
                case UserActionEvent.EVENT_MANUAL_CONNECT:
                    eventType = "EVENT_MANUAL_CONNECT";
                    break;
                case UserActionEvent.EVENT_ADD_OR_UPDATE_NETWORK:
                    eventType = "EVENT_ADD_OR_UPDATE_NETWORK";
                    break;
            }
            sb.append(" eventType=").append(eventType);
            sb.append(" startTimeMillis=").append(mUserActionEvent.startTimeMillis);
            TargetNetworkInfo networkInfo = mUserActionEvent.targetNetworkInfo;
            if (networkInfo != null) {
                sb.append(" isEphemeral=").append(networkInfo.isEphemeral);
                sb.append(" isPasspoint=").append(networkInfo.isPasspoint);
            }
            WifiStatus wifiStatus = mUserActionEvent.wifiStatus;
            if (wifiStatus != null) {
                sb.append("\nWifiStatus: isConnected=").append(wifiStatus.isConnected);
                sb.append(" isValidated=").append(wifiStatus.isValidated);
                sb.append(" lastRssi=").append(wifiStatus.lastRssi);
                sb.append(" estimatedTxKbps=").append(wifiStatus.estimatedTxKbps);
                sb.append(" estimatedRxKbps=").append(wifiStatus.estimatedRxKbps);
                sb.append(" isStuckDueToUserConnectChoice=")
                        .append(wifiStatus.isStuckDueToUserConnectChoice);
            }
            NetworkDisableReason disableReason = mUserActionEvent.networkDisableReason;
            if (disableReason != null) {
                sb.append("\nNetworkDisableReason: DisableReason=")
                        .append(disableReason.disableReason);
                sb.append(" configTemporarilyDisabled=")
                        .append(disableReason.configTemporarilyDisabled);
                sb.append(" configPermanentlyDisabled=")
                        .append(disableReason.configPermanentlyDisabled);
                sb.append(" bssidDisableReasons=")
                        .append(Arrays.toString(disableReason.bssidDisableReasons));
            }
            return sb.toString();
        }

        public UserActionEvent toProto() {
            return mUserActionEvent;
        }
    }

    /**
     * Log event, tracking the start time, end time and result of a wireless connection attempt.
     */
    class ConnectionEvent {
        WifiMetricsProto.ConnectionEvent mConnectionEvent;
        //<TODO> Move these constants into a wifi.proto Enum, and create a new Failure Type field
        //covering more than just l2 failures. see b/27652362
        /**
         * Failure codes, used for the 'level_2_failure_code' Connection event field (covers a lot
         * more failures than just l2 though, since the proto does not have a place to log
         * framework failures)
         */
        // Failure is unknown
        public static final int FAILURE_UNKNOWN = 0;
        // NONE
        public static final int FAILURE_NONE = 1;
        // ASSOCIATION_REJECTION_EVENT
        public static final int FAILURE_ASSOCIATION_REJECTION = 2;
        // AUTHENTICATION_FAILURE_EVENT
        public static final int FAILURE_AUTHENTICATION_FAILURE = 3;
        // SSID_TEMP_DISABLED (Also Auth failure)
        public static final int FAILURE_SSID_TEMP_DISABLED = 4;
        // reconnect() or reassociate() call to WifiNative failed
        public static final int FAILURE_CONNECT_NETWORK_FAILED = 5;
        // NETWORK_DISCONNECTION_EVENT
        public static final int FAILURE_NETWORK_DISCONNECTION = 6;
        // NEW_CONNECTION_ATTEMPT before previous finished
        public static final int FAILURE_NEW_CONNECTION_ATTEMPT = 7;
        // New connection attempt to the same network & bssid
        public static final int FAILURE_REDUNDANT_CONNECTION_ATTEMPT = 8;
        // Roam Watchdog timer triggered (Roaming timed out)
        public static final int FAILURE_ROAM_TIMEOUT = 9;
        // DHCP failure
        public static final int FAILURE_DHCP = 10;
        // ASSOCIATION_TIMED_OUT
        public static final int FAILURE_ASSOCIATION_TIMED_OUT = 11;

        RouterFingerPrint mRouterFingerPrint;
        private long mRealStartTime;
        private long mRealEndTime;
        private String mConfigSsid;
        private String mConfigBssid;
        private int mWifiState;
        private boolean mScreenOn;

        private ConnectionEvent() {
            mConnectionEvent = new WifiMetricsProto.ConnectionEvent();
            mRealEndTime = 0;
            mRealStartTime = 0;
            mRouterFingerPrint = new RouterFingerPrint();
            mConnectionEvent.routerFingerprint = mRouterFingerPrint.mRouterFingerPrintProto;
            mConfigSsid = "<NULL>";
            mConfigBssid = "<NULL>";
            mWifiState = WifiMetricsProto.WifiLog.WIFI_UNKNOWN;
            mScreenOn = false;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("startTime=");
            Calendar c = Calendar.getInstance();
            synchronized (mLock) {
                c.setTimeInMillis(mConnectionEvent.startTimeMillis);
                sb.append(mConnectionEvent.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", SSID=");
                sb.append(mConfigSsid);
                sb.append(", BSSID=");
                sb.append(mConfigBssid);
                sb.append(", durationMillis=");
                sb.append(mConnectionEvent.durationTakenToConnectMillis);
                sb.append(", roamType=");
                switch(mConnectionEvent.roamType) {
                    case 1:
                        sb.append("ROAM_NONE");
                        break;
                    case 2:
                        sb.append("ROAM_DBDC");
                        break;
                    case 3:
                        sb.append("ROAM_ENTERPRISE");
                        break;
                    case 4:
                        sb.append("ROAM_USER_SELECTED");
                        break;
                    case 5:
                        sb.append("ROAM_UNRELATED");
                        break;
                    default:
                        sb.append("ROAM_UNKNOWN");
                }
                sb.append(", connectionResult=");
                sb.append(mConnectionEvent.connectionResult);
                sb.append(", level2FailureCode=");
                switch(mConnectionEvent.level2FailureCode) {
                    case FAILURE_NONE:
                        sb.append("NONE");
                        break;
                    case FAILURE_ASSOCIATION_REJECTION:
                        sb.append("ASSOCIATION_REJECTION");
                        break;
                    case FAILURE_AUTHENTICATION_FAILURE:
                        sb.append("AUTHENTICATION_FAILURE");
                        break;
                    case FAILURE_SSID_TEMP_DISABLED:
                        sb.append("SSID_TEMP_DISABLED");
                        break;
                    case FAILURE_CONNECT_NETWORK_FAILED:
                        sb.append("CONNECT_NETWORK_FAILED");
                        break;
                    case FAILURE_NETWORK_DISCONNECTION:
                        sb.append("NETWORK_DISCONNECTION");
                        break;
                    case FAILURE_NEW_CONNECTION_ATTEMPT:
                        sb.append("NEW_CONNECTION_ATTEMPT");
                        break;
                    case FAILURE_REDUNDANT_CONNECTION_ATTEMPT:
                        sb.append("REDUNDANT_CONNECTION_ATTEMPT");
                        break;
                    case FAILURE_ROAM_TIMEOUT:
                        sb.append("ROAM_TIMEOUT");
                        break;
                    case FAILURE_DHCP:
                        sb.append("DHCP");
                        break;
                    case FAILURE_ASSOCIATION_TIMED_OUT:
                        sb.append("ASSOCIATION_TIMED_OUT");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", connectivityLevelFailureCode=");
                switch(mConnectionEvent.connectivityLevelFailureCode) {
                    case WifiMetricsProto.ConnectionEvent.HLF_NONE:
                        sb.append("NONE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_DHCP:
                        sb.append("DHCP");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_NO_INTERNET:
                        sb.append("NO_INTERNET");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_UNWANTED:
                        sb.append("UNWANTED");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", signalStrength=");
                sb.append(mConnectionEvent.signalStrength);
                sb.append(", wifiState=");
                switch(mWifiState) {
                    case WifiMetricsProto.WifiLog.WIFI_DISABLED:
                        sb.append("WIFI_DISABLED");
                        break;
                    case WifiMetricsProto.WifiLog.WIFI_DISCONNECTED:
                        sb.append("WIFI_DISCONNECTED");
                        break;
                    case WifiMetricsProto.WifiLog.WIFI_ASSOCIATED:
                        sb.append("WIFI_ASSOCIATED");
                        break;
                    default:
                        sb.append("WIFI_UNKNOWN");
                        break;
                }
                sb.append(", screenOn=");
                sb.append(mScreenOn);
                sb.append(", mRouterFingerprint=");
                sb.append(mRouterFingerPrint.toString());
                sb.append(", useRandomizedMac=");
                sb.append(mConnectionEvent.useRandomizedMac);
                sb.append(", useAggressiveMac=" + mConnectionEvent.useAggressiveMac);
                sb.append(", connectionNominator=");
                switch (mConnectionEvent.connectionNominator) {
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN:
                        sb.append("NOMINATOR_UNKNOWN");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_MANUAL:
                        sb.append("NOMINATOR_MANUAL");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED:
                        sb.append("NOMINATOR_SAVED");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SUGGESTION:
                        sb.append("NOMINATOR_SUGGESTION");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_PASSPOINT:
                        sb.append("NOMINATOR_PASSPOINT");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_CARRIER:
                        sb.append("NOMINATOR_CARRIER");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_EXTERNAL_SCORED:
                        sb.append("NOMINATOR_EXTERNAL_SCORED");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SPECIFIER:
                        sb.append("NOMINATOR_SPECIFIER");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED_USER_CONNECT_CHOICE:
                        sb.append("NOMINATOR_SAVED_USER_CONNECT_CHOICE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_OPEN_NETWORK_AVAILABLE:
                        sb.append("NOMINATOR_OPEN_NETWORK_AVAILABLE");
                        break;
                    default:
                        sb.append(String.format("UnrecognizedNominator(%d)",
                                mConnectionEvent.connectionNominator));
                }
                sb.append(", networkSelectorExperimentId=");
                sb.append(mConnectionEvent.networkSelectorExperimentId);
                sb.append(", numBssidInBlocklist=" + mConnectionEvent.numBssidInBlocklist);
                sb.append(", level2FailureReason=");
                switch(mConnectionEvent.level2FailureReason) {
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_NONE:
                        sb.append("AUTH_FAILURE_NONE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_TIMEOUT:
                        sb.append("AUTH_FAILURE_TIMEOUT");
                        break;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_WRONG_PSWD:
                        sb.append("AUTH_FAILURE_WRONG_PSWD");
                        break;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_EAP_FAILURE:
                        sb.append("AUTH_FAILURE_EAP_FAILURE");
                        break;
                    default:
                        sb.append("FAILURE_REASON_UNKNOWN");
                        break;
                }
                sb.append(", networkType=");
                switch(mConnectionEvent.networkType) {
                    case WifiMetricsProto.ConnectionEvent.TYPE_UNKNOWN:
                        sb.append("TYPE_UNKNOWN");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_WPA2:
                        sb.append("TYPE_WPA2");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_WPA3:
                        sb.append("TYPE_WPA3");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_PASSPOINT:
                        sb.append("TYPE_PASSPOINT");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_EAP:
                        sb.append("TYPE_EAP");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_OWE:
                        sb.append("TYPE_OWE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_OPEN:
                        sb.append("TYPE_OPEN");
                        break;
                    case WifiMetricsProto.ConnectionEvent.TYPE_WAPI:
                        sb.append("TYPE_WAPI");
                        break;
                }
                sb.append(", networkCreator=");
                switch (mConnectionEvent.networkCreator) {
                    case WifiMetricsProto.ConnectionEvent.CREATOR_UNKNOWN:
                        sb.append("CREATOR_UNKNOWN");
                        break;
                    case WifiMetricsProto.ConnectionEvent.CREATOR_USER:
                        sb.append("CREATOR_USER");
                        break;
                    case WifiMetricsProto.ConnectionEvent.CREATOR_CARRIER:
                        sb.append("CREATOR_CARRIER");
                        break;
                }
                sb.append(", numConsecutiveConnectionFailure="
                        + mConnectionEvent.numConsecutiveConnectionFailure);
                sb.append(", isOsuProvisioned=" + mConnectionEvent.isOsuProvisioned);
            }
            return sb.toString();
        }
    }

    class WifiOffMetrics {
        public int numWifiOff = 0;
        public int numWifiOffDeferring = 0;
        public int numWifiOffDeferringTimeout = 0;
        public final IntCounter wifiOffDeferringTimeHistogram = new IntCounter();

        public WifiMetricsProto.WifiOffMetrics toProto() {
            WifiMetricsProto.WifiOffMetrics proto =
                    new WifiMetricsProto.WifiOffMetrics();
            proto.numWifiOff = numWifiOff;
            proto.numWifiOffDeferring = numWifiOffDeferring;
            proto.numWifiOffDeferringTimeout = numWifiOffDeferringTimeout;
            proto.wifiOffDeferringTimeHistogram = wifiOffDeferringTimeHistogram.toProto();
            return proto;
        }

        public void clear() {
            numWifiOff = 0;
            numWifiOffDeferring = 0;
            numWifiOffDeferringTimeout = 0;
            wifiOffDeferringTimeHistogram.clear();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("numWifiOff=")
                    .append(numWifiOff)
                    .append(", numWifiOffDeferring=")
                    .append(numWifiOffDeferring)
                    .append(", numWifiOffDeferringTimeout=")
                    .append(numWifiOffDeferringTimeout)
                    .append(", wifiOffDeferringTimeHistogram=")
                    .append(wifiOffDeferringTimeHistogram);
            return sb.toString();
        }
    }

    class SoftApConfigLimitationMetrics {
        // Collect the number of softap security setting reset to default during the restore
        public int numSecurityTypeResetToDefault = 0;
        // Collect the number of softap max client setting reset to default during the restore
        public int numMaxClientSettingResetToDefault = 0;
        // Collect the number of softap client control setting reset to default during the restore
        public int numClientControlByUserResetToDefault = 0;
        // Collect the max client setting when reach it cause client is blocked
        public final IntCounter maxClientSettingWhenReachHistogram = new IntCounter();

        public WifiMetricsProto.SoftApConfigLimitationMetrics toProto() {
            WifiMetricsProto.SoftApConfigLimitationMetrics proto =
                    new WifiMetricsProto.SoftApConfigLimitationMetrics();
            proto.numSecurityTypeResetToDefault = numSecurityTypeResetToDefault;
            proto.numMaxClientSettingResetToDefault = numMaxClientSettingResetToDefault;
            proto.numClientControlByUserResetToDefault = numClientControlByUserResetToDefault;
            proto.maxClientSettingWhenReachHistogram = maxClientSettingWhenReachHistogram.toProto();
            return proto;
        }

        public void clear() {
            numSecurityTypeResetToDefault = 0;
            numMaxClientSettingResetToDefault = 0;
            numClientControlByUserResetToDefault = 0;
            maxClientSettingWhenReachHistogram.clear();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("numSecurityTypeResetToDefault=")
                    .append(numSecurityTypeResetToDefault)
                    .append(", numMaxClientSettingResetToDefault=")
                    .append(numMaxClientSettingResetToDefault)
                    .append(", numClientControlByUserResetToDefault=")
                    .append(numClientControlByUserResetToDefault)
                    .append(", maxClientSettingWhenReachHistogram=")
                    .append(maxClientSettingWhenReachHistogram);
            return sb.toString();
        }
    }

    class CarrierWifiMetrics {
        public int numConnectionSuccess = 0;
        public int numConnectionAuthFailure = 0;
        public int numConnectionNonAuthFailure = 0;

        public WifiMetricsProto.CarrierWifiMetrics toProto() {
            WifiMetricsProto.CarrierWifiMetrics proto =
                    new WifiMetricsProto.CarrierWifiMetrics();
            proto.numConnectionSuccess = numConnectionSuccess;
            proto.numConnectionAuthFailure = numConnectionAuthFailure;
            proto.numConnectionNonAuthFailure = numConnectionNonAuthFailure;
            return proto;
        }

        public void clear() {
            numConnectionSuccess = 0;
            numConnectionAuthFailure = 0;
            numConnectionNonAuthFailure = 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("numConnectionSuccess=")
                    .append(numConnectionSuccess)
                    .append(", numConnectionAuthFailure=")
                    .append(numConnectionAuthFailure)
                    .append(", numConnectionNonAuthFailure")
                    .append(numConnectionNonAuthFailure);
            return sb.toString();
        }
    }

    public WifiMetrics(Context context, FrameworkFacade facade, Clock clock, Looper looper,
            WifiAwareMetrics awareMetrics, RttMetrics rttMetrics,
            WifiPowerMetrics wifiPowerMetrics, WifiP2pMetrics wifiP2pMetrics,
            DppMetrics dppMetrics) {
        mContext = context;
        mFacade = facade;
        mClock = clock;
        mCurrentConnectionEvent = null;
        mScreenOn = true;
        mWifiState = WifiMetricsProto.WifiLog.WIFI_DISABLED;
        mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
        mWifiAwareMetrics = awareMetrics;
        mRttMetrics = rttMetrics;
        mWifiPowerMetrics = wifiPowerMetrics;
        mWifiP2pMetrics = wifiP2pMetrics;
        mDppMetrics = dppMetrics;
        mHandler = new Handler(looper) {
            public void handleMessage(Message msg) {
                synchronized (mLock) {
                    processMessage(msg);
                }
            }
        };

        mCurrentDeviceMobilityState = WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN;
        DeviceMobilityStatePnoScanStats unknownStateStats =
                getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
        unknownStateStats.numTimesEnteredState++;
        mCurrentDeviceMobilityStateStartMs = mClock.getElapsedSinceBootMillis();
        mCurrentDeviceMobilityStatePnoScanStartMs = -1;
        mOnWifiUsabilityListeners =
                new ExternalCallbackTracker<IOnWifiUsabilityStatsListener>(mHandler);
    }

    /** Sets internal ScoringParams member */
    public void setScoringParams(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    /** Sets internal WifiConfigManager member */
    public void setWifiConfigManager(WifiConfigManager wifiConfigManager) {
        mWifiConfigManager = wifiConfigManager;
    }

    /** Sets internal WifiNetworkSelector member */
    public void setWifiNetworkSelector(WifiNetworkSelector wifiNetworkSelector) {
        mWifiNetworkSelector = wifiNetworkSelector;
    }

    /** Sets internal PasspointManager member */
    public void setPasspointManager(PasspointManager passpointManager) {
        mPasspointManager = passpointManager;
    }

    /** Sets internal WifiDataStall member */
    public void setWifiDataStall(WifiDataStall wifiDataStall) {
        mWifiDataStall = wifiDataStall;
    }

    /** Sets internal BssidBlocklistMonitor member */
    public void setBssidBlocklistMonitor(BssidBlocklistMonitor bssidBlocklistMonitor) {
        mBssidBlocklistMonitor = bssidBlocklistMonitor;
    }

    /** Sets internal WifiHealthMonitor member */
    public void setWifiHealthMonitor(WifiHealthMonitor wifiHealthMonitor) {
        mWifiHealthMonitor = wifiHealthMonitor;
    }

    /** Sets internal WifiScoreCard member */
    public void setWifiScoreCard(WifiScoreCard wifiScoreCard) {
        mWifiScoreCard = wifiScoreCard;
    }

    /**
     * Increment cumulative counters for link layer stats.
     * @param newStats
     */
    public void incrementWifiLinkLayerUsageStats(WifiLinkLayerStats newStats) {
        if (newStats == null) {
            return;
        }
        if (mLastLinkLayerStats == null) {
            mLastLinkLayerStats = newStats;
            return;
        }
        if (!newLinkLayerStatsIsValid(mLastLinkLayerStats, newStats)) {
            // This could mean the radio chip is reset or the data is incorrectly reported.
            // Don't increment any counts and discard the possibly corrupt |newStats| completely.
            mLastLinkLayerStats = null;
            return;
        }
        mWifiLinkLayerUsageStats.loggingDurationMs +=
                (newStats.timeStampInMs - mLastLinkLayerStats.timeStampInMs);
        mWifiLinkLayerUsageStats.radioOnTimeMs += (newStats.on_time - mLastLinkLayerStats.on_time);
        mWifiLinkLayerUsageStats.radioTxTimeMs += (newStats.tx_time - mLastLinkLayerStats.tx_time);
        mWifiLinkLayerUsageStats.radioRxTimeMs += (newStats.rx_time - mLastLinkLayerStats.rx_time);
        mWifiLinkLayerUsageStats.radioScanTimeMs +=
                (newStats.on_time_scan - mLastLinkLayerStats.on_time_scan);
        mWifiLinkLayerUsageStats.radioNanScanTimeMs +=
                (newStats.on_time_nan_scan - mLastLinkLayerStats.on_time_nan_scan);
        mWifiLinkLayerUsageStats.radioBackgroundScanTimeMs +=
                (newStats.on_time_background_scan - mLastLinkLayerStats.on_time_background_scan);
        mWifiLinkLayerUsageStats.radioRoamScanTimeMs +=
                (newStats.on_time_roam_scan - mLastLinkLayerStats.on_time_roam_scan);
        mWifiLinkLayerUsageStats.radioPnoScanTimeMs +=
                (newStats.on_time_pno_scan - mLastLinkLayerStats.on_time_pno_scan);
        mWifiLinkLayerUsageStats.radioHs20ScanTimeMs +=
                (newStats.on_time_hs20_scan - mLastLinkLayerStats.on_time_hs20_scan);
        mLastLinkLayerStats = newStats;
    }

    private boolean newLinkLayerStatsIsValid(WifiLinkLayerStats oldStats,
            WifiLinkLayerStats newStats) {
        if (newStats.on_time < oldStats.on_time
                || newStats.tx_time < oldStats.tx_time
                || newStats.rx_time < oldStats.rx_time
                || newStats.on_time_scan < oldStats.on_time_scan) {
            return false;
        }
        return true;
    }

    /**
     * Increment total number of attempts to start a pno scan
     */
    public void incrementPnoScanStartAttemptCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanAttempts++;
        }
    }

    /**
     * Increment total number of attempts with pno scan failed
     */
    public void incrementPnoScanFailedCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanFailed++;
        }
    }

    /**
     * Increment number of times pno scan found a result
     */
    public void incrementPnoFoundNetworkEventCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoFoundNetworkEvents++;
        }
    }

    // Values used for indexing SystemStateEntries
    private static final int SCREEN_ON = 1;
    private static final int SCREEN_OFF = 0;

    /**
     * Create a new connection event and check if the new one overlaps with previous one.
     * Call when wifi attempts to make a new network connection
     * If there is a current 'un-ended' connection event, it will be ended with UNKNOWN connectivity
     * failure code.
     * Gathers and sets the RouterFingerPrint data as well
     *
     * @param config WifiConfiguration of the config used for the current connection attempt
     * @param roamType Roam type that caused connection attempt, see WifiMetricsProto.WifiLog.ROAM_X
     * @return The duration in ms since the last unfinished connection attempt,
     * or 0 if there is no unfinished connection
     */
    public int startConnectionEvent(
            WifiConfiguration config, String targetBSSID, int roamType) {
        synchronized (mLock) {
            int overlapWithLastConnectionMs = 0;
            if (mCurrentConnectionEvent != null) {
                overlapWithLastConnectionMs = (int) (mClock.getElapsedSinceBootMillis()
                        - mCurrentConnectionEvent.mRealStartTime);
                //Is this new Connection Event the same as the current one
                if (mCurrentConnectionEvent.mConfigSsid != null
                        && mCurrentConnectionEvent.mConfigBssid != null
                        && config != null
                        && mCurrentConnectionEvent.mConfigSsid.equals(config.SSID)
                        && (mCurrentConnectionEvent.mConfigBssid.equals("any")
                        || mCurrentConnectionEvent.mConfigBssid.equals(targetBSSID))) {
                    mCurrentConnectionEvent.mConfigBssid = targetBSSID;
                    // End Connection Event due to new connection attempt to the same network
                    endConnectionEvent(ConnectionEvent.FAILURE_REDUNDANT_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                } else {
                    // End Connection Event due to new connection attempt to different network
                    endConnectionEvent(ConnectionEvent.FAILURE_NEW_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                }
            }
            //If past maximum connection events, start removing the oldest
            while(mConnectionEventList.size() >= MAX_CONNECTION_EVENTS) {
                mConnectionEventList.remove(0);
            }
            mCurrentConnectionEvent = new ConnectionEvent();
            mCurrentConnectionEvent.mConnectionEvent.startTimeMillis =
                    mClock.getWallClockMillis();
            mCurrentConnectionEvent.mConfigBssid = targetBSSID;
            mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            mCurrentConnectionEvent.mConnectionEvent.networkSelectorExperimentId =
                    mNetworkSelectorExperimentId;
            mCurrentConnectionEvent.mRouterFingerPrint.updateFromWifiConfiguration(config);
            mCurrentConnectionEvent.mConfigBssid = "any";
            mCurrentConnectionEvent.mRealStartTime = mClock.getElapsedSinceBootMillis();
            mCurrentConnectionEvent.mWifiState = mWifiState;
            mCurrentConnectionEvent.mScreenOn = mScreenOn;
            mConnectionEventList.add(mCurrentConnectionEvent);
            mScanResultRssiTimestampMillis = -1;
            if (config != null) {
                mCurrentConnectionEvent.mConnectionEvent.useRandomizedMac =
                        config.macRandomizationSetting
                        == WifiConfiguration.RANDOMIZATION_PERSISTENT;
                mCurrentConnectionEvent.mConnectionEvent.useAggressiveMac =
                        mWifiConfigManager.shouldUseAggressiveRandomization(config);
                mCurrentConnectionEvent.mConnectionEvent.connectionNominator =
                        mNetworkIdToNominatorId.get(config.networkId,
                                WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN);
                ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                if (candidate != null) {
                    // Cache the RSSI of the candidate, as the connection event level is updated
                    // from other sources (polls, bssid_associations) and delta requires the
                    // scanResult rssi
                    mScanResultRssi = candidate.level;
                    mScanResultRssiTimestampMillis = mClock.getElapsedSinceBootMillis();
                }
                mCurrentConnectionEvent.mConnectionEvent.numBssidInBlocklist =
                        mBssidBlocklistMonitor.updateAndGetNumBlockedBssidsForSsid(config.SSID);
                mCurrentConnectionEvent.mConnectionEvent.networkType =
                        WifiMetricsProto.ConnectionEvent.TYPE_UNKNOWN;
                mCurrentConnectionEvent.mConnectionEvent.isOsuProvisioned = false;
                if (config.isPasspoint()) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_PASSPOINT;
                    mCurrentConnectionEvent.mConnectionEvent.isOsuProvisioned =
                            !TextUtils.isEmpty(config.updateIdentifier);
                } else if (WifiConfigurationUtil.isConfigForSaeNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_WPA3;
                } else if (WifiConfigurationUtil.isConfigForWapiPskNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_WAPI;
                } else if (WifiConfigurationUtil.isConfigForWapiCertNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_WAPI;
                } else if (WifiConfigurationUtil.isConfigForPskNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_WPA2;
                } else if (WifiConfigurationUtil.isConfigForEapNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_EAP;
                } else if (WifiConfigurationUtil.isConfigForOweNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_OWE;
                } else if (WifiConfigurationUtil.isConfigForOpenNetwork(config)) {
                    mCurrentConnectionEvent.mConnectionEvent.networkType =
                            WifiMetricsProto.ConnectionEvent.TYPE_OPEN;
                }

                if (!config.fromWifiNetworkSuggestion) {
                    mCurrentConnectionEvent.mConnectionEvent.networkCreator =
                            WifiMetricsProto.ConnectionEvent.CREATOR_USER;
                } else if (config.carrierId != TelephonyManager.UNKNOWN_CARRIER_ID) {
                    mCurrentConnectionEvent.mConnectionEvent.networkCreator =
                            WifiMetricsProto.ConnectionEvent.CREATOR_CARRIER;
                } else {
                    mCurrentConnectionEvent.mConnectionEvent.networkCreator =
                            WifiMetricsProto.ConnectionEvent.CREATOR_UNKNOWN;
                }

                mCurrentConnectionEvent.mConnectionEvent.screenOn = mScreenOn;
                if (mCurrentConnectionEvent.mConfigSsid != null) {
                    WifiScoreCard.NetworkConnectionStats recentStats = mWifiScoreCard.lookupNetwork(
                            mCurrentConnectionEvent.mConfigSsid).getRecentStats();
                    mCurrentConnectionEvent.mConnectionEvent.numConsecutiveConnectionFailure =
                            recentStats.getCount(WifiScoreCard.CNT_CONSECUTIVE_CONNECTION_FAILURE);
                }
            }
            return overlapWithLastConnectionMs;
        }
    }

    /**
     * set the RoamType of the current ConnectionEvent (if any)
     */
    public void setConnectionEventRoamType(int roamType) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            }
        }
    }

    /**
     * Set AP related metrics from ScanDetail
     */
    public void setConnectionScanDetail(ScanDetail scanDetail) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null && scanDetail != null) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                ScanResult scanResult = scanDetail.getScanResult();
                //Ensure that we have a networkDetail, and that it corresponds to the currently
                //tracked connection attempt
                if (networkDetail != null && scanResult != null
                        && mCurrentConnectionEvent.mConfigSsid != null
                        && mCurrentConnectionEvent.mConfigSsid
                        .equals("\"" + networkDetail.getSSID() + "\"")) {
                    updateMetricsFromNetworkDetail(networkDetail);
                    updateMetricsFromScanResult(scanResult);
                }
            }
        }
    }

    /**
     * Set PMK cache status for a connection event
     */
    public void setConnectionPmkCache(boolean isEnabled) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                mCurrentConnectionEvent.mRouterFingerPrint.setPmkCache(isEnabled);
            }
        }
    }

    /**
     * Set the max link speed supported by current network
     */
    public void setConnectionMaxSupportedLinkSpeedMbps(int maxSupportedTxLinkSpeedMbps,
            int maxSupportedRxLinkSpeedMbps) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                mCurrentConnectionEvent.mRouterFingerPrint.setMaxSupportedLinkSpeedMbps(
                        maxSupportedTxLinkSpeedMbps, maxSupportedRxLinkSpeedMbps);
            }
        }
    }

    /**
     * End a Connection event record. Call when wifi connection attempt succeeds or fails.
     * If a Connection event has not been started and is active when .end is called, then this
     * method will do nothing.
     *
     * @param level2FailureCode Level 2 failure code returned by supplicant
     * @param connectivityFailureCode WifiMetricsProto.ConnectionEvent.HLF_X
     * @param level2FailureReason Breakdown of level2FailureCode with more detailed reason
     */
    public void endConnectionEvent(int level2FailureCode, int connectivityFailureCode,
            int level2FailureReason) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                boolean result = (level2FailureCode == 1)
                        && (connectivityFailureCode == WifiMetricsProto.ConnectionEvent.HLF_NONE);
                mCurrentConnectionEvent.mConnectionEvent.connectionResult = result ? 1 : 0;
                mCurrentConnectionEvent.mRealEndTime = mClock.getElapsedSinceBootMillis();
                mCurrentConnectionEvent.mConnectionEvent.durationTakenToConnectMillis = (int)
                        (mCurrentConnectionEvent.mRealEndTime
                        - mCurrentConnectionEvent.mRealStartTime);
                mCurrentConnectionEvent.mConnectionEvent.level2FailureCode = level2FailureCode;
                mCurrentConnectionEvent.mConnectionEvent.connectivityLevelFailureCode =
                        connectivityFailureCode;
                mCurrentConnectionEvent.mConnectionEvent.level2FailureReason = level2FailureReason;

                // Write metrics to statsd
                int wwFailureCode = getConnectionResultFailureCode(level2FailureCode,
                        level2FailureReason);
                if (wwFailureCode != -1) {
                    WifiStatsLog.write(WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED, result,
                            wwFailureCode, mCurrentConnectionEvent.mConnectionEvent.signalStrength);
                }
                // ConnectionEvent already added to ConnectionEvents List. Safe to null current here
                mCurrentConnectionEvent = null;
                if (!result) {
                    mScanResultRssiTimestampMillis = -1;
                }
                mWifiStatusBuilder.setConnected(result);
            }
        }
    }

    private int getConnectionResultFailureCode(int level2FailureCode, int level2FailureReason) {
        switch (level2FailureCode) {
            case ConnectionEvent.FAILURE_NONE:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_UNKNOWN;
            case ConnectionEvent.FAILURE_ASSOCIATION_TIMED_OUT:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_ASSOCIATION_TIMEOUT;
            case ConnectionEvent.FAILURE_ASSOCIATION_REJECTION:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_ASSOCIATION_REJECTION;
            case ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE:
                switch (level2FailureReason) {
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_EAP_FAILURE:
                        return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_AUTHENTICATION_EAP;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_WRONG_PSWD:
                        return -1;
                    default:
                        return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_AUTHENTICATION_GENERAL;
                }
            case ConnectionEvent.FAILURE_DHCP:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_DHCP;
            case ConnectionEvent.FAILURE_NETWORK_DISCONNECTION:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_NETWORK_DISCONNECTION;
            case ConnectionEvent.FAILURE_ROAM_TIMEOUT:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_ROAM_TIMEOUT;
            case ConnectionEvent.FAILURE_NEW_CONNECTION_ATTEMPT:
            case ConnectionEvent.FAILURE_REDUNDANT_CONNECTION_ATTEMPT:
                return -1;
            default:
                return WifiStatsLog.WIFI_CONNECTION_RESULT_REPORTED__FAILURE_CODE__FAILURE_UNKNOWN;
        }
    }

    /**
     * Set ConnectionEvent DTIM Interval (if set), and 802.11 Connection mode, from NetworkDetail
     */
    private void updateMetricsFromNetworkDetail(NetworkDetail networkDetail) {
        int dtimInterval = networkDetail.getDtimInterval();
        if (dtimInterval > 0) {
            mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.dtim =
                    dtimInterval;
        }
        int connectionWifiMode;
        switch (networkDetail.getWifiMode()) {
            case InformationElementUtil.WifiMode.MODE_UNDEFINED:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_UNKNOWN;
                break;
            case InformationElementUtil.WifiMode.MODE_11A:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_A;
                break;
            case InformationElementUtil.WifiMode.MODE_11B:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_B;
                break;
            case InformationElementUtil.WifiMode.MODE_11G:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_G;
                break;
            case InformationElementUtil.WifiMode.MODE_11N:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_N;
                break;
            case InformationElementUtil.WifiMode.MODE_11AC  :
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_AC;
                break;
            case InformationElementUtil.WifiMode.MODE_11AX  :
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_AX;
                break;
            default:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_OTHER;
                break;
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                .routerTechnology = connectionWifiMode;

        if (networkDetail.isMboSupported()) {
            mWifiLogProto.numConnectToNetworkSupportingMbo++;
            if (networkDetail.isOceSupported()) {
                mWifiLogProto.numConnectToNetworkSupportingOce++;
            }
        }
    }

    /**
     * Set ConnectionEvent RSSI and authentication type from ScanResult
     */
    private void updateMetricsFromScanResult(ScanResult scanResult) {
        mCurrentConnectionEvent.mConnectionEvent.signalStrength = scanResult.level;
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
        mCurrentConnectionEvent.mConfigBssid = scanResult.BSSID;
        if (scanResult.capabilities != null) {
            if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                    || ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)
                    || ScanResultUtil.isScanResultForEapSuiteBNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
            }
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.channelInfo =
                scanResult.frequency;
    }

    void setIsLocationEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isLocationEnabled = enabled;
        }
    }

    void setIsScanningAlwaysEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isScanningAlwaysEnabled = enabled;
        }
    }

    /**
     * Developer options toggle value for verbose logging.
     */
    public void setVerboseLoggingEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isVerboseLoggingEnabled = enabled;
        }
    }

    /**
     * Developer options toggle value for enhanced MAC randomization.
     */
    public void setEnhancedMacRandomizationForceEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isEnhancedMacRandomizationForceEnabled = enabled;
        }
    }

    /**
     * Wifi wake feature toggle.
     */
    public void setWifiWakeEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isWifiWakeEnabled = enabled;
        }
    }

    /**
     * Increment Non Empty Scan Results count
     */
    public void incrementNonEmptyScanResultCount() {
        if (DBG) Log.v(TAG, "incrementNonEmptyScanResultCount");
        synchronized (mLock) {
            mWifiLogProto.numNonEmptyScanResults++;
        }
    }

    /**
     * Increment Empty Scan Results count
     */
    public void incrementEmptyScanResultCount() {
        if (DBG) Log.v(TAG, "incrementEmptyScanResultCount");
        synchronized (mLock) {
            mWifiLogProto.numEmptyScanResults++;
        }
    }

    /**
     * Increment background scan count
     */
    public void incrementBackgroundScanCount() {
        if (DBG) Log.v(TAG, "incrementBackgroundScanCount");
        synchronized (mLock) {
            mWifiLogProto.numBackgroundScans++;
        }
    }

    /**
     * Get Background scan count
     */
    public int getBackgroundScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numBackgroundScans;
        }
    }

    /**
     * Increment oneshot scan count, and the associated WifiSystemScanStateCount entry
     */
    public void incrementOneshotScanCount() {
        synchronized (mLock) {
            mWifiLogProto.numOneshotScans++;
        }
        incrementWifiSystemScanStateCount(mWifiState, mScreenOn);
    }

    /**
     * Increment the count of oneshot scans that include DFS channels.
     */
    public void incrementOneshotScanWithDfsCount() {
        synchronized (mLock) {
            mWifiLogProto.numOneshotHasDfsChannelScans++;
        }
    }

    /**
     * Increment connectivity oneshot scan count.
     */
    public void incrementConnectivityOneshotScanCount() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityOneshotScans++;
        }
    }

    /**
     * Get oneshot scan count
     */
    public int getOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotScans;
        }
    }

    /**
     * Get connectivity oneshot scan count
     */
    public int getConnectivityOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numConnectivityOneshotScans;
        }
    }

    /**
     * Get the count of oneshot scan requests that included DFS channels.
     */
    public int getOneshotScanWithDfsCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotHasDfsChannelScans;
        }
    }

    /**
     * Increment oneshot scan count for external apps.
     */
    public void incrementExternalAppOneshotScanRequestsCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalAppOneshotScanRequests++;
        }
    }
    /**
     * Increment oneshot scan throttle count for external foreground apps.
     */
    public void incrementExternalForegroundAppOneshotScanRequestsThrottledCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled++;
        }
    }

    /**
     * Increment oneshot scan throttle count for external background apps.
     */
    public void incrementExternalBackgroundAppOneshotScanRequestsThrottledCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled++;
        }
    }

    private String returnCodeToString(int scanReturnCode) {
        switch(scanReturnCode){
            case WifiMetricsProto.WifiLog.SCAN_UNKNOWN:
                return "SCAN_UNKNOWN";
            case WifiMetricsProto.WifiLog.SCAN_SUCCESS:
                return "SCAN_SUCCESS";
            case WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED:
                return "SCAN_FAILURE_INTERRUPTED";
            case WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION:
                return "SCAN_FAILURE_INVALID_CONFIGURATION";
            case WifiMetricsProto.WifiLog.FAILURE_WIFI_DISABLED:
                return "FAILURE_WIFI_DISABLED";
            default:
                return "<UNKNOWN>";
        }
    }

    /**
     * Increment count of scan return code occurrence
     *
     * @param scanReturnCode Return code from scan attempt WifiMetricsProto.WifiLog.SCAN_X
     */
    public void incrementScanReturnEntry(int scanReturnCode, int countToAdd) {
        synchronized (mLock) {
            if (DBG) Log.v(TAG, "incrementScanReturnEntry " + returnCodeToString(scanReturnCode));
            int entry = mScanReturnEntries.get(scanReturnCode);
            entry += countToAdd;
            mScanReturnEntries.put(scanReturnCode, entry);
        }
    }
    /**
     * Get the count of this scanReturnCode
     * @param scanReturnCode that we are getting the count for
     */
    public int getScanReturnEntry(int scanReturnCode) {
        synchronized (mLock) {
            return mScanReturnEntries.get(scanReturnCode);
        }
    }

    private String wifiSystemStateToString(int state) {
        switch(state){
            case WifiMetricsProto.WifiLog.WIFI_UNKNOWN:
                return "WIFI_UNKNOWN";
            case WifiMetricsProto.WifiLog.WIFI_DISABLED:
                return "WIFI_DISABLED";
            case WifiMetricsProto.WifiLog.WIFI_DISCONNECTED:
                return "WIFI_DISCONNECTED";
            case WifiMetricsProto.WifiLog.WIFI_ASSOCIATED:
                return "WIFI_ASSOCIATED";
            default:
                return "default";
        }
    }

    /**
     * Increments the count of scans initiated by each wifi state, accounts for screenOn/Off
     *
     * @param state State of the system when scan was initiated, see WifiMetricsProto.WifiLog.WIFI_X
     * @param screenOn Is the screen on
     */
    public void incrementWifiSystemScanStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            if (DBG) {
                Log.v(TAG, "incrementWifiSystemScanStateCount " + wifiSystemStateToString(state)
                        + " " + screenOn);
            }
            int index = (state * 2) + (screenOn ? SCREEN_ON : SCREEN_OFF);
            int entry = mWifiSystemStateEntries.get(index);
            entry++;
            mWifiSystemStateEntries.put(index, entry);
        }
    }

    /**
     * Get the count of this system State Entry
     */
    public int getSystemStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            int index = state * 2 + (screenOn ? SCREEN_ON : SCREEN_OFF);
            return mWifiSystemStateEntries.get(index);
        }
    }

    /**
     * Increment number of times the Watchdog of Last Resort triggered, resetting the wifi stack
     */
    public void incrementNumLastResortWatchdogTriggers() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggers++;
        }
    }
    /**
     * @param count number of networks over bad association threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadAssociationNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad authentication threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadAuthenticationNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad dhcp threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadDhcpNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad other threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadOtherNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks seen when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogAvailableNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal += count;
        }
    }
    /**
     * Increment count of triggers with atleast one bad association network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadAssociation() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad authentication network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadAuthentication() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad dhcp network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadDhcp() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad other network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadOther() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadOther++;
        }
    }

    /**
     * Increment number of times connectivity watchdog confirmed pno is working
     */
    public void incrementNumConnectivityWatchdogPnoGood() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogPnoGood++;
        }
    }
    /**
     * Increment number of times connectivity watchdog found pno not working
     */
    public void incrementNumConnectivityWatchdogPnoBad() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogPnoBad++;
        }
    }
    /**
     * Increment number of times connectivity watchdog confirmed background scan is working
     */
    public void incrementNumConnectivityWatchdogBackgroundGood() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogBackgroundGood++;
        }
    }
    /**
     * Increment number of times connectivity watchdog found background scan not working
     */
    public void incrementNumConnectivityWatchdogBackgroundBad() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogBackgroundBad++;
        }
    }

    /**
     * Increment various poll related metrics, and cache performance data for StaEvent logging
     */
    public void handlePollResult(WifiInfo wifiInfo) {
        mLastPollRssi = wifiInfo.getRssi();
        mLastPollLinkSpeed = wifiInfo.getLinkSpeed();
        mLastPollFreq = wifiInfo.getFrequency();
        incrementRssiPollRssiCount(mLastPollFreq, mLastPollRssi);
        incrementLinkSpeedCount(mLastPollLinkSpeed, mLastPollRssi);
        mLastPollRxLinkSpeed = wifiInfo.getRxLinkSpeedMbps();
        incrementTxLinkSpeedBandCount(mLastPollLinkSpeed, mLastPollFreq);
        incrementRxLinkSpeedBandCount(mLastPollRxLinkSpeed, mLastPollFreq);
        mWifiStatusBuilder.setRssi(mLastPollRssi);
        mWifiStatusBuilder.setNetworkId(wifiInfo.getNetworkId());
    }

    /**
     * Increment occurence count of RSSI level from RSSI poll for the given frequency.
     * @param frequency (MHz)
     * @param rssi
     */
    @VisibleForTesting
    public void incrementRssiPollRssiCount(int frequency, int rssi) {
        if (!(rssi >= MIN_RSSI_POLL && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            if (!mRssiPollCountsMap.containsKey(frequency)) {
                mRssiPollCountsMap.put(frequency, new SparseIntArray());
            }
            SparseIntArray sparseIntArray = mRssiPollCountsMap.get(frequency);
            int count = sparseIntArray.get(rssi);
            sparseIntArray.put(rssi, count + 1);
            maybeIncrementRssiDeltaCount(rssi - mScanResultRssi);
        }
    }

    /**
     * Increment occurence count of difference between scan result RSSI and the first RSSI poll.
     * Ignores rssi values outside the bounds of [MIN_RSSI_DELTA, MAX_RSSI_DELTA]
     * mLock must be held when calling this method.
     */
    private void maybeIncrementRssiDeltaCount(int rssi) {
        // Check if this RSSI poll is close enough to a scan result RSSI to log a delta value
        if (mScanResultRssiTimestampMillis >= 0) {
            long timeDelta = mClock.getElapsedSinceBootMillis() - mScanResultRssiTimestampMillis;
            if (timeDelta <= TIMEOUT_RSSI_DELTA_MILLIS) {
                if (rssi >= MIN_RSSI_DELTA && rssi <= MAX_RSSI_DELTA) {
                    int count = mRssiDeltaCounts.get(rssi);
                    mRssiDeltaCounts.put(rssi, count + 1);
                }
            }
            mScanResultRssiTimestampMillis = -1;
        }
    }

    /**
     * Increment occurrence count of link speed.
     * Ignores link speed values that are lower than MIN_LINK_SPEED_MBPS
     * and rssi values outside the bounds of [MIN_RSSI_POLL, MAX_RSSI_POLL]
     */
    @VisibleForTesting
    public void incrementLinkSpeedCount(int linkSpeed, int rssi) {
        if (!(mContext.getResources().getBoolean(R.bool.config_wifiLinkSpeedMetricsEnabled)
                && linkSpeed >= MIN_LINK_SPEED_MBPS
                && rssi >= MIN_RSSI_POLL
                && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            LinkSpeedCount linkSpeedCount = mLinkSpeedCounts.get(linkSpeed);
            if (linkSpeedCount == null) {
                linkSpeedCount = new LinkSpeedCount();
                linkSpeedCount.linkSpeedMbps = linkSpeed;
                mLinkSpeedCounts.put(linkSpeed, linkSpeedCount);
            }
            linkSpeedCount.count++;
            linkSpeedCount.rssiSumDbm += Math.abs(rssi);
            linkSpeedCount.rssiSumOfSquaresDbmSq += rssi * rssi;
        }
    }

    /**
     * Increment occurrence count of Tx link speed for operating sub-band
     * Ignores link speed values that are lower than MIN_LINK_SPEED_MBPS
     * @param txLinkSpeed PHY layer Tx link speed in Mbps
     * @param frequency Channel frequency of beacon frames in MHz
     */
    @VisibleForTesting
    public void incrementTxLinkSpeedBandCount(int txLinkSpeed, int frequency) {
        if (!(mContext.getResources().getBoolean(R.bool.config_wifiLinkSpeedMetricsEnabled)
                && txLinkSpeed >= MIN_LINK_SPEED_MBPS)) {
            return;
        }
        synchronized (mLock) {
            if (ScanResult.is24GHz(frequency)) {
                mTxLinkSpeedCount2g.increment(txLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_LOW_END_FREQ) {
                mTxLinkSpeedCount5gLow.increment(txLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_MID_END_FREQ) {
                mTxLinkSpeedCount5gMid.increment(txLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_HIGH_END_FREQ) {
                mTxLinkSpeedCount5gHigh.increment(txLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_LOW_END_FREQ) {
                mTxLinkSpeedCount6gLow.increment(txLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_MID_END_FREQ) {
                mTxLinkSpeedCount6gMid.increment(txLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_HIGH_END_FREQ) {
                mTxLinkSpeedCount6gHigh.increment(txLinkSpeed);
            }
        }
    }

    /**
     * Increment occurrence count of Rx link speed for operating sub-band
     * Ignores link speed values that are lower than MIN_LINK_SPEED_MBPS
     * @param rxLinkSpeed PHY layer Tx link speed in Mbps
     * @param frequency Channel frequency of beacon frames in MHz
     */
    @VisibleForTesting
    public void incrementRxLinkSpeedBandCount(int rxLinkSpeed, int frequency) {
        if (!(mContext.getResources().getBoolean(R.bool.config_wifiLinkSpeedMetricsEnabled)
                && rxLinkSpeed >= MIN_LINK_SPEED_MBPS)) {
            return;
        }
        synchronized (mLock) {
            if (ScanResult.is24GHz(frequency)) {
                mRxLinkSpeedCount2g.increment(rxLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_LOW_END_FREQ) {
                mRxLinkSpeedCount5gLow.increment(rxLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_MID_END_FREQ) {
                mRxLinkSpeedCount5gMid.increment(rxLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_5_GHZ_HIGH_END_FREQ) {
                mRxLinkSpeedCount5gHigh.increment(rxLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_LOW_END_FREQ) {
                mRxLinkSpeedCount6gLow.increment(rxLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_MID_END_FREQ) {
                mRxLinkSpeedCount6gMid.increment(rxLinkSpeed);
            } else if (frequency <= KnownBandsChannelHelper.BAND_6_GHZ_HIGH_END_FREQ) {
                mRxLinkSpeedCount6gHigh.increment(rxLinkSpeed);
            }
        }
    }

    /**
     * Increment occurrence count of channel utilization
     * @param channelUtilization Channel utilization of current network
     * @param frequency Channel frequency of current network
     */
    @VisibleForTesting
    public void incrementChannelUtilizationCount(int channelUtilization, int frequency) {
        if (channelUtilization < InformationElementUtil.BssLoad.MIN_CHANNEL_UTILIZATION
                || channelUtilization > InformationElementUtil.BssLoad.MAX_CHANNEL_UTILIZATION) {
            return;
        }
        synchronized (mLock) {
            if (ScanResult.is24GHz(frequency)) {
                mChannelUtilizationHistogram2G.increment(channelUtilization);
            } else {
                mChannelUtilizationHistogramAbove2G.increment(channelUtilization);
            }
        }
    }

    /**
     * Increment occurrence count of Tx and Rx throughput
     * @param txThroughputKbps Tx throughput of current network in Kbps
     * @param rxThroughputKbps Rx throughput of current network in Kbps
     * @param frequency Channel frequency of current network in MHz
     */
    @VisibleForTesting
    public void incrementThroughputKbpsCount(int txThroughputKbps, int rxThroughputKbps,
            int frequency) {
        synchronized (mLock) {
            if (ScanResult.is24GHz(frequency)) {
                if (txThroughputKbps >= 0) {
                    mTxThroughputMbpsHistogram2G.increment(txThroughputKbps / 1000);
                }
                if (rxThroughputKbps >= 0) {
                    mRxThroughputMbpsHistogram2G.increment(rxThroughputKbps / 1000);
                }
            } else {
                if (txThroughputKbps >= 0) {
                    mTxThroughputMbpsHistogramAbove2G.increment(txThroughputKbps / 1000);
                }
                if (rxThroughputKbps >= 0) {
                    mRxThroughputMbpsHistogramAbove2G.increment(rxThroughputKbps / 1000);
                }
            }
            mWifiStatusBuilder.setEstimatedTxKbps(txThroughputKbps);
            mWifiStatusBuilder.setEstimatedRxKbps(rxThroughputKbps);
        }
    }

    /**
     * Increment count of Watchdog successes.
     */
    public void incrementNumLastResortWatchdogSuccesses() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogSuccesses++;
        }
    }

    /**
     * Increment the count of network connection failures that happened after watchdog has been
     * triggered.
     */
    public void incrementWatchdogTotalConnectionFailureCountAfterTrigger() {
        synchronized (mLock) {
            mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger++;
        }
    }

    /**
     * Sets the time taken for wifi to connect after a watchdog triggers a restart.
     * @param milliseconds
     */
    public void setWatchdogSuccessTimeDurationMs(long ms) {
        synchronized (mLock) {
            mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs = ms;
        }
    }

    /**
     * Increments the count of alerts by alert reason.
     *
     * @param reason The cause of the alert. The reason values are driver-specific.
     */
    private void incrementAlertReasonCount(int reason) {
        if (reason > WifiLoggerHal.WIFI_ALERT_REASON_MAX
                || reason < WifiLoggerHal.WIFI_ALERT_REASON_MIN) {
            reason = WifiLoggerHal.WIFI_ALERT_REASON_RESERVED;
        }
        synchronized (mLock) {
            int alertCount = mWifiAlertReasonCounts.get(reason);
            mWifiAlertReasonCounts.put(reason, alertCount + 1);
        }
    }

    /**
     * Counts all the different types of networks seen in a set of scan results
     */
    public void countScanResults(List<ScanDetail> scanDetails) {
        if (scanDetails == null) {
            return;
        }
        int totalResults = 0;
        int openNetworks = 0;
        int personalNetworks = 0;
        int enterpriseNetworks = 0;
        int hiddenNetworks = 0;
        int hotspot2r1Networks = 0;
        int hotspot2r2Networks = 0;
        int hotspot2r3Networks = 0;
        int enhacedOpenNetworks = 0;
        int wpa3PersonalNetworks = 0;
        int wpa3EnterpriseNetworks = 0;
        int wapiPersonalNetworks = 0;
        int wapiEnterpriseNetworks = 0;
        int mboSupportedNetworks = 0;
        int mboCellularDataAwareNetworks = 0;
        int oceSupportedNetworks = 0;
        int filsSupportedNetworks = 0;
        int band6gNetworks = 0;
        int standard11axNetworks = 0;

        for (ScanDetail scanDetail : scanDetails) {
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            ScanResult scanResult = scanDetail.getScanResult();
            totalResults++;
            if (networkDetail != null) {
                if (networkDetail.isHiddenBeaconFrame()) {
                    hiddenNetworks++;
                }
                if (networkDetail.getHSRelease() != null) {
                    if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                        hotspot2r1Networks++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                        hotspot2r2Networks++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R3) {
                        hotspot2r3Networks++;
                    }
                }
                if (networkDetail.isMboSupported()) {
                    mboSupportedNetworks++;
                    if (networkDetail.isMboCellularDataAware()) {
                        mboCellularDataAwareNetworks++;
                    }
                    if (networkDetail.isOceSupported()) {
                        oceSupportedNetworks++;
                    }
                }
                if (networkDetail.getWifiMode() == InformationElementUtil.WifiMode.MODE_11AX) {
                    standard11axNetworks++;
                }
            }
            if (scanResult != null && scanResult.capabilities != null) {
                if (ScanResultUtil.isScanResultForFilsSha256Network(scanResult)
                        || ScanResultUtil.isScanResultForFilsSha384Network(scanResult)) {
                    filsSupportedNetworks++;
                }
                if (scanResult.is6GHz()) {
                    band6gNetworks++;
                }
                if (ScanResultUtil.isScanResultForEapSuiteBNetwork(scanResult)) {
                    wpa3EnterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForWapiPskNetwork(scanResult)) {
                    wapiPersonalNetworks++;
                } else if (ScanResultUtil.isScanResultForWapiCertNetwork(scanResult)) {
                    wapiEnterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                    enterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
                    wpa3PersonalNetworks++;
                } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                        || ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                    personalNetworks++;
                } else if (ScanResultUtil.isScanResultForOweNetwork(scanResult)) {
                    enhacedOpenNetworks++;
                } else {
                    openNetworks++;
                }
            }
        }
        synchronized (mLock) {
            mWifiLogProto.numTotalScanResults += totalResults;
            mWifiLogProto.numOpenNetworkScanResults += openNetworks;
            mWifiLogProto.numLegacyPersonalNetworkScanResults += personalNetworks;
            mWifiLogProto.numLegacyEnterpriseNetworkScanResults += enterpriseNetworks;
            mWifiLogProto.numEnhancedOpenNetworkScanResults += enhacedOpenNetworks;
            mWifiLogProto.numWpa3PersonalNetworkScanResults += wpa3PersonalNetworks;
            mWifiLogProto.numWpa3EnterpriseNetworkScanResults += wpa3EnterpriseNetworks;
            mWifiLogProto.numWapiPersonalNetworkScanResults += wapiPersonalNetworks;
            mWifiLogProto.numWapiEnterpriseNetworkScanResults += wapiEnterpriseNetworks;
            mWifiLogProto.numHiddenNetworkScanResults += hiddenNetworks;
            mWifiLogProto.numHotspot2R1NetworkScanResults += hotspot2r1Networks;
            mWifiLogProto.numHotspot2R2NetworkScanResults += hotspot2r2Networks;
            mWifiLogProto.numHotspot2R3NetworkScanResults += hotspot2r3Networks;
            mWifiLogProto.numMboSupportedNetworkScanResults += mboSupportedNetworks;
            mWifiLogProto.numMboCellularDataAwareNetworkScanResults += mboCellularDataAwareNetworks;
            mWifiLogProto.numOceSupportedNetworkScanResults += oceSupportedNetworks;
            mWifiLogProto.numFilsSupportedNetworkScanResults += filsSupportedNetworks;
            mWifiLogProto.num11AxNetworkScanResults += standard11axNetworks;
            mWifiLogProto.num6GNetworkScanResults += band6gNetworks;
            mWifiLogProto.numScans++;
        }
    }

    private boolean mWifiWins = false; // Based on scores, use wifi instead of mobile data?
    // Based on Wifi usability scores. use wifi instead of mobile data?
    private boolean mWifiWinsUsabilityScore = false;

    /**
     * Increments occurence of a particular wifi score calculated
     * in WifiScoreReport by current connected network. Scores are bounded
     * within  [MIN_WIFI_SCORE, MAX_WIFI_SCORE] to limit size of SparseArray.
     *
     * Also records events when the current score breaches significant thresholds.
     */
    public void incrementWifiScoreCount(int score) {
        if (score < MIN_WIFI_SCORE || score > MAX_WIFI_SCORE) {
            return;
        }
        synchronized (mLock) {
            int count = mWifiScoreCounts.get(score);
            mWifiScoreCounts.put(score, count + 1);

            boolean wifiWins = mWifiWins;
            if (mWifiWins && score < LOW_WIFI_SCORE) {
                wifiWins = false;
            } else if (!mWifiWins && score > LOW_WIFI_SCORE) {
                wifiWins = true;
            }
            mLastScore = score;
            mLastScoreNoReset = score;
            if (wifiWins != mWifiWins) {
                mWifiWins = wifiWins;
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_SCORE_BREACH;
                addStaEvent(event);
                // Only record the first score breach by checking whether mScoreBreachLowTimeMillis
                // has been set to -1
                if (!wifiWins && mScoreBreachLowTimeMillis == -1) {
                    mScoreBreachLowTimeMillis = mClock.getElapsedSinceBootMillis();
                }
            }
        }
    }

    /**
     * Increments occurence of the results from attempting to start SoftAp.
     * Maps the |result| and WifiManager |failureCode| constant to proto defined SoftApStartResult
     * codes.
     */
    public void incrementSoftApStartResult(boolean result, int failureCode) {
        synchronized (mLock) {
            if (result) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY,
                        count + 1);
                return;
            }

            // now increment failure modes - if not explicitly handled, dump into the general
            // error bucket.
            if (failureCode == WifiManager.SAP_START_FAILURE_NO_CHANNEL) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL,
                        count + 1);
            } else if (failureCode == WifiManager.SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount
                        .SOFT_AP_FAILED_UNSUPPORTED_CONFIGURATION);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount
                        .SOFT_AP_FAILED_UNSUPPORTED_CONFIGURATION,
                        count + 1);
            } else {
                // failure mode not tracked at this time...  count as a general error for now.
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR,
                        count + 1);
            }
        }
    }

    /**
     * Adds a record indicating the current up state of soft AP
     */
    public void addSoftApUpChangedEvent(boolean isUp, int mode, long defaultShutdownTimeoutMillis) {
        SoftApConnectedClientsEvent event = new SoftApConnectedClientsEvent();
        event.eventType = isUp ? SoftApConnectedClientsEvent.SOFT_AP_UP :
                SoftApConnectedClientsEvent.SOFT_AP_DOWN;
        event.numConnectedClients = 0;
        event.defaultShutdownTimeoutSetting = defaultShutdownTimeoutMillis;
        addSoftApConnectedClientsEvent(event, mode);
    }

    /**
     * Adds a record for current number of associated stations to soft AP
     */
    public void addSoftApNumAssociatedStationsChangedEvent(int numStations, int mode) {
        SoftApConnectedClientsEvent event = new SoftApConnectedClientsEvent();
        event.eventType = SoftApConnectedClientsEvent.NUM_CLIENTS_CHANGED;
        event.numConnectedClients = numStations;
        addSoftApConnectedClientsEvent(event, mode);
    }

    /**
     * Adds a record to the corresponding event list based on mode param
     */
    private void addSoftApConnectedClientsEvent(SoftApConnectedClientsEvent event, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            if (softApEventList.size() > MAX_NUM_SOFT_AP_EVENTS) {
                return;
            }

            event.timeStampMillis = mClock.getElapsedSinceBootMillis();
            softApEventList.add(event);
        }
    }

    /**
     * Updates current soft AP events with channel info
     */
    public void addSoftApChannelSwitchedEvent(int frequency, int bandwidth, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            for (int index = softApEventList.size() - 1; index >= 0; index--) {
                SoftApConnectedClientsEvent event = softApEventList.get(index);

                if (event != null && event.eventType == SoftApConnectedClientsEvent.SOFT_AP_UP) {
                    event.channelFrequency = frequency;
                    event.channelBandwidth = bandwidth;
                    break;
                }
            }
        }
    }

    /**
     * Updates current soft AP events with softap configuration
     */
    public void updateSoftApConfiguration(SoftApConfiguration config, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            for (int index = softApEventList.size() - 1; index >= 0; index--) {
                SoftApConnectedClientsEvent event = softApEventList.get(index);

                if (event != null && event.eventType == SoftApConnectedClientsEvent.SOFT_AP_UP) {
                    event.maxNumClientsSettingInSoftapConfiguration =
                            config.getMaxNumberOfClients();
                    event.shutdownTimeoutSettingInSoftapConfiguration =
                            config.getShutdownTimeoutMillis();
                    event.clientControlIsEnabled = config.isClientControlByUserEnabled();
                    break;
                }
            }
        }
    }

    /**
     * Updates current soft AP events with softap capability
     */
    public void updateSoftApCapability(SoftApCapability capability, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            for (int index = softApEventList.size() - 1; index >= 0; index--) {
                SoftApConnectedClientsEvent event = softApEventList.get(index);
                if (event != null && event.eventType == SoftApConnectedClientsEvent.SOFT_AP_UP) {
                    event.maxNumClientsSettingInSoftapCapability =
                            capability.getMaxSupportedClients();
                    break;
                }
            }
        }
    }

    /**
     * Increment number of times the HAL crashed.
     */
    public void incrementNumHalCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numHalCrashes++;
        }
    }

    /**
     * Increment number of times the Wificond crashed.
     */
    public void incrementNumWificondCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numWificondCrashes++;
        }
    }

    /**
     * Increment number of times the supplicant crashed.
     */
    public void incrementNumSupplicantCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numSupplicantCrashes++;
        }
    }

    /**
     * Increment number of times the hostapd crashed.
     */
    public void incrementNumHostapdCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numHostapdCrashes++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in HAL.
     */
    public void incrementNumSetupClientInterfaceFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in wificond.
     */
    public void incrementNumSetupClientInterfaceFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToWificond++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in supplicant.
     */
    public void incrementNumSetupClientInterfaceFailureDueToSupplicant() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in HAL.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in wificond.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in hostapd.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToHostapd() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd++;
        }
    }

    /**
     * Increment number of times we got client interface down.
     */
    public void incrementNumClientInterfaceDown() {
        synchronized (mLock) {
            mWifiLogProto.numClientInterfaceDown++;
        }
    }

    /**
     * Increment number of times we got client interface down.
     */
    public void incrementNumSoftApInterfaceDown() {
        synchronized (mLock) {
            mWifiLogProto.numSoftApInterfaceDown++;
        }
    }

    /**
     * Increment number of times Passpoint provider being installed.
     */
    public void incrementNumPasspointProviderInstallation() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderInstallation++;
        }
    }

    /**
     * Increment number of times Passpoint provider is installed successfully.
     */
    public void incrementNumPasspointProviderInstallSuccess() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderInstallSuccess++;
        }
    }

    /**
     * Increment number of times Passpoint provider being uninstalled.
     */
    public void incrementNumPasspointProviderUninstallation() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderUninstallation++;
        }
    }

    /**
     * Increment number of times Passpoint provider is uninstalled successfully.
     */
    public void incrementNumPasspointProviderUninstallSuccess() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderUninstallSuccess++;
        }
    }

    /**
     * Increment number of Passpoint providers with no Root CA in their profile.
     */
    public void incrementNumPasspointProviderWithNoRootCa() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderWithNoRootCa++;
        }
    }

    /**
     * Increment number of Passpoint providers with a self-signed Root CA in their profile.
     */
    public void incrementNumPasspointProviderWithSelfSignedRootCa() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderWithSelfSignedRootCa++;
        }
    }

    /**
     * Increment number of Passpoint providers with subscription expiration date in their profile.
     */
    public void incrementNumPasspointProviderWithSubscriptionExpiration() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderWithSubscriptionExpiration++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to MCC.
     */
    public void incrementNumRadioModeChangeToMcc() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToMcc++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to SCC.
     */
    public void incrementNumRadioModeChangeToScc() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToScc++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to SBS.
     */
    public void incrementNumRadioModeChangeToSbs() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToSbs++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to DBS.
     */
    public void incrementNumRadioModeChangeToDbs() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToDbs++;
        }
    }

    /**
     * Increment number of times we detected a channel did not satisfy user band preference.
     */
    public void incrementNumSoftApUserBandPreferenceUnsatisfied() {
        synchronized (mLock) {
            mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied++;
        }
    }

    /**
     * Increment N-Way network selection decision histograms:
     * Counts the size of various sets of scanDetails within a scan, and increment the occurrence
     * of that size for the associated histogram. There are ten histograms generated for each
     * combination of: {SSID, BSSID} *{Total, Saved, Open, Saved_or_Open, Passpoint}
     * Only performs this count if isFullBand is true, otherwise, increments the partial scan count
     */
    public void incrementAvailableNetworksHistograms(List<ScanDetail> scanDetails,
            boolean isFullBand) {
        synchronized (mLock) {
            if (mWifiConfigManager == null || mWifiNetworkSelector == null
                    || mPasspointManager == null) {
                return;
            }
            if (!isFullBand) {
                mWifiLogProto.partialAllSingleScanListenerResults++;
                return;
            }
            Set<ScanResultMatchInfo> ssids = new HashSet<ScanResultMatchInfo>();
            int bssids = 0;
            Set<ScanResultMatchInfo> openSsids = new HashSet<ScanResultMatchInfo>();
            int openBssids = 0;
            Set<ScanResultMatchInfo> savedSsids = new HashSet<ScanResultMatchInfo>();
            int savedBssids = 0;
            // openOrSavedSsids calculated from union of savedSsids & openSsids
            int openOrSavedBssids = 0;
            Set<PasspointProvider> savedPasspointProviderProfiles =
                    new HashSet<PasspointProvider>();
            int savedPasspointProviderBssids = 0;
            int passpointR1Aps = 0;
            int passpointR2Aps = 0;
            int passpointR3Aps = 0;
            Map<ANQPNetworkKey, Integer> passpointR1UniqueEss = new HashMap<>();
            Map<ANQPNetworkKey, Integer> passpointR2UniqueEss = new HashMap<>();
            Map<ANQPNetworkKey, Integer> passpointR3UniqueEss = new HashMap<>();
            int supporting80211mcAps = 0;
            for (ScanDetail scanDetail : scanDetails) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                ScanResult scanResult = scanDetail.getScanResult();

                // statistics to be collected for ALL APs (irrespective of signal power)
                if (networkDetail.is80211McResponderSupport()) {
                    supporting80211mcAps++;
                }

                ScanResultMatchInfo matchInfo = ScanResultMatchInfo.fromScanResult(scanResult);
                List<Pair<PasspointProvider, PasspointMatch>> matchedProviders = null;
                if (networkDetail.isInterworking()) {
                    // Try to match provider, but do not allow new ANQP messages. Use cached data.
                    matchedProviders = mPasspointManager.matchProvider(scanResult, false);
                    if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                        passpointR1Aps++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                        passpointR2Aps++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R3) {
                        passpointR3Aps++;
                    }

                    long bssid = 0;
                    boolean validBssid = false;
                    try {
                        bssid = Utils.parseMac(scanResult.BSSID);
                        validBssid = true;
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG,
                                "Invalid BSSID provided in the scan result: " + scanResult.BSSID);
                    }
                    if (validBssid) {
                        ANQPNetworkKey uniqueEss = ANQPNetworkKey.buildKey(scanResult.SSID, bssid,
                                scanResult.hessid, networkDetail.getAnqpDomainID());
                        if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                            Integer countObj = passpointR1UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR1UniqueEss.put(uniqueEss, count + 1);
                        } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                            Integer countObj = passpointR2UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR2UniqueEss.put(uniqueEss, count + 1);
                        } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R3) {
                            Integer countObj = passpointR3UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR3UniqueEss.put(uniqueEss, count + 1);
                        }
                    }
                }

                if (mWifiNetworkSelector.isSignalTooWeak(scanResult)) {
                    continue;
                }

                // statistics to be collected ONLY for those APs with sufficient signal power

                ssids.add(matchInfo);
                bssids++;
                boolean isOpen = matchInfo.networkType == WifiConfiguration.SECURITY_TYPE_OPEN;
                WifiConfiguration config =
                        mWifiConfigManager.getConfiguredNetworkForScanDetail(scanDetail);
                boolean isSaved = (config != null) && !config.isEphemeral()
                        && !config.isPasspoint();
                if (isOpen) {
                    openSsids.add(matchInfo);
                    openBssids++;
                }
                if (isSaved) {
                    savedSsids.add(matchInfo);
                    savedBssids++;
                }
                if (isOpen || isSaved) {
                    openOrSavedBssids++;
                    // Calculate openOrSavedSsids union later
                }
                if (matchedProviders != null && !matchedProviders.isEmpty()) {
                    for (Pair<PasspointProvider, PasspointMatch> passpointProvider :
                            matchedProviders) {
                        savedPasspointProviderProfiles.add(passpointProvider.first);
                    }
                    savedPasspointProviderBssids++;
                }
            }
            mWifiLogProto.fullBandAllSingleScanListenerResults++;
            incrementTotalScanSsids(mTotalSsidsInScanHistogram, ssids.size());
            incrementTotalScanResults(mTotalBssidsInScanHistogram, bssids);
            incrementSsid(mAvailableOpenSsidsInScanHistogram, openSsids.size());
            incrementBssid(mAvailableOpenBssidsInScanHistogram, openBssids);
            incrementSsid(mAvailableSavedSsidsInScanHistogram, savedSsids.size());
            incrementBssid(mAvailableSavedBssidsInScanHistogram, savedBssids);
            openSsids.addAll(savedSsids); // openSsids = Union(openSsids, savedSsids)
            incrementSsid(mAvailableOpenOrSavedSsidsInScanHistogram, openSsids.size());
            incrementBssid(mAvailableOpenOrSavedBssidsInScanHistogram, openOrSavedBssids);
            incrementSsid(mAvailableSavedPasspointProviderProfilesInScanHistogram,
                    savedPasspointProviderProfiles.size());
            incrementBssid(mAvailableSavedPasspointProviderBssidsInScanHistogram,
                    savedPasspointProviderBssids);
            incrementTotalPasspointAps(mObservedHotspotR1ApInScanHistogram, passpointR1Aps);
            incrementTotalPasspointAps(mObservedHotspotR2ApInScanHistogram, passpointR2Aps);
            incrementTotalPasspointAps(mObservedHotspotR3ApInScanHistogram, passpointR3Aps);
            incrementTotalUniquePasspointEss(mObservedHotspotR1EssInScanHistogram,
                    passpointR1UniqueEss.size());
            incrementTotalUniquePasspointEss(mObservedHotspotR2EssInScanHistogram,
                    passpointR2UniqueEss.size());
            incrementTotalUniquePasspointEss(mObservedHotspotR3EssInScanHistogram,
                    passpointR3UniqueEss.size());
            for (Integer count : passpointR1UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR1ApsPerEssInScanHistogram, count);
            }
            for (Integer count : passpointR2UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR2ApsPerEssInScanHistogram, count);
            }
            for (Integer count : passpointR3UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR3ApsPerEssInScanHistogram, count);
            }
            increment80211mcAps(mObserved80211mcApInScanHistogram, supporting80211mcAps);
        }
    }

    /** Increments the occurence of a "Connect to Network" notification. */
    public void incrementConnectToNetworkNotification(String notifierTag, int notificationType) {
        synchronized (mLock) {
            int count = mConnectToNetworkNotificationCount.get(notificationType);
            mConnectToNetworkNotificationCount.put(notificationType, count + 1);
        }
    }

    /** Increments the occurence of an "Connect to Network" notification user action. */
    public void incrementConnectToNetworkNotificationAction(String notifierTag,
            int notificationType, int actionType) {
        synchronized (mLock) {
            int key = notificationType * CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER
                    + actionType;
            int count = mConnectToNetworkNotificationActionCount.get(key);
            mConnectToNetworkNotificationActionCount.put(key, count + 1);
        }
    }

    /**
     * Sets the number of SSIDs blacklisted from recommendation by the open network notification
     * recommender.
     */
    public void setNetworkRecommenderBlacklistSize(String notifierTag, int size) {
        synchronized (mLock) {
            mOpenNetworkRecommenderBlacklistSize = size;
        }
    }

    /** Sets if the available network notification feature is enabled. */
    public void setIsWifiNetworksAvailableNotificationEnabled(String notifierTag, boolean enabled) {
        synchronized (mLock) {
            mIsWifiNetworksAvailableNotificationOn = enabled;
        }
    }

    /** Increments the occurence of connection attempts that were initiated unsuccessfully */
    public void incrementNumNetworkRecommendationUpdates(String notifierTag) {
        synchronized (mLock) {
            mNumOpenNetworkRecommendationUpdates++;
        }
    }

    /** Increments the occurence of connection attempts that were initiated unsuccessfully */
    public void incrementNumNetworkConnectMessageFailedToSend(String notifierTag) {
        synchronized (mLock) {
            mNumOpenNetworkConnectMessageFailedToSend++;
        }
    }

    /** Log firmware alert related metrics */
    public void logFirmwareAlert(int errorCode) {
        incrementAlertReasonCount(errorCode);
        logWifiIsUnusableEvent(WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT, errorCode);
        addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                WifiUsabilityStats.TYPE_FIRMWARE_ALERT, errorCode);
    }

    public static final String PROTO_DUMP_ARG = "wifiMetricsProto";
    public static final String CLEAN_DUMP_ARG = "clean";

    /**
     * Dump all WifiMetrics. Collects some metrics from ConfigStore, Settings and WifiManager
     * at this time.
     *
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args [wifiMetricsProto [clean]]
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            consolidateScoringParams();
            if (args != null && args.length > 0 && PROTO_DUMP_ARG.equals(args[0])) {
                // Dump serialized WifiLog proto
                consolidateProto();

                byte[] wifiMetricsProto = WifiMetricsProto.WifiLog.toByteArray(mWifiLogProto);
                String metricsProtoDump = Base64.encodeToString(wifiMetricsProto, Base64.DEFAULT);
                if (args.length > 1 && CLEAN_DUMP_ARG.equals(args[1])) {
                    // Output metrics proto bytes (base64) and nothing else
                    pw.print(metricsProtoDump);
                } else {
                    // Tag the start and end of the metrics proto bytes
                    pw.println("WifiMetrics:");
                    pw.println(metricsProtoDump);
                    pw.println("EndWifiMetrics");
                }
                clear();
            } else {
                pw.println("WifiMetrics:");
                pw.println("mConnectionEvents:");
                for (ConnectionEvent event : mConnectionEventList) {
                    String eventLine = event.toString();
                    if (event == mCurrentConnectionEvent) {
                        eventLine += " CURRENTLY OPEN EVENT";
                    }
                    pw.println(eventLine);
                }
                pw.println("mWifiLogProto.numSavedNetworks=" + mWifiLogProto.numSavedNetworks);
                pw.println("mWifiLogProto.numSavedNetworksWithMacRandomization="
                        + mWifiLogProto.numSavedNetworksWithMacRandomization);
                pw.println("mWifiLogProto.numOpenNetworks=" + mWifiLogProto.numOpenNetworks);
                pw.println("mWifiLogProto.numLegacyPersonalNetworks="
                        + mWifiLogProto.numLegacyPersonalNetworks);
                pw.println("mWifiLogProto.numLegacyEnterpriseNetworks="
                        + mWifiLogProto.numLegacyEnterpriseNetworks);
                pw.println("mWifiLogProto.numEnhancedOpenNetworks="
                        + mWifiLogProto.numEnhancedOpenNetworks);
                pw.println("mWifiLogProto.numWpa3PersonalNetworks="
                        + mWifiLogProto.numWpa3PersonalNetworks);
                pw.println("mWifiLogProto.numWpa3EnterpriseNetworks="
                        + mWifiLogProto.numWpa3EnterpriseNetworks);
                pw.println("mWifiLogProto.numWapiPersonalNetworks="
                        + mWifiLogProto.numWapiPersonalNetworks);
                pw.println("mWifiLogProto.numWapiEnterpriseNetworks="
                        + mWifiLogProto.numWapiEnterpriseNetworks);
                pw.println("mWifiLogProto.numHiddenNetworks=" + mWifiLogProto.numHiddenNetworks);
                pw.println("mWifiLogProto.numPasspointNetworks="
                        + mWifiLogProto.numPasspointNetworks);
                pw.println("mWifiLogProto.isLocationEnabled=" + mWifiLogProto.isLocationEnabled);
                pw.println("mWifiLogProto.isScanningAlwaysEnabled="
                        + mWifiLogProto.isScanningAlwaysEnabled);
                pw.println("mWifiLogProto.isVerboseLoggingEnabled="
                        + mWifiLogProto.isVerboseLoggingEnabled);
                pw.println("mWifiLogProto.isEnhancedMacRandomizationForceEnabled="
                        + mWifiLogProto.isEnhancedMacRandomizationForceEnabled);
                pw.println("mWifiLogProto.isWifiWakeEnabled=" + mWifiLogProto.isWifiWakeEnabled);
                pw.println("mWifiLogProto.numNetworksAddedByUser="
                        + mWifiLogProto.numNetworksAddedByUser);
                pw.println("mWifiLogProto.numNetworksAddedByApps="
                        + mWifiLogProto.numNetworksAddedByApps);
                pw.println("mWifiLogProto.numNonEmptyScanResults="
                        + mWifiLogProto.numNonEmptyScanResults);
                pw.println("mWifiLogProto.numEmptyScanResults="
                        + mWifiLogProto.numEmptyScanResults);
                pw.println("mWifiLogProto.numConnecitvityOneshotScans="
                        + mWifiLogProto.numConnectivityOneshotScans);
                pw.println("mWifiLogProto.numOneshotScans="
                        + mWifiLogProto.numOneshotScans);
                pw.println("mWifiLogProto.numOneshotHasDfsChannelScans="
                        + mWifiLogProto.numOneshotHasDfsChannelScans);
                pw.println("mWifiLogProto.numBackgroundScans="
                        + mWifiLogProto.numBackgroundScans);
                pw.println("mWifiLogProto.numExternalAppOneshotScanRequests="
                        + mWifiLogProto.numExternalAppOneshotScanRequests);
                pw.println("mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled="
                        + mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled);
                pw.println("mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled="
                        + mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled);
                pw.println("mWifiLogProto.meteredNetworkStatsSaved=");
                pw.println(mMeteredNetworkStatsBuilder.toProto(false));
                pw.println("mWifiLogProto.meteredNetworkStatsSuggestion=");
                pw.println(mMeteredNetworkStatsBuilder.toProto(true));
                pw.println("mScanReturnEntries:");
                pw.println("  SCAN_UNKNOWN: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_UNKNOWN));
                pw.println("  SCAN_SUCCESS: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_SUCCESS));
                pw.println("  SCAN_FAILURE_INTERRUPTED: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED));
                pw.println("  SCAN_FAILURE_INVALID_CONFIGURATION: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION));
                pw.println("  FAILURE_WIFI_DISABLED: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.FAILURE_WIFI_DISABLED));

                pw.println("mSystemStateEntries: <state><screenOn> : <scansInitiated>");
                pw.println("  WIFI_UNKNOWN       ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN, true));
                pw.println("  WIFI_DISABLED      ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISABLED, true));
                pw.println("  WIFI_DISCONNECTED  ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED, true));
                pw.println("  WIFI_ASSOCIATED    ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED, true));
                pw.println("  WIFI_UNKNOWN      OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN, false));
                pw.println("  WIFI_DISABLED     OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISABLED, false));
                pw.println("  WIFI_DISCONNECTED OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED, false));
                pw.println("  WIFI_ASSOCIATED   OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED, false));
                pw.println("mWifiLogProto.numConnectivityWatchdogPnoGood="
                        + mWifiLogProto.numConnectivityWatchdogPnoGood);
                pw.println("mWifiLogProto.numConnectivityWatchdogPnoBad="
                        + mWifiLogProto.numConnectivityWatchdogPnoBad);
                pw.println("mWifiLogProto.numConnectivityWatchdogBackgroundGood="
                        + mWifiLogProto.numConnectivityWatchdogBackgroundGood);
                pw.println("mWifiLogProto.numConnectivityWatchdogBackgroundBad="
                        + mWifiLogProto.numConnectivityWatchdogBackgroundBad);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggers="
                        + mWifiLogProto.numLastResortWatchdogTriggers);
                pw.println("mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadOther="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadOther);
                pw.println("mWifiLogProto.numLastResortWatchdogSuccesses="
                        + mWifiLogProto.numLastResortWatchdogSuccesses);
                pw.println("mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger="
                        + mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger);
                pw.println("mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs="
                        + mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs);
                pw.println("mWifiLogProto.recordDurationSec="
                        + ((mClock.getElapsedSinceBootMillis() / 1000) - mRecordStartTimeSec));

                try {
                    JSONObject rssiMap = new JSONObject();
                    for (Map.Entry<Integer, SparseIntArray> entry : mRssiPollCountsMap.entrySet()) {
                        int frequency = entry.getKey();
                        final SparseIntArray histogram = entry.getValue();
                        JSONArray histogramElements = new JSONArray();
                        for (int i = MIN_RSSI_POLL; i <= MAX_RSSI_POLL; i++) {
                            int count = histogram.get(i);
                            if (count == 0) {
                                continue;
                            }
                            JSONObject histogramElement = new JSONObject();
                            histogramElement.put(Integer.toString(i), count);
                            histogramElements.put(histogramElement);
                        }
                        rssiMap.put(Integer.toString(frequency), histogramElements);
                    }
                    pw.println("mWifiLogProto.rssiPollCount: " + rssiMap.toString());
                } catch (JSONException e) {
                    pw.println("JSONException occurred: " + e.getMessage());
                }

                pw.println("mWifiLogProto.rssiPollDeltaCount: Printing counts for ["
                        + MIN_RSSI_DELTA + ", " + MAX_RSSI_DELTA + "]");
                StringBuilder sb = new StringBuilder();
                for (int i = MIN_RSSI_DELTA; i <= MAX_RSSI_DELTA; i++) {
                    sb.append(mRssiDeltaCounts.get(i) + " ");
                }
                pw.println("  " + sb.toString());
                pw.println("mWifiLogProto.linkSpeedCounts: ");
                sb.setLength(0);
                for (int i = 0; i < mLinkSpeedCounts.size(); i++) {
                    LinkSpeedCount linkSpeedCount = mLinkSpeedCounts.valueAt(i);
                    sb.append(linkSpeedCount.linkSpeedMbps).append(":{")
                            .append(linkSpeedCount.count).append(", ")
                            .append(linkSpeedCount.rssiSumDbm).append(", ")
                            .append(linkSpeedCount.rssiSumOfSquaresDbmSq).append("} ");
                }
                if (sb.length() > 0) {
                    pw.println(sb.toString());
                }
                pw.print("mWifiLogProto.alertReasonCounts=");
                sb.setLength(0);
                for (int i = WifiLoggerHal.WIFI_ALERT_REASON_MIN;
                        i <= WifiLoggerHal.WIFI_ALERT_REASON_MAX; i++) {
                    int count = mWifiAlertReasonCounts.get(i);
                    if (count > 0) {
                        sb.append("(" + i + "," + count + "),");
                    }
                }
                if (sb.length() > 1) {
                    sb.setLength(sb.length() - 1);  // strip trailing comma
                    pw.println(sb.toString());
                } else {
                    pw.println("()");
                }
                pw.println("mWifiLogProto.numTotalScanResults="
                        + mWifiLogProto.numTotalScanResults);
                pw.println("mWifiLogProto.numOpenNetworkScanResults="
                        + mWifiLogProto.numOpenNetworkScanResults);
                pw.println("mWifiLogProto.numLegacyPersonalNetworkScanResults="
                        + mWifiLogProto.numLegacyPersonalNetworkScanResults);
                pw.println("mWifiLogProto.numLegacyEnterpriseNetworkScanResults="
                        + mWifiLogProto.numLegacyEnterpriseNetworkScanResults);
                pw.println("mWifiLogProto.numEnhancedOpenNetworkScanResults="
                        + mWifiLogProto.numEnhancedOpenNetworkScanResults);
                pw.println("mWifiLogProto.numWpa3PersonalNetworkScanResults="
                        + mWifiLogProto.numWpa3PersonalNetworkScanResults);
                pw.println("mWifiLogProto.numWpa3EnterpriseNetworkScanResults="
                        + mWifiLogProto.numWpa3EnterpriseNetworkScanResults);
                pw.println("mWifiLogProto.numWapiPersonalNetworkScanResults="
                        + mWifiLogProto.numWapiPersonalNetworkScanResults);
                pw.println("mWifiLogProto.numWapiEnterpriseNetworkScanResults="
                        + mWifiLogProto.numWapiEnterpriseNetworkScanResults);
                pw.println("mWifiLogProto.numHiddenNetworkScanResults="
                        + mWifiLogProto.numHiddenNetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R1NetworkScanResults="
                        + mWifiLogProto.numHotspot2R1NetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R2NetworkScanResults="
                        + mWifiLogProto.numHotspot2R2NetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R3NetworkScanResults="
                        + mWifiLogProto.numHotspot2R3NetworkScanResults);
                pw.println("mWifiLogProto.numMboSupportedNetworkScanResults="
                        + mWifiLogProto.numMboSupportedNetworkScanResults);
                pw.println("mWifiLogProto.numMboCellularDataAwareNetworkScanResults="
                        + mWifiLogProto.numMboCellularDataAwareNetworkScanResults);
                pw.println("mWifiLogProto.numOceSupportedNetworkScanResults="
                        + mWifiLogProto.numOceSupportedNetworkScanResults);
                pw.println("mWifiLogProto.numFilsSupportedNetworkScanResults="
                        + mWifiLogProto.numFilsSupportedNetworkScanResults);
                pw.println("mWifiLogProto.num11AxNetworkScanResults="
                        + mWifiLogProto.num11AxNetworkScanResults);
                pw.println("mWifiLogProto.num6GNetworkScanResults"
                        + mWifiLogProto.num6GNetworkScanResults);
                pw.println("mWifiLogProto.numBssidFilteredDueToMboAssocDisallowInd="
                        + mWifiLogProto.numBssidFilteredDueToMboAssocDisallowInd);
                pw.println("mWifiLogProto.numConnectToNetworkSupportingMbo="
                        + mWifiLogProto.numConnectToNetworkSupportingMbo);
                pw.println("mWifiLogProto.numConnectToNetworkSupportingOce="
                        + mWifiLogProto.numConnectToNetworkSupportingOce);
                pw.println("mWifiLogProto.numForceScanDueToSteeringRequest="
                        + mWifiLogProto.numForceScanDueToSteeringRequest);
                pw.println("mWifiLogProto.numMboCellularSwitchRequest="
                        + mWifiLogProto.numMboCellularSwitchRequest);
                pw.println("mWifiLogProto.numSteeringRequestIncludingMboAssocRetryDelay="
                        + mWifiLogProto.numSteeringRequestIncludingMboAssocRetryDelay);
                pw.println("mWifiLogProto.numConnectRequestWithFilsAkm="
                        + mWifiLogProto.numConnectRequestWithFilsAkm);
                pw.println("mWifiLogProto.numL2ConnectionThroughFilsAuthentication="
                        + mWifiLogProto.numL2ConnectionThroughFilsAuthentication);

                pw.println("mWifiLogProto.numScans=" + mWifiLogProto.numScans);
                pw.println("mWifiLogProto.WifiScoreCount: [" + MIN_WIFI_SCORE + ", "
                        + MAX_WIFI_SCORE + "]");
                for (int i = 0; i <= MAX_WIFI_SCORE; i++) {
                    pw.print(mWifiScoreCounts.get(i) + " ");
                }
                pw.println(); // add a line after wifi scores
                pw.println("mWifiLogProto.WifiUsabilityScoreCount: [" + MIN_WIFI_USABILITY_SCORE
                        + ", " + MAX_WIFI_USABILITY_SCORE + "]");
                for (int i = MIN_WIFI_USABILITY_SCORE; i <= MAX_WIFI_USABILITY_SCORE; i++) {
                    pw.print(mWifiUsabilityScoreCounts.get(i) + " ");
                }
                pw.println(); // add a line after wifi usability scores
                pw.println("mWifiLogProto.SoftApManagerReturnCodeCounts:");
                pw.println("  SUCCESS: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY));
                pw.println("  FAILED_GENERAL_ERROR: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR));
                pw.println("  FAILED_NO_CHANNEL: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL));
                pw.println("  FAILED_UNSUPPORTED_CONFIGURATION: "
                        + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount
                        .SOFT_AP_FAILED_UNSUPPORTED_CONFIGURATION));
                pw.print("\n");
                pw.println("mWifiLogProto.numHalCrashes="
                        + mWifiLogProto.numHalCrashes);
                pw.println("mWifiLogProto.numWificondCrashes="
                        + mWifiLogProto.numWificondCrashes);
                pw.println("mWifiLogProto.numSupplicantCrashes="
                        + mWifiLogProto.numSupplicantCrashes);
                pw.println("mWifiLogProto.numHostapdCrashes="
                        + mWifiLogProto.numHostapdCrashes);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToHal="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToHal);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToWificond="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToWificond);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd);
                pw.println("StaEventList:");
                for (StaEventWithTime event : mStaEventList) {
                    pw.println(event);
                }
                pw.println("UserActionEvents:");
                for (UserActionEventWithTime event : mUserActionEventList) {
                    pw.println(event);
                }

                pw.println("mWifiLogProto.numPasspointProviders="
                        + mWifiLogProto.numPasspointProviders);
                pw.println("mWifiLogProto.numPasspointProviderInstallation="
                        + mWifiLogProto.numPasspointProviderInstallation);
                pw.println("mWifiLogProto.numPasspointProviderInstallSuccess="
                        + mWifiLogProto.numPasspointProviderInstallSuccess);
                pw.println("mWifiLogProto.numPasspointProviderUninstallation="
                        + mWifiLogProto.numPasspointProviderUninstallation);
                pw.println("mWifiLogProto.numPasspointProviderUninstallSuccess="
                        + mWifiLogProto.numPasspointProviderUninstallSuccess);
                pw.println("mWifiLogProto.numPasspointProvidersSuccessfullyConnected="
                        + mWifiLogProto.numPasspointProvidersSuccessfullyConnected);

                pw.println("mWifiLogProto.installedPasspointProfileTypeForR1:"
                        + mInstalledPasspointProfileTypeForR1);
                pw.println("mWifiLogProto.installedPasspointProfileTypeForR2:"
                        + mInstalledPasspointProfileTypeForR2);

                pw.println("mWifiLogProto.passpointProvisionStats.numProvisionSuccess="
                            + mNumProvisionSuccess);
                pw.println("mWifiLogProto.passpointProvisionStats.provisionFailureCount:"
                            + mPasspointProvisionFailureCounts);

                pw.println("mWifiLogProto.numRadioModeChangeToMcc="
                        + mWifiLogProto.numRadioModeChangeToMcc);
                pw.println("mWifiLogProto.numRadioModeChangeToScc="
                        + mWifiLogProto.numRadioModeChangeToScc);
                pw.println("mWifiLogProto.numRadioModeChangeToSbs="
                        + mWifiLogProto.numRadioModeChangeToSbs);
                pw.println("mWifiLogProto.numRadioModeChangeToDbs="
                        + mWifiLogProto.numRadioModeChangeToDbs);
                pw.println("mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied="
                        + mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied);
                pw.println("mTotalSsidsInScanHistogram:"
                        + mTotalSsidsInScanHistogram.toString());
                pw.println("mTotalBssidsInScanHistogram:"
                        + mTotalBssidsInScanHistogram.toString());
                pw.println("mAvailableOpenSsidsInScanHistogram:"
                        + mAvailableOpenSsidsInScanHistogram.toString());
                pw.println("mAvailableOpenBssidsInScanHistogram:"
                        + mAvailableOpenBssidsInScanHistogram.toString());
                pw.println("mAvailableSavedSsidsInScanHistogram:"
                        + mAvailableSavedSsidsInScanHistogram.toString());
                pw.println("mAvailableSavedBssidsInScanHistogram:"
                        + mAvailableSavedBssidsInScanHistogram.toString());
                pw.println("mAvailableOpenOrSavedSsidsInScanHistogram:"
                        + mAvailableOpenOrSavedSsidsInScanHistogram.toString());
                pw.println("mAvailableOpenOrSavedBssidsInScanHistogram:"
                        + mAvailableOpenOrSavedBssidsInScanHistogram.toString());
                pw.println("mAvailableSavedPasspointProviderProfilesInScanHistogram:"
                        + mAvailableSavedPasspointProviderProfilesInScanHistogram.toString());
                pw.println("mAvailableSavedPasspointProviderBssidsInScanHistogram:"
                        + mAvailableSavedPasspointProviderBssidsInScanHistogram.toString());
                pw.println("mWifiLogProto.partialAllSingleScanListenerResults="
                        + mWifiLogProto.partialAllSingleScanListenerResults);
                pw.println("mWifiLogProto.fullBandAllSingleScanListenerResults="
                        + mWifiLogProto.fullBandAllSingleScanListenerResults);
                pw.println("mWifiAwareMetrics:");
                mWifiAwareMetrics.dump(fd, pw, args);
                pw.println("mRttMetrics:");
                mRttMetrics.dump(fd, pw, args);

                pw.println("mPnoScanMetrics.numPnoScanAttempts="
                        + mPnoScanMetrics.numPnoScanAttempts);
                pw.println("mPnoScanMetrics.numPnoScanFailed="
                        + mPnoScanMetrics.numPnoScanFailed);
                pw.println("mPnoScanMetrics.numPnoScanStartedOverOffload="
                        + mPnoScanMetrics.numPnoScanStartedOverOffload);
                pw.println("mPnoScanMetrics.numPnoScanFailedOverOffload="
                        + mPnoScanMetrics.numPnoScanFailedOverOffload);
                pw.println("mPnoScanMetrics.numPnoFoundNetworkEvents="
                        + mPnoScanMetrics.numPnoFoundNetworkEvents);

                pw.println("mWifiLinkLayerUsageStats.loggingDurationMs="
                        + mWifiLinkLayerUsageStats.loggingDurationMs);
                pw.println("mWifiLinkLayerUsageStats.radioOnTimeMs="
                        + mWifiLinkLayerUsageStats.radioOnTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioTxTimeMs="
                        + mWifiLinkLayerUsageStats.radioTxTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioRxTimeMs="
                        + mWifiLinkLayerUsageStats.radioRxTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioNanScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioNanScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioBackgroundScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioBackgroundScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioRoamScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioRoamScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioPnoScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioPnoScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioHs20ScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioHs20ScanTimeMs);

                pw.println("mWifiLogProto.connectToNetworkNotificationCount="
                        + mConnectToNetworkNotificationCount.toString());
                pw.println("mWifiLogProto.connectToNetworkNotificationActionCount="
                        + mConnectToNetworkNotificationActionCount.toString());
                pw.println("mWifiLogProto.openNetworkRecommenderBlacklistSize="
                        + mOpenNetworkRecommenderBlacklistSize);
                pw.println("mWifiLogProto.isWifiNetworksAvailableNotificationOn="
                        + mIsWifiNetworksAvailableNotificationOn);
                pw.println("mWifiLogProto.numOpenNetworkRecommendationUpdates="
                        + mNumOpenNetworkRecommendationUpdates);
                pw.println("mWifiLogProto.numOpenNetworkConnectMessageFailedToSend="
                        + mNumOpenNetworkConnectMessageFailedToSend);

                pw.println("mWifiLogProto.observedHotspotR1ApInScanHistogram="
                        + mObservedHotspotR1ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2ApInScanHistogram="
                        + mObservedHotspotR2ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR3ApInScanHistogram="
                        + mObservedHotspotR3ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR1EssInScanHistogram="
                        + mObservedHotspotR1EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2EssInScanHistogram="
                        + mObservedHotspotR2EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR3EssInScanHistogram="
                        + mObservedHotspotR3EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR1ApsPerEssInScanHistogram="
                        + mObservedHotspotR1ApsPerEssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2ApsPerEssInScanHistogram="
                        + mObservedHotspotR2ApsPerEssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR3ApsPerEssInScanHistogram="
                        + mObservedHotspotR3ApsPerEssInScanHistogram);

                pw.println("mWifiLogProto.observed80211mcSupportingApsInScanHistogram"
                        + mObserved80211mcApInScanHistogram);
                pw.println("mWifiLogProto.bssidBlocklistStats:");
                pw.println(mBssidBlocklistStats.toString());

                pw.println("mSoftApTetheredEvents:");
                for (SoftApConnectedClientsEvent event : mSoftApEventListTethered) {
                    StringBuilder eventLine = new StringBuilder();
                    eventLine.append("event_type=" + event.eventType);
                    eventLine.append(",time_stamp_millis=" + event.timeStampMillis);
                    eventLine.append(",num_connected_clients=" + event.numConnectedClients);
                    eventLine.append(",channel_frequency=" + event.channelFrequency);
                    eventLine.append(",channel_bandwidth=" + event.channelBandwidth);
                    eventLine.append(",max_num_clients_setting_in_softap_configuration="
                            + event.maxNumClientsSettingInSoftapConfiguration);
                    eventLine.append(",max_num_clients_setting_in_softap_capability="
                            + event.maxNumClientsSettingInSoftapCapability);
                    eventLine.append(",shutdown_timeout_setting_in_softap_configuration="
                            + event.shutdownTimeoutSettingInSoftapConfiguration);
                    eventLine.append(",default_shutdown_timeout_setting="
                            + event.defaultShutdownTimeoutSetting);
                    eventLine.append(",client_control_is_enabled=" + event.clientControlIsEnabled);
                    pw.println(eventLine.toString());
                }
                pw.println("mSoftApLocalOnlyEvents:");
                for (SoftApConnectedClientsEvent event : mSoftApEventListLocalOnly) {
                    StringBuilder eventLine = new StringBuilder();
                    eventLine.append("event_type=" + event.eventType);
                    eventLine.append(",time_stamp_millis=" + event.timeStampMillis);
                    eventLine.append(",num_connected_clients=" + event.numConnectedClients);
                    eventLine.append(",channel_frequency=" + event.channelFrequency);
                    eventLine.append(",channel_bandwidth=" + event.channelBandwidth);
                    eventLine.append(",max_num_clients_setting_in_softap_configuration="
                            + event.maxNumClientsSettingInSoftapConfiguration);
                    eventLine.append(",max_num_clients_setting_in_softap_capability="
                            + event.maxNumClientsSettingInSoftapCapability);
                    eventLine.append(",shutdown_timeout_setting_in_softap_configuration="
                            + event.shutdownTimeoutSettingInSoftapConfiguration);
                    eventLine.append(",default_shutdown_timeout_setting="
                            + event.defaultShutdownTimeoutSetting);
                    eventLine.append(",client_control_is_enabled=" + event.clientControlIsEnabled);
                    pw.println(eventLine.toString());
                }

                mWifiPowerMetrics.dump(pw);
                mWifiWakeMetrics.dump(pw);

                pw.println("mWifiLogProto.isMacRandomizationOn="
                        + mContext.getResources().getBoolean(
                                R.bool.config_wifi_connected_mac_randomization_supported));
                pw.println("mWifiLogProto.scoreExperimentId=" + mWifiLogProto.scoreExperimentId);
                pw.println("mExperimentValues.wifiIsUnusableLoggingEnabled="
                        + mContext.getResources().getBoolean(
                                R.bool.config_wifiIsUnusableEventMetricsEnabled));
                pw.println("mExperimentValues.wifiDataStallMinTxBad="
                        + mContext.getResources().getInteger(
                                R.integer.config_wifiDataStallMinTxBad));
                pw.println("mExperimentValues.wifiDataStallMinTxSuccessWithoutRx="
                        + mContext.getResources().getInteger(
                                R.integer.config_wifiDataStallMinTxSuccessWithoutRx));
                pw.println("mExperimentValues.linkSpeedCountsLoggingEnabled="
                        + mContext.getResources().getBoolean(
                                R.bool.config_wifiLinkSpeedMetricsEnabled));
                pw.println("mExperimentValues.dataStallDurationMs="
                        + mExperimentValues.dataStallDurationMs);
                pw.println("mExperimentValues.dataStallTxTputThrKbps="
                        + mExperimentValues.dataStallTxTputThrKbps);
                pw.println("mExperimentValues.dataStallRxTputThrKbps="
                        + mExperimentValues.dataStallRxTputThrKbps);
                pw.println("mExperimentValues.dataStallTxPerThr="
                        + mExperimentValues.dataStallTxPerThr);
                pw.println("mExperimentValues.dataStallCcaLevelThr="
                        + mExperimentValues.dataStallCcaLevelThr);
                pw.println("WifiIsUnusableEventList: ");
                for (WifiIsUnusableWithTime event : mWifiIsUnusableList) {
                    pw.println(event);
                }
                pw.println("Hardware Version: " + SystemProperties.get("ro.boot.revision", ""));

                pw.println("mWifiUsabilityStatsEntriesList:");
                for (WifiUsabilityStatsEntry stats : mWifiUsabilityStatsEntriesList) {
                    printWifiUsabilityStatsEntry(pw, stats);
                }
                pw.println("mWifiUsabilityStatsList:");
                for (WifiUsabilityStats stats : mWifiUsabilityStatsListGood) {
                    pw.println("\nlabel=" + stats.label);
                    pw.println("\ntrigger_type=" + stats.triggerType);
                    pw.println("\ntime_stamp_ms=" + stats.timeStampMs);
                    for (WifiUsabilityStatsEntry entry : stats.stats) {
                        printWifiUsabilityStatsEntry(pw, entry);
                    }
                }
                for (WifiUsabilityStats stats : mWifiUsabilityStatsListBad) {
                    pw.println("\nlabel=" + stats.label);
                    pw.println("\ntrigger_type=" + stats.triggerType);
                    pw.println("\ntime_stamp_ms=" + stats.timeStampMs);
                    for (WifiUsabilityStatsEntry entry : stats.stats) {
                        printWifiUsabilityStatsEntry(pw, entry);
                    }
                }

                pw.println("mMobilityStatePnoStatsMap:");
                for (int i = 0; i < mMobilityStatePnoStatsMap.size(); i++) {
                    printDeviceMobilityStatePnoScanStats(pw, mMobilityStatePnoStatsMap.valueAt(i));
                }

                mWifiP2pMetrics.dump(pw);
                pw.println("mDppMetrics:");
                mDppMetrics.dump(pw);

                pw.println("mWifiConfigStoreReadDurationHistogram:"
                        + mWifiConfigStoreReadDurationHistogram.toString());
                pw.println("mWifiConfigStoreWriteDurationHistogram:"
                        + mWifiConfigStoreWriteDurationHistogram.toString());

                pw.println("mLinkProbeSuccessRssiCounts:" + mLinkProbeSuccessRssiCounts);
                pw.println("mLinkProbeFailureRssiCounts:" + mLinkProbeFailureRssiCounts);
                pw.println("mLinkProbeSuccessLinkSpeedCounts:" + mLinkProbeSuccessLinkSpeedCounts);
                pw.println("mLinkProbeFailureLinkSpeedCounts:" + mLinkProbeFailureLinkSpeedCounts);
                pw.println("mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram:"
                        + mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram);
                pw.println("mLinkProbeFailureSecondsSinceLastTxSuccessHistogram:"
                        + mLinkProbeFailureSecondsSinceLastTxSuccessHistogram);
                pw.println("mLinkProbeSuccessElapsedTimeMsHistogram:"
                        + mLinkProbeSuccessElapsedTimeMsHistogram);
                pw.println("mLinkProbeFailureReasonCounts:" + mLinkProbeFailureReasonCounts);
                pw.println("mLinkProbeExperimentProbeCounts:" + mLinkProbeExperimentProbeCounts);

                pw.println("mNetworkSelectionExperimentPairNumChoicesCounts:"
                        + mNetworkSelectionExperimentPairNumChoicesCounts);
                pw.println("mLinkProbeStaEventCount:" + mLinkProbeStaEventCount);

                pw.println("mWifiNetworkRequestApiLog:\n" + mWifiNetworkRequestApiLog);
                pw.println("mWifiNetworkRequestApiMatchSizeHistogram:\n"
                        + mWifiNetworkRequestApiMatchSizeHistogram);
                pw.println("mWifiNetworkSuggestionApiLog:\n" + mWifiNetworkSuggestionApiLog);
                pw.println("mWifiNetworkSuggestionApiMatchSizeHistogram:\n"
                        + mWifiNetworkSuggestionApiListSizeHistogram);
                pw.println("mWifiNetworkSuggestionApiAppTypeCounter:\n"
                        + mWifiNetworkSuggestionApiAppTypeCounter);
                printUserApprovalSuggestionAppReaction(pw);
                printUserApprovalCarrierReaction(pw);
                pw.println("mNetworkIdToNominatorId:\n" + mNetworkIdToNominatorId);
                pw.println("mWifiLockStats:\n" + mWifiLockStats);
                pw.println("mWifiLockHighPerfAcqDurationSecHistogram:\n"
                        + mWifiLockHighPerfAcqDurationSecHistogram);
                pw.println("mWifiLockLowLatencyAcqDurationSecHistogram:\n"
                        + mWifiLockLowLatencyAcqDurationSecHistogram);
                pw.println("mWifiLockHighPerfActiveSessionDurationSecHistogram:\n"
                        + mWifiLockHighPerfActiveSessionDurationSecHistogram);
                pw.println("mWifiLockLowLatencyActiveSessionDurationSecHistogram:\n"
                        + mWifiLockLowLatencyActiveSessionDurationSecHistogram);
                pw.println("mWifiToggleStats:\n" + mWifiToggleStats);
                pw.println("mWifiLogProto.numAddOrUpdateNetworkCalls="
                        + mWifiLogProto.numAddOrUpdateNetworkCalls);
                pw.println("mWifiLogProto.numEnableNetworkCalls="
                        + mWifiLogProto.numEnableNetworkCalls);

                pw.println("mWifiLogProto.txLinkSpeedCount2g=" + mTxLinkSpeedCount2g);
                pw.println("mWifiLogProto.txLinkSpeedCount5gLow=" + mTxLinkSpeedCount5gLow);
                pw.println("mWifiLogProto.txLinkSpeedCount5gMid=" + mTxLinkSpeedCount5gMid);
                pw.println("mWifiLogProto.txLinkSpeedCount5gHigh=" + mTxLinkSpeedCount5gHigh);
                pw.println("mWifiLogProto.txLinkSpeedCount6gLow=" + mTxLinkSpeedCount6gLow);
                pw.println("mWifiLogProto.txLinkSpeedCount6gMid=" + mTxLinkSpeedCount6gMid);
                pw.println("mWifiLogProto.txLinkSpeedCount6gHigh=" + mTxLinkSpeedCount6gHigh);

                pw.println("mWifiLogProto.rxLinkSpeedCount2g=" + mRxLinkSpeedCount2g);
                pw.println("mWifiLogProto.rxLinkSpeedCount5gLow=" + mRxLinkSpeedCount5gLow);
                pw.println("mWifiLogProto.rxLinkSpeedCount5gMid=" + mRxLinkSpeedCount5gMid);
                pw.println("mWifiLogProto.rxLinkSpeedCount5gHigh=" + mRxLinkSpeedCount5gHigh);
                pw.println("mWifiLogProto.rxLinkSpeedCount6gLow=" + mRxLinkSpeedCount6gLow);
                pw.println("mWifiLogProto.rxLinkSpeedCount6gMid=" + mRxLinkSpeedCount6gMid);
                pw.println("mWifiLogProto.rxLinkSpeedCount6gHigh=" + mRxLinkSpeedCount6gHigh);

                pw.println("mWifiLogProto.numIpRenewalFailure="
                        + mWifiLogProto.numIpRenewalFailure);
                pw.println("mWifiLogProto.connectionDurationStats="
                        + mConnectionDurationStats.toString());
                pw.println("mWifiLogProto.isExternalWifiScorerOn="
                        + mWifiLogProto.isExternalWifiScorerOn);
                pw.println("mWifiLogProto.wifiOffMetrics="
                        + mWifiOffMetrics.toString());
                pw.println("mWifiLogProto.softApConfigLimitationMetrics="
                        + mSoftApConfigLimitationMetrics.toString());
                pw.println("mChannelUtilizationHistogram2G:\n"
                        + mChannelUtilizationHistogram2G);
                pw.println("mChannelUtilizationHistogramAbove2G:\n"
                        + mChannelUtilizationHistogramAbove2G);
                pw.println("mTxThroughputMbpsHistogram2G:\n"
                        + mTxThroughputMbpsHistogram2G);
                pw.println("mRxThroughputMbpsHistogram2G:\n"
                        + mRxThroughputMbpsHistogram2G);
                pw.println("mTxThroughputMbpsHistogramAbove2G:\n"
                        + mTxThroughputMbpsHistogramAbove2G);
                pw.println("mRxThroughputMbpsHistogramAbove2G:\n"
                        + mRxThroughputMbpsHistogramAbove2G);
                pw.println("mCarrierWifiMetrics:\n"
                        + mCarrierWifiMetrics);
                pw.println(firstConnectAfterBootStatsToString(mFirstConnectAfterBootStats));

                dumpInitPartialScanMetrics(pw);
            }
        }
    }

    private void dumpInitPartialScanMetrics(PrintWriter pw) {
        pw.println("mInitPartialScanTotalCount:\n" + mInitPartialScanTotalCount);
        pw.println("mInitPartialScanSuccessCount:\n" + mInitPartialScanSuccessCount);
        pw.println("mInitPartialScanFailureCount:\n" + mInitPartialScanFailureCount);
        pw.println("mInitPartialScanSuccessHistogram:\n" + mInitPartialScanSuccessHistogram);
        pw.println("mInitPartialScanFailureHistogram:\n" + mInitPartialScanFailureHistogram);
    }

    private void printWifiUsabilityStatsEntry(PrintWriter pw, WifiUsabilityStatsEntry entry) {
        StringBuilder line = new StringBuilder();
        line.append("timestamp_ms=" + entry.timeStampMs);
        line.append(",rssi=" + entry.rssi);
        line.append(",link_speed_mbps=" + entry.linkSpeedMbps);
        line.append(",total_tx_success=" + entry.totalTxSuccess);
        line.append(",total_tx_retries=" + entry.totalTxRetries);
        line.append(",total_tx_bad=" + entry.totalTxBad);
        line.append(",total_rx_success=" + entry.totalRxSuccess);
        line.append(",total_radio_on_time_ms=" + entry.totalRadioOnTimeMs);
        line.append(",total_radio_tx_time_ms=" + entry.totalRadioTxTimeMs);
        line.append(",total_radio_rx_time_ms=" + entry.totalRadioRxTimeMs);
        line.append(",total_scan_time_ms=" + entry.totalScanTimeMs);
        line.append(",total_nan_scan_time_ms=" + entry.totalNanScanTimeMs);
        line.append(",total_background_scan_time_ms=" + entry.totalBackgroundScanTimeMs);
        line.append(",total_roam_scan_time_ms=" + entry.totalRoamScanTimeMs);
        line.append(",total_pno_scan_time_ms=" + entry.totalPnoScanTimeMs);
        line.append(",total_hotspot_2_scan_time_ms=" + entry.totalHotspot2ScanTimeMs);
        line.append(",wifi_score=" + entry.wifiScore);
        line.append(",wifi_usability_score=" + entry.wifiUsabilityScore);
        line.append(",seq_num_to_framework=" + entry.seqNumToFramework);
        line.append(",prediction_horizon_sec=" + entry.predictionHorizonSec);
        line.append(",total_cca_busy_freq_time_ms=" + entry.totalCcaBusyFreqTimeMs);
        line.append(",total_radio_on_freq_time_ms=" + entry.totalRadioOnFreqTimeMs);
        line.append(",total_beacon_rx=" + entry.totalBeaconRx);
        line.append(",probe_status_since_last_update=" + entry.probeStatusSinceLastUpdate);
        line.append(",probe_elapsed_time_ms_since_last_update="
                + entry.probeElapsedTimeSinceLastUpdateMs);
        line.append(",probe_mcs_rate_since_last_update=" + entry.probeMcsRateSinceLastUpdate);
        line.append(",rx_link_speed_mbps=" + entry.rxLinkSpeedMbps);
        line.append(",seq_num_inside_framework=" + entry.seqNumInsideFramework);
        line.append(",is_same_bssid_and_freq=" + entry.isSameBssidAndFreq);
        line.append(",device_mobility_state=" + entry.deviceMobilityState);
        pw.println(line.toString());
    }

    private void printDeviceMobilityStatePnoScanStats(PrintWriter pw,
            DeviceMobilityStatePnoScanStats stats) {
        StringBuilder line = new StringBuilder();
        line.append("device_mobility_state=" + stats.deviceMobilityState);
        line.append(",num_times_entered_state=" + stats.numTimesEnteredState);
        line.append(",total_duration_ms=" + stats.totalDurationMs);
        line.append(",pno_duration_ms=" + stats.pnoDurationMs);
        pw.println(line.toString());
    }

    private void printUserApprovalSuggestionAppReaction(PrintWriter pw) {
        pw.println("mUserApprovalSuggestionAppUiUserReaction:");
        for (UserReaction event : mUserApprovalSuggestionAppUiReactionList) {
            pw.println(event);
        }
    }

    private void printUserApprovalCarrierReaction(PrintWriter pw) {
        pw.println("mUserApprovalCarrierUiUserReaction:");
        for (UserReaction event : mUserApprovalCarrierUiReactionList) {
            pw.println(event);
        }
    }

    /**
     * Update various counts of saved network types
     * @param networks List of WifiConfigurations representing all saved networks, must not be null
     */
    public void updateSavedNetworks(List<WifiConfiguration> networks) {
        synchronized (mLock) {
            mWifiLogProto.numSavedNetworks = networks.size();
            mWifiLogProto.numSavedNetworksWithMacRandomization = 0;
            mWifiLogProto.numOpenNetworks = 0;
            mWifiLogProto.numLegacyPersonalNetworks = 0;
            mWifiLogProto.numLegacyEnterpriseNetworks = 0;
            mWifiLogProto.numEnhancedOpenNetworks = 0;
            mWifiLogProto.numWpa3PersonalNetworks = 0;
            mWifiLogProto.numWpa3EnterpriseNetworks = 0;
            mWifiLogProto.numWapiPersonalNetworks = 0;
            mWifiLogProto.numWapiEnterpriseNetworks = 0;
            mWifiLogProto.numNetworksAddedByUser = 0;
            mWifiLogProto.numNetworksAddedByApps = 0;
            mWifiLogProto.numHiddenNetworks = 0;
            mWifiLogProto.numPasspointNetworks = 0;

            for (WifiConfiguration config : networks) {
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                    mWifiLogProto.numOpenNetworks++;
                } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
                    mWifiLogProto.numEnhancedOpenNetworks++;
                } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WAPI_PSK)) {
                    mWifiLogProto.numWapiPersonalNetworks++;
                } else if (config.isEnterprise()) {
                    if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
                        mWifiLogProto.numWpa3EnterpriseNetworks++;
                    } else if (config.allowedKeyManagement.get(
                            WifiConfiguration.KeyMgmt.WAPI_CERT)) {
                        mWifiLogProto.numWapiEnterpriseNetworks++;
                    } else {
                        mWifiLogProto.numLegacyEnterpriseNetworks++;
                    }
                } else {
                    if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
                        mWifiLogProto.numWpa3PersonalNetworks++;
                    } else {
                        mWifiLogProto.numLegacyPersonalNetworks++;
                    }
                }
                mWifiLogProto.numNetworksAddedByApps++;
                if (config.hiddenSSID) {
                    mWifiLogProto.numHiddenNetworks++;
                }
                if (config.isPasspoint()) {
                    mWifiLogProto.numPasspointNetworks++;
                }
                if (config.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_PERSISTENT) {
                    mWifiLogProto.numSavedNetworksWithMacRandomization++;
                }
            }
        }
    }

    /**
     * Update metrics for saved Passpoint profiles.
     *
     * @param numSavedProfiles The number of saved Passpoint profiles
     * @param numConnectedProfiles The number of saved Passpoint profiles that have ever resulted
     *                             in a successful network connection
     */
    public void updateSavedPasspointProfiles(int numSavedProfiles, int numConnectedProfiles) {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviders = numSavedProfiles;
            mWifiLogProto.numPasspointProvidersSuccessfullyConnected = numConnectedProfiles;
        }
    }

    /**
     * Update number of times for type of saved Passpoint profile.
     *
     * @param providers Passpoint providers installed on the device.
     */
    public void updateSavedPasspointProfilesInfo(
            Map<String, PasspointProvider> providers) {
        int passpointType;
        int eapType;
        PasspointConfiguration config;
        synchronized (mLock) {
            mInstalledPasspointProfileTypeForR1.clear();
            mInstalledPasspointProfileTypeForR2.clear();
            for (Map.Entry<String, PasspointProvider> entry : providers.entrySet()) {
                config = entry.getValue().getConfig();
                if (config.getCredential().getUserCredential() != null) {
                    eapType = EAPConstants.EAP_TTLS;
                } else if (config.getCredential().getCertCredential() != null) {
                    eapType = EAPConstants.EAP_TLS;
                } else if (config.getCredential().getSimCredential() != null) {
                    eapType = config.getCredential().getSimCredential().getEapType();
                } else {
                    eapType = -1;
                }
                switch (eapType) {
                    case EAPConstants.EAP_TLS:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_TLS;
                        break;
                    case EAPConstants.EAP_TTLS:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_TTLS;
                        break;
                    case EAPConstants.EAP_SIM:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_SIM;
                        break;
                    case EAPConstants.EAP_AKA:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_AKA;
                        break;
                    case EAPConstants.EAP_AKA_PRIME:
                        passpointType =
                                WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_AKA_PRIME;
                        break;
                    default:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_UNKNOWN;

                }
                if (config.validateForR2()) {
                    mInstalledPasspointProfileTypeForR2.increment(passpointType);
                } else {
                    mInstalledPasspointProfileTypeForR1.increment(passpointType);
                }
            }
        }
    }

    /**
     * Increment initial partial scan count
     */
    public void incrementInitialPartialScanCount() {
        synchronized (mLock) {
            mInitPartialScanTotalCount++;
        }
    }

    /**
     * Report of initial partial scan
     * @param channelCount number of channels used in this scan
     * @param status true if scan resulted in a network connection attempt, false otherwise
     */
    public void reportInitialPartialScan(int channelCount, boolean status) {
        synchronized (mLock) {
            if (status) {
                mInitPartialScanSuccessCount++;
                mInitPartialScanSuccessHistogram.increment(channelCount);
            } else {
                mInitPartialScanFailureCount++;
                mInitPartialScanFailureHistogram.increment(channelCount);
            }
        }
    }

    /**
     * Put all metrics that were being tracked separately into mWifiLogProto
     */
    private void consolidateProto() {
        List<WifiMetricsProto.RssiPollCount> rssis = new ArrayList<>();
        synchronized (mLock) {
            int connectionEventCount = mConnectionEventList.size();
            // Exclude the current active un-ended connection event
            if (mCurrentConnectionEvent != null) {
                connectionEventCount--;
            }
            mWifiLogProto.connectionEvent =
                    new WifiMetricsProto.ConnectionEvent[connectionEventCount];
            for (int i = 0; i < connectionEventCount; i++) {
                mWifiLogProto.connectionEvent[i] = mConnectionEventList.get(i).mConnectionEvent;
            }

            //Convert the SparseIntArray of scanReturnEntry integers into ScanReturnEntry proto list
            mWifiLogProto.scanReturnEntries =
                    new WifiMetricsProto.WifiLog.ScanReturnEntry[mScanReturnEntries.size()];
            for (int i = 0; i < mScanReturnEntries.size(); i++) {
                mWifiLogProto.scanReturnEntries[i] = new WifiMetricsProto.WifiLog.ScanReturnEntry();
                mWifiLogProto.scanReturnEntries[i].scanReturnCode = mScanReturnEntries.keyAt(i);
                mWifiLogProto.scanReturnEntries[i].scanResultsCount = mScanReturnEntries.valueAt(i);
            }

            // Convert the SparseIntArray of systemStateEntry into WifiSystemStateEntry proto list
            // This one is slightly more complex, as the Sparse are indexed with:
            //     key: wifiState * 2 + isScreenOn, value: wifiStateCount
            mWifiLogProto.wifiSystemStateEntries =
                    new WifiMetricsProto.WifiLog
                    .WifiSystemStateEntry[mWifiSystemStateEntries.size()];
            for (int i = 0; i < mWifiSystemStateEntries.size(); i++) {
                mWifiLogProto.wifiSystemStateEntries[i] =
                        new WifiMetricsProto.WifiLog.WifiSystemStateEntry();
                mWifiLogProto.wifiSystemStateEntries[i].wifiState =
                        mWifiSystemStateEntries.keyAt(i) / 2;
                mWifiLogProto.wifiSystemStateEntries[i].wifiStateCount =
                        mWifiSystemStateEntries.valueAt(i);
                mWifiLogProto.wifiSystemStateEntries[i].isScreenOn =
                        (mWifiSystemStateEntries.keyAt(i) % 2) > 0;
            }
            mWifiLogProto.recordDurationSec = (int) ((mClock.getElapsedSinceBootMillis() / 1000)
                    - mRecordStartTimeSec);

            /**
             * Convert the SparseIntArrays of RSSI poll rssi, counts, and frequency to the
             * proto's repeated IntKeyVal array.
             */
            for (Map.Entry<Integer, SparseIntArray> entry : mRssiPollCountsMap.entrySet()) {
                int frequency = entry.getKey();
                SparseIntArray histogram = entry.getValue();
                for (int i = 0; i < histogram.size(); i++) {
                    WifiMetricsProto.RssiPollCount keyVal = new WifiMetricsProto.RssiPollCount();
                    keyVal.rssi = histogram.keyAt(i);
                    keyVal.count = histogram.valueAt(i);
                    keyVal.frequency = frequency;
                    rssis.add(keyVal);
                }
            }
            mWifiLogProto.rssiPollRssiCount = rssis.toArray(mWifiLogProto.rssiPollRssiCount);

            /**
             * Convert the SparseIntArray of RSSI delta rssi's and counts to the proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.rssiPollDeltaCount =
                    new WifiMetricsProto.RssiPollCount[mRssiDeltaCounts.size()];
            for (int i = 0; i < mRssiDeltaCounts.size(); i++) {
                mWifiLogProto.rssiPollDeltaCount[i] = new WifiMetricsProto.RssiPollCount();
                mWifiLogProto.rssiPollDeltaCount[i].rssi = mRssiDeltaCounts.keyAt(i);
                mWifiLogProto.rssiPollDeltaCount[i].count = mRssiDeltaCounts.valueAt(i);
            }

            /**
             * Add LinkSpeedCount objects from mLinkSpeedCounts to proto.
             */
            mWifiLogProto.linkSpeedCounts =
                    new WifiMetricsProto.LinkSpeedCount[mLinkSpeedCounts.size()];
            for (int i = 0; i < mLinkSpeedCounts.size(); i++) {
                mWifiLogProto.linkSpeedCounts[i] = mLinkSpeedCounts.valueAt(i);
            }

            /**
             * Convert the SparseIntArray of alert reasons and counts to the proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.alertReasonCount =
                    new WifiMetricsProto.AlertReasonCount[mWifiAlertReasonCounts.size()];
            for (int i = 0; i < mWifiAlertReasonCounts.size(); i++) {
                mWifiLogProto.alertReasonCount[i] = new WifiMetricsProto.AlertReasonCount();
                mWifiLogProto.alertReasonCount[i].reason = mWifiAlertReasonCounts.keyAt(i);
                mWifiLogProto.alertReasonCount[i].count = mWifiAlertReasonCounts.valueAt(i);
            }

            /**
            *  Convert the SparseIntArray of Wifi Score and counts to proto's repeated
            * IntKeyVal array.
            */
            mWifiLogProto.wifiScoreCount =
                    new WifiMetricsProto.WifiScoreCount[mWifiScoreCounts.size()];
            for (int score = 0; score < mWifiScoreCounts.size(); score++) {
                mWifiLogProto.wifiScoreCount[score] = new WifiMetricsProto.WifiScoreCount();
                mWifiLogProto.wifiScoreCount[score].score = mWifiScoreCounts.keyAt(score);
                mWifiLogProto.wifiScoreCount[score].count = mWifiScoreCounts.valueAt(score);
            }

            /**
             * Convert the SparseIntArray of Wifi Usability Score and counts to proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.wifiUsabilityScoreCount =
                new WifiMetricsProto.WifiUsabilityScoreCount[mWifiUsabilityScoreCounts.size()];
            for (int scoreIdx = 0; scoreIdx < mWifiUsabilityScoreCounts.size(); scoreIdx++) {
                mWifiLogProto.wifiUsabilityScoreCount[scoreIdx] =
                    new WifiMetricsProto.WifiUsabilityScoreCount();
                mWifiLogProto.wifiUsabilityScoreCount[scoreIdx].score =
                    mWifiUsabilityScoreCounts.keyAt(scoreIdx);
                mWifiLogProto.wifiUsabilityScoreCount[scoreIdx].count =
                    mWifiUsabilityScoreCounts.valueAt(scoreIdx);
            }

            /**
             * Convert the SparseIntArray of SoftAp Return codes and counts to proto's repeated
             * IntKeyVal array.
             */
            int codeCounts = mSoftApManagerReturnCodeCounts.size();
            mWifiLogProto.softApReturnCode = new WifiMetricsProto.SoftApReturnCodeCount[codeCounts];
            for (int sapCode = 0; sapCode < codeCounts; sapCode++) {
                mWifiLogProto.softApReturnCode[sapCode] =
                        new WifiMetricsProto.SoftApReturnCodeCount();
                mWifiLogProto.softApReturnCode[sapCode].startResult =
                        mSoftApManagerReturnCodeCounts.keyAt(sapCode);
                mWifiLogProto.softApReturnCode[sapCode].count =
                        mSoftApManagerReturnCodeCounts.valueAt(sapCode);
            }

            /**
             * Convert StaEventList to array of StaEvents
             */
            mWifiLogProto.staEventList = new StaEvent[mStaEventList.size()];
            for (int i = 0; i < mStaEventList.size(); i++) {
                mWifiLogProto.staEventList[i] = mStaEventList.get(i).staEvent;
            }
            mWifiLogProto.userActionEvents = new UserActionEvent[mUserActionEventList.size()];
            for (int i = 0; i < mUserActionEventList.size(); i++) {
                mWifiLogProto.userActionEvents[i] = mUserActionEventList.get(i).toProto();
            }
            mWifiLogProto.totalSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mTotalSsidsInScanHistogram);
            mWifiLogProto.totalBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mTotalBssidsInScanHistogram);
            mWifiLogProto.availableOpenSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableOpenSsidsInScanHistogram);
            mWifiLogProto.availableOpenBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableOpenBssidsInScanHistogram);
            mWifiLogProto.availableSavedSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableSavedSsidsInScanHistogram);
            mWifiLogProto.availableSavedBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableSavedBssidsInScanHistogram);
            mWifiLogProto.availableOpenOrSavedSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableOpenOrSavedSsidsInScanHistogram);
            mWifiLogProto.availableOpenOrSavedBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableOpenOrSavedBssidsInScanHistogram);
            mWifiLogProto.availableSavedPasspointProviderProfilesInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableSavedPasspointProviderProfilesInScanHistogram);
            mWifiLogProto.availableSavedPasspointProviderBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableSavedPasspointProviderBssidsInScanHistogram);
            mWifiLogProto.wifiAwareLog = mWifiAwareMetrics.consolidateProto();
            mWifiLogProto.wifiRttLog = mRttMetrics.consolidateProto();

            mWifiLogProto.pnoScanMetrics = mPnoScanMetrics;
            mWifiLogProto.wifiLinkLayerUsageStats = mWifiLinkLayerUsageStats;

            /**
             * Convert the SparseIntArray of "Connect to Network" notification types and counts to
             * proto's repeated IntKeyVal array.
             */
            ConnectToNetworkNotificationAndActionCount[] notificationCountArray =
                    new ConnectToNetworkNotificationAndActionCount[
                            mConnectToNetworkNotificationCount.size()];
            for (int i = 0; i < mConnectToNetworkNotificationCount.size(); i++) {
                ConnectToNetworkNotificationAndActionCount keyVal =
                        new ConnectToNetworkNotificationAndActionCount();
                keyVal.notification = mConnectToNetworkNotificationCount.keyAt(i);
                keyVal.recommender =
                        ConnectToNetworkNotificationAndActionCount.RECOMMENDER_OPEN;
                keyVal.count = mConnectToNetworkNotificationCount.valueAt(i);
                notificationCountArray[i] = keyVal;
            }
            mWifiLogProto.connectToNetworkNotificationCount = notificationCountArray;

            /**
             * Convert the SparseIntArray of "Connect to Network" notification types and counts to
             * proto's repeated IntKeyVal array.
             */
            ConnectToNetworkNotificationAndActionCount[] notificationActionCountArray =
                    new ConnectToNetworkNotificationAndActionCount[
                            mConnectToNetworkNotificationActionCount.size()];
            for (int i = 0; i < mConnectToNetworkNotificationActionCount.size(); i++) {
                ConnectToNetworkNotificationAndActionCount keyVal =
                        new ConnectToNetworkNotificationAndActionCount();
                int k = mConnectToNetworkNotificationActionCount.keyAt(i);
                keyVal.notification =  k / CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER;
                keyVal.action = k % CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER;
                keyVal.recommender =
                        ConnectToNetworkNotificationAndActionCount.RECOMMENDER_OPEN;
                keyVal.count = mConnectToNetworkNotificationActionCount.valueAt(i);
                notificationActionCountArray[i] = keyVal;
            }

            mWifiLogProto.installedPasspointProfileTypeForR1 =
                    convertPasspointProfilesToProto(mInstalledPasspointProfileTypeForR1);
            mWifiLogProto.installedPasspointProfileTypeForR2 =
                    convertPasspointProfilesToProto(mInstalledPasspointProfileTypeForR2);

            mWifiLogProto.connectToNetworkNotificationActionCount = notificationActionCountArray;

            mWifiLogProto.openNetworkRecommenderBlacklistSize =
                    mOpenNetworkRecommenderBlacklistSize;
            mWifiLogProto.isWifiNetworksAvailableNotificationOn =
                    mIsWifiNetworksAvailableNotificationOn;
            mWifiLogProto.numOpenNetworkRecommendationUpdates =
                    mNumOpenNetworkRecommendationUpdates;
            mWifiLogProto.numOpenNetworkConnectMessageFailedToSend =
                    mNumOpenNetworkConnectMessageFailedToSend;

            mWifiLogProto.observedHotspotR1ApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR1ApInScanHistogram);
            mWifiLogProto.observedHotspotR2ApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR2ApInScanHistogram);
            mWifiLogProto.observedHotspotR3ApsInScanHistogram =
                makeNumConnectableNetworksBucketArray(mObservedHotspotR3ApInScanHistogram);
            mWifiLogProto.observedHotspotR1EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR1EssInScanHistogram);
            mWifiLogProto.observedHotspotR2EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR2EssInScanHistogram);
            mWifiLogProto.observedHotspotR3EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR3EssInScanHistogram);
            mWifiLogProto.observedHotspotR1ApsPerEssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                            mObservedHotspotR1ApsPerEssInScanHistogram);
            mWifiLogProto.observedHotspotR2ApsPerEssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                            mObservedHotspotR2ApsPerEssInScanHistogram);
            mWifiLogProto.observedHotspotR3ApsPerEssInScanHistogram =
                makeNumConnectableNetworksBucketArray(
                    mObservedHotspotR3ApsPerEssInScanHistogram);

            mWifiLogProto.observed80211McSupportingApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObserved80211mcApInScanHistogram);

            if (mSoftApEventListTethered.size() > 0) {
                mWifiLogProto.softApConnectedClientsEventsTethered =
                        mSoftApEventListTethered.toArray(
                        mWifiLogProto.softApConnectedClientsEventsTethered);
            }
            if (mSoftApEventListLocalOnly.size() > 0) {
                mWifiLogProto.softApConnectedClientsEventsLocalOnly =
                        mSoftApEventListLocalOnly.toArray(
                        mWifiLogProto.softApConnectedClientsEventsLocalOnly);
            }

            mWifiLogProto.wifiPowerStats = mWifiPowerMetrics.buildProto();
            mWifiLogProto.wifiRadioUsage = mWifiPowerMetrics.buildWifiRadioUsageProto();
            mWifiLogProto.wifiWakeStats = mWifiWakeMetrics.buildProto();
            mWifiLogProto.isMacRandomizationOn = mContext.getResources().getBoolean(
                    R.bool.config_wifi_connected_mac_randomization_supported);
            mExperimentValues.wifiIsUnusableLoggingEnabled = mContext.getResources().getBoolean(
                    R.bool.config_wifiIsUnusableEventMetricsEnabled);
            mExperimentValues.linkSpeedCountsLoggingEnabled = mContext.getResources().getBoolean(
                    R.bool.config_wifiLinkSpeedMetricsEnabled);
            mExperimentValues.wifiDataStallMinTxBad = mContext.getResources().getInteger(
                    R.integer.config_wifiDataStallMinTxBad);
            mExperimentValues.wifiDataStallMinTxSuccessWithoutRx =
                    mContext.getResources().getInteger(
                            R.integer.config_wifiDataStallMinTxSuccessWithoutRx);
            mWifiLogProto.experimentValues = mExperimentValues;
            mWifiLogProto.wifiIsUnusableEventList =
                    new WifiIsUnusableEvent[mWifiIsUnusableList.size()];
            for (int i = 0; i < mWifiIsUnusableList.size(); i++) {
                mWifiLogProto.wifiIsUnusableEventList[i] = mWifiIsUnusableList.get(i).event;
            }
            mWifiLogProto.hardwareRevision = SystemProperties.get("ro.boot.revision", "");

            // Postprocessing on WifiUsabilityStats to upload an equal number of LABEL_GOOD and
            // LABEL_BAD WifiUsabilityStats
            final int numUsabilityStats = Math.min(
                    Math.min(mWifiUsabilityStatsListBad.size(),
                            mWifiUsabilityStatsListGood.size()),
                    MAX_WIFI_USABILITY_STATS_PER_TYPE_TO_UPLOAD);
            LinkedList<WifiUsabilityStats> usabilityStatsGoodCopy =
                    new LinkedList<>(mWifiUsabilityStatsListGood);
            LinkedList<WifiUsabilityStats> usabilityStatsBadCopy =
                    new LinkedList<>(mWifiUsabilityStatsListBad);
            mWifiLogProto.wifiUsabilityStatsList = new WifiUsabilityStats[numUsabilityStats * 2];
            for (int i = 0; i < numUsabilityStats; i++) {
                mWifiLogProto.wifiUsabilityStatsList[2 * i] = usabilityStatsGoodCopy.remove(
                        mRand.nextInt(usabilityStatsGoodCopy.size()));
                mWifiLogProto.wifiUsabilityStatsList[2 * i + 1] = usabilityStatsBadCopy.remove(
                        mRand.nextInt(usabilityStatsBadCopy.size()));
            }
            mWifiLogProto.mobilityStatePnoStatsList =
                    new DeviceMobilityStatePnoScanStats[mMobilityStatePnoStatsMap.size()];
            for (int i = 0; i < mMobilityStatePnoStatsMap.size(); i++) {
                mWifiLogProto.mobilityStatePnoStatsList[i] = mMobilityStatePnoStatsMap.valueAt(i);
            }
            mWifiLogProto.wifiP2PStats = mWifiP2pMetrics.consolidateProto();
            mWifiLogProto.wifiDppLog = mDppMetrics.consolidateProto();
            mWifiLogProto.wifiConfigStoreIo = new WifiMetricsProto.WifiConfigStoreIO();
            mWifiLogProto.wifiConfigStoreIo.readDurations =
                    makeWifiConfigStoreIODurationBucketArray(mWifiConfigStoreReadDurationHistogram);
            mWifiLogProto.wifiConfigStoreIo.writeDurations =
                    makeWifiConfigStoreIODurationBucketArray(
                            mWifiConfigStoreWriteDurationHistogram);

            LinkProbeStats linkProbeStats = new LinkProbeStats();
            linkProbeStats.successRssiCounts = mLinkProbeSuccessRssiCounts.toProto();
            linkProbeStats.failureRssiCounts = mLinkProbeFailureRssiCounts.toProto();
            linkProbeStats.successLinkSpeedCounts = mLinkProbeSuccessLinkSpeedCounts.toProto();
            linkProbeStats.failureLinkSpeedCounts = mLinkProbeFailureLinkSpeedCounts.toProto();
            linkProbeStats.successSecondsSinceLastTxSuccessHistogram =
                    mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram.toProto();
            linkProbeStats.failureSecondsSinceLastTxSuccessHistogram =
                    mLinkProbeFailureSecondsSinceLastTxSuccessHistogram.toProto();
            linkProbeStats.successElapsedTimeMsHistogram =
                    mLinkProbeSuccessElapsedTimeMsHistogram.toProto();
            linkProbeStats.failureReasonCounts = mLinkProbeFailureReasonCounts.toProto(
                    LinkProbeFailureReasonCount.class,
                    (reason, count) -> {
                        LinkProbeFailureReasonCount c = new LinkProbeFailureReasonCount();
                        c.failureReason = linkProbeFailureReasonToProto(reason);
                        c.count = count;
                        return c;
                    });
            linkProbeStats.experimentProbeCounts = mLinkProbeExperimentProbeCounts.toProto(
                    ExperimentProbeCounts.class,
                    (experimentId, probeCount) -> {
                        ExperimentProbeCounts c = new ExperimentProbeCounts();
                        c.experimentId = experimentId;
                        c.probeCount = probeCount;
                        return c;
                    });
            mWifiLogProto.linkProbeStats = linkProbeStats;

            mWifiLogProto.networkSelectionExperimentDecisionsList =
                    makeNetworkSelectionExperimentDecisionsList();

            mWifiNetworkRequestApiLog.networkMatchSizeHistogram =
                    mWifiNetworkRequestApiMatchSizeHistogram.toProto();
            mWifiLogProto.wifiNetworkRequestApiLog = mWifiNetworkRequestApiLog;

            mWifiNetworkSuggestionApiLog.networkListSizeHistogram =
                    mWifiNetworkSuggestionApiListSizeHistogram.toProto();
            mWifiNetworkSuggestionApiLog.appCountPerType =
                    mWifiNetworkSuggestionApiAppTypeCounter.toProto(SuggestionAppCount.class,
                            (key, count) -> {
                                SuggestionAppCount entry = new SuggestionAppCount();
                                entry.appType = key;
                                entry.count = count;
                                return entry;
                            });
            mWifiLogProto.wifiNetworkSuggestionApiLog = mWifiNetworkSuggestionApiLog;

            UserReactionToApprovalUiEvent events = new UserReactionToApprovalUiEvent();
            events.userApprovalAppUiReaction = mUserApprovalSuggestionAppUiReactionList
                    .toArray(new UserReaction[0]);
            events.userApprovalCarrierUiReaction = mUserApprovalCarrierUiReactionList
                    .toArray(new UserReaction[0]);
            mWifiLogProto.userReactionToApprovalUiEvent = events;

            mWifiLockStats.highPerfLockAcqDurationSecHistogram =
                    mWifiLockHighPerfAcqDurationSecHistogram.toProto();

            mWifiLockStats.lowLatencyLockAcqDurationSecHistogram =
                    mWifiLockLowLatencyAcqDurationSecHistogram.toProto();

            mWifiLockStats.highPerfActiveSessionDurationSecHistogram =
                    mWifiLockHighPerfActiveSessionDurationSecHistogram.toProto();

            mWifiLockStats.lowLatencyActiveSessionDurationSecHistogram =
                    mWifiLockLowLatencyActiveSessionDurationSecHistogram.toProto();

            mWifiLogProto.wifiLockStats = mWifiLockStats;
            mWifiLogProto.wifiToggleStats = mWifiToggleStats;

            /**
             * Convert the SparseIntArray of passpoint provision failure code
             * and counts to the proto's repeated IntKeyVal array.
             */
            mWifiLogProto.passpointProvisionStats = new PasspointProvisionStats();
            mWifiLogProto.passpointProvisionStats.numProvisionSuccess = mNumProvisionSuccess;
            mWifiLogProto.passpointProvisionStats.provisionFailureCount =
                    mPasspointProvisionFailureCounts.toProto(ProvisionFailureCount.class,
                            (key, count) -> {
                                ProvisionFailureCount entry = new ProvisionFailureCount();
                                entry.failureCode = key;
                                entry.count = count;
                                return entry;
                            });
            // 'G' is due to that 1st Letter after _ becomes capital during protobuff compilation
            mWifiLogProto.txLinkSpeedCount2G = mTxLinkSpeedCount2g.toProto();
            mWifiLogProto.txLinkSpeedCount5GLow = mTxLinkSpeedCount5gLow.toProto();
            mWifiLogProto.txLinkSpeedCount5GMid = mTxLinkSpeedCount5gMid.toProto();
            mWifiLogProto.txLinkSpeedCount5GHigh = mTxLinkSpeedCount5gHigh.toProto();
            mWifiLogProto.txLinkSpeedCount6GLow = mTxLinkSpeedCount6gLow.toProto();
            mWifiLogProto.txLinkSpeedCount6GMid = mTxLinkSpeedCount6gMid.toProto();
            mWifiLogProto.txLinkSpeedCount6GHigh = mTxLinkSpeedCount6gHigh.toProto();

            mWifiLogProto.rxLinkSpeedCount2G = mRxLinkSpeedCount2g.toProto();
            mWifiLogProto.rxLinkSpeedCount5GLow = mRxLinkSpeedCount5gLow.toProto();
            mWifiLogProto.rxLinkSpeedCount5GMid = mRxLinkSpeedCount5gMid.toProto();
            mWifiLogProto.rxLinkSpeedCount5GHigh = mRxLinkSpeedCount5gHigh.toProto();
            mWifiLogProto.rxLinkSpeedCount6GLow = mRxLinkSpeedCount6gLow.toProto();
            mWifiLogProto.rxLinkSpeedCount6GMid = mRxLinkSpeedCount6gMid.toProto();
            mWifiLogProto.rxLinkSpeedCount6GHigh = mRxLinkSpeedCount6gHigh.toProto();

            HealthMonitorMetrics healthMonitorMetrics = mWifiHealthMonitor.buildProto();
            if (healthMonitorMetrics != null) {
                mWifiLogProto.healthMonitorMetrics = healthMonitorMetrics;
            }
            mWifiLogProto.bssidBlocklistStats = mBssidBlocklistStats.toProto();
            mWifiLogProto.connectionDurationStats = mConnectionDurationStats.toProto();
            mWifiLogProto.wifiOffMetrics = mWifiOffMetrics.toProto();
            mWifiLogProto.softApConfigLimitationMetrics = mSoftApConfigLimitationMetrics.toProto();
            mWifiLogProto.channelUtilizationHistogram =
                    new WifiMetricsProto.ChannelUtilizationHistogram();
            mWifiLogProto.channelUtilizationHistogram.utilization2G =
                    mChannelUtilizationHistogram2G.toProto();
            mWifiLogProto.channelUtilizationHistogram.utilizationAbove2G =
                    mChannelUtilizationHistogramAbove2G.toProto();
            mWifiLogProto.throughputMbpsHistogram =
                    new WifiMetricsProto.ThroughputMbpsHistogram();
            mWifiLogProto.throughputMbpsHistogram.tx2G =
                    mTxThroughputMbpsHistogram2G.toProto();
            mWifiLogProto.throughputMbpsHistogram.txAbove2G =
                    mTxThroughputMbpsHistogramAbove2G.toProto();
            mWifiLogProto.throughputMbpsHistogram.rx2G =
                    mRxThroughputMbpsHistogram2G.toProto();
            mWifiLogProto.throughputMbpsHistogram.rxAbove2G =
                    mRxThroughputMbpsHistogramAbove2G.toProto();
            mWifiLogProto.meteredNetworkStatsSaved = mMeteredNetworkStatsBuilder.toProto(false);
            mWifiLogProto.meteredNetworkStatsSuggestion = mMeteredNetworkStatsBuilder.toProto(true);

            InitPartialScanStats initialPartialScanStats = new InitPartialScanStats();
            initialPartialScanStats.numScans = mInitPartialScanTotalCount;
            initialPartialScanStats.numSuccessScans = mInitPartialScanSuccessCount;
            initialPartialScanStats.numFailureScans = mInitPartialScanFailureCount;
            initialPartialScanStats.successfulScanChannelCountHistogram =
                    mInitPartialScanSuccessHistogram.toProto();
            initialPartialScanStats.failedScanChannelCountHistogram =
                    mInitPartialScanFailureHistogram.toProto();
            mWifiLogProto.initPartialScanStats = initialPartialScanStats;
            mWifiLogProto.carrierWifiMetrics = mCarrierWifiMetrics.toProto();
            mWifiLogProto.mainlineModuleVersion = mWifiHealthMonitor.getWifiStackVersion();
            mWifiLogProto.firstConnectAfterBootStats = mFirstConnectAfterBootStats;
        }
    }

    private static int linkProbeFailureReasonToProto(int reason) {
        switch (reason) {
            case WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_MCS_UNSUPPORTED;
            case WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_NO_ACK:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_NO_ACK;
            case WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_TIMEOUT:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_TIMEOUT;
            case WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_ALREADY_STARTED:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_ALREADY_STARTED;
            default:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_UNKNOWN;
        }
    }

    private NetworkSelectionExperimentDecisions[] makeNetworkSelectionExperimentDecisionsList() {
        NetworkSelectionExperimentDecisions[] results = new NetworkSelectionExperimentDecisions[
                mNetworkSelectionExperimentPairNumChoicesCounts.size()];
        int i = 0;
        for (Map.Entry<Pair<Integer, Integer>, NetworkSelectionExperimentResults> entry :
                mNetworkSelectionExperimentPairNumChoicesCounts.entrySet()) {
            NetworkSelectionExperimentDecisions result = new NetworkSelectionExperimentDecisions();
            result.experiment1Id = entry.getKey().first;
            result.experiment2Id = entry.getKey().second;
            result.sameSelectionNumChoicesCounter =
                    entry.getValue().sameSelectionNumChoicesCounter.toProto();
            result.differentSelectionNumChoicesCounter =
                    entry.getValue().differentSelectionNumChoicesCounter.toProto();
            results[i] = result;
            i++;
        }
        return results;
    }

    /** Sets the scoring experiment id to current value */
    private void consolidateScoringParams() {
        synchronized (mLock) {
            if (mScoringParams != null) {
                int experimentIdentifier = mScoringParams.getExperimentIdentifier();
                if (experimentIdentifier == 0) {
                    mWifiLogProto.scoreExperimentId = "";
                } else {
                    mWifiLogProto.scoreExperimentId = "x" + experimentIdentifier;
                }
            }
        }
    }

    private WifiMetricsProto.NumConnectableNetworksBucket[] makeNumConnectableNetworksBucketArray(
            SparseIntArray sia) {
        WifiMetricsProto.NumConnectableNetworksBucket[] array =
                new WifiMetricsProto.NumConnectableNetworksBucket[sia.size()];
        for (int i = 0; i < sia.size(); i++) {
            WifiMetricsProto.NumConnectableNetworksBucket keyVal =
                    new WifiMetricsProto.NumConnectableNetworksBucket();
            keyVal.numConnectableNetworks = sia.keyAt(i);
            keyVal.count = sia.valueAt(i);
            array[i] = keyVal;
        }
        return array;
    }

    private WifiMetricsProto.WifiConfigStoreIO.DurationBucket[]
            makeWifiConfigStoreIODurationBucketArray(SparseIntArray sia) {
        MetricsUtils.GenericBucket[] genericBuckets =
                MetricsUtils.linearHistogramToGenericBuckets(sia,
                        WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS);
        WifiMetricsProto.WifiConfigStoreIO.DurationBucket[] array =
                new WifiMetricsProto.WifiConfigStoreIO.DurationBucket[genericBuckets.length];
        try {
            for (int i = 0; i < genericBuckets.length; i++) {
                array[i] = new WifiMetricsProto.WifiConfigStoreIO.DurationBucket();
                array[i].rangeStartMs = toIntExact(genericBuckets[i].start);
                array[i].rangeEndMs = toIntExact(genericBuckets[i].end);
                array[i].count = genericBuckets[i].count;
            }
        } catch (ArithmeticException e) {
            // Return empty array on any overflow errors.
            array = new WifiMetricsProto.WifiConfigStoreIO.DurationBucket[0];
        }
        return array;
    }

    /**
     * Clear all WifiMetrics, except for currentConnectionEvent and Open Network Notification
     * feature enabled state, blacklist size.
     */
    private void clear() {
        synchronized (mLock) {
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mScanReturnEntries.clear();
            mWifiSystemStateEntries.clear();
            mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
            mRssiPollCountsMap.clear();
            mRssiDeltaCounts.clear();
            mLinkSpeedCounts.clear();
            mTxLinkSpeedCount2g.clear();
            mTxLinkSpeedCount5gLow.clear();
            mTxLinkSpeedCount5gMid.clear();
            mTxLinkSpeedCount5gHigh.clear();
            mTxLinkSpeedCount6gLow.clear();
            mTxLinkSpeedCount6gMid.clear();
            mTxLinkSpeedCount6gHigh.clear();
            mRxLinkSpeedCount2g.clear();
            mRxLinkSpeedCount5gLow.clear();
            mRxLinkSpeedCount5gMid.clear();
            mRxLinkSpeedCount5gHigh.clear();
            mRxLinkSpeedCount6gLow.clear();
            mRxLinkSpeedCount6gMid.clear();
            mRxLinkSpeedCount6gHigh.clear();
            mWifiAlertReasonCounts.clear();
            mWifiScoreCounts.clear();
            mWifiUsabilityScoreCounts.clear();
            mWifiLogProto.clear();
            mScanResultRssiTimestampMillis = -1;
            mSoftApManagerReturnCodeCounts.clear();
            mStaEventList.clear();
            mUserActionEventList.clear();
            mWifiAwareMetrics.clear();
            mRttMetrics.clear();
            mTotalSsidsInScanHistogram.clear();
            mTotalBssidsInScanHistogram.clear();
            mAvailableOpenSsidsInScanHistogram.clear();
            mAvailableOpenBssidsInScanHistogram.clear();
            mAvailableSavedSsidsInScanHistogram.clear();
            mAvailableSavedBssidsInScanHistogram.clear();
            mAvailableOpenOrSavedSsidsInScanHistogram.clear();
            mAvailableOpenOrSavedBssidsInScanHistogram.clear();
            mAvailableSavedPasspointProviderProfilesInScanHistogram.clear();
            mAvailableSavedPasspointProviderBssidsInScanHistogram.clear();
            mPnoScanMetrics.clear();
            mWifiLinkLayerUsageStats.clear();
            mConnectToNetworkNotificationCount.clear();
            mConnectToNetworkNotificationActionCount.clear();
            mNumOpenNetworkRecommendationUpdates = 0;
            mNumOpenNetworkConnectMessageFailedToSend = 0;
            mObservedHotspotR1ApInScanHistogram.clear();
            mObservedHotspotR2ApInScanHistogram.clear();
            mObservedHotspotR3ApInScanHistogram.clear();
            mObservedHotspotR1EssInScanHistogram.clear();
            mObservedHotspotR2EssInScanHistogram.clear();
            mObservedHotspotR3EssInScanHistogram.clear();
            mObservedHotspotR1ApsPerEssInScanHistogram.clear();
            mObservedHotspotR2ApsPerEssInScanHistogram.clear();
            mObservedHotspotR3ApsPerEssInScanHistogram.clear();
            mSoftApEventListTethered.clear();
            mSoftApEventListLocalOnly.clear();
            mWifiWakeMetrics.clear();
            mObserved80211mcApInScanHistogram.clear();
            mWifiIsUnusableList.clear();
            mInstalledPasspointProfileTypeForR1.clear();
            mInstalledPasspointProfileTypeForR2.clear();
            mWifiUsabilityStatsListGood.clear();
            mWifiUsabilityStatsListBad.clear();
            mWifiUsabilityStatsEntriesList.clear();
            mMobilityStatePnoStatsMap.clear();
            mWifiP2pMetrics.clear();
            mDppMetrics.clear();
            mWifiUsabilityStatsCounter = 0;
            mLastBssid = null;
            mLastFrequency = -1;
            mSeqNumInsideFramework = 0;
            mLastWifiUsabilityScore = -1;
            mLastWifiUsabilityScoreNoReset = -1;
            mLastPredictionHorizonSec = -1;
            mLastPredictionHorizonSecNoReset = -1;
            mSeqNumToFramework = -1;
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
            mProbeElapsedTimeSinceLastUpdateMs = -1;
            mProbeMcsRateSinceLastUpdate = -1;
            mScoreBreachLowTimeMillis = -1;
            mMeteredNetworkStatsBuilder.clear();
            mWifiConfigStoreReadDurationHistogram.clear();
            mWifiConfigStoreWriteDurationHistogram.clear();
            mLinkProbeSuccessRssiCounts.clear();
            mLinkProbeFailureRssiCounts.clear();
            mLinkProbeSuccessLinkSpeedCounts.clear();
            mLinkProbeFailureLinkSpeedCounts.clear();
            mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram.clear();
            mLinkProbeFailureSecondsSinceLastTxSuccessHistogram.clear();
            mLinkProbeSuccessElapsedTimeMsHistogram.clear();
            mLinkProbeFailureReasonCounts.clear();
            mLinkProbeExperimentProbeCounts.clear();
            mLinkProbeStaEventCount = 0;
            mNetworkSelectionExperimentPairNumChoicesCounts.clear();
            mWifiNetworkSuggestionApiLog.clear();
            mWifiNetworkRequestApiMatchSizeHistogram.clear();
            mWifiNetworkSuggestionApiListSizeHistogram.clear();
            mWifiNetworkSuggestionApiAppTypeCounter.clear();
            mUserApprovalSuggestionAppUiReactionList.clear();
            mUserApprovalCarrierUiReactionList.clear();
            mWifiLockHighPerfAcqDurationSecHistogram.clear();
            mWifiLockLowLatencyAcqDurationSecHistogram.clear();
            mWifiLockHighPerfActiveSessionDurationSecHistogram.clear();
            mWifiLockLowLatencyActiveSessionDurationSecHistogram.clear();
            mWifiLockStats.clear();
            mWifiToggleStats.clear();
            mChannelUtilizationHistogram2G.clear();
            mChannelUtilizationHistogramAbove2G.clear();
            mTxThroughputMbpsHistogram2G.clear();
            mRxThroughputMbpsHistogram2G.clear();
            mTxThroughputMbpsHistogramAbove2G.clear();
            mRxThroughputMbpsHistogramAbove2G.clear();
            mPasspointProvisionFailureCounts.clear();
            mNumProvisionSuccess = 0;
            mBssidBlocklistStats = new BssidBlocklistStats();
            mConnectionDurationStats.clear();
            mWifiLogProto.isExternalWifiScorerOn = false;
            mWifiOffMetrics.clear();
            mSoftApConfigLimitationMetrics.clear();
            //Initial partial scan metrics
            mInitPartialScanTotalCount = 0;
            mInitPartialScanSuccessCount = 0;
            mInitPartialScanFailureCount = 0;
            mInitPartialScanSuccessHistogram.clear();
            mInitPartialScanFailureHistogram.clear();
            mCarrierWifiMetrics.clear();
            mFirstConnectAfterBootStats = null;
        }
    }

    /**
     *  Set screen state (On/Off)
     */
    public void setScreenState(boolean screenOn) {
        synchronized (mLock) {
            mScreenOn = screenOn;
        }
    }

    /**
     *  Set wifi state (WIFI_UNKNOWN, WIFI_DISABLED, WIFI_DISCONNECTED, WIFI_ASSOCIATED)
     */
    public void setWifiState(int wifiState) {
        synchronized (mLock) {
            mWifiState = wifiState;
            mWifiWins = (wifiState == WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
            mWifiWinsUsabilityScore = (wifiState == WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
            if (wifiState == WifiMetricsProto.WifiLog.WIFI_DISCONNECTED
                    || wifiState == WifiMetricsProto.WifiLog.WIFI_DISABLED) {
                mWifiStatusBuilder = new WifiStatusBuilder();
            }
        }
    }

    /**
     * Message handler for interesting WifiMonitor messages. Generates StaEvents
     */
    private void processMessage(Message msg) {
        StaEvent event = new StaEvent();
        boolean logEvent = true;
        switch (msg.what) {
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                event.type = StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT;
                event.associationTimedOut = msg.arg1 > 0 ? true : false;
                event.status = msg.arg2;
                break;
            case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                event.type = StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT;
                switch (msg.arg1) {
                    case WifiManager.ERROR_AUTH_FAILURE_NONE:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_NONE;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_TIMEOUT:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_TIMEOUT;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_WRONG_PSWD;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_EAP_FAILURE;
                        break;
                    default:
                        break;
                }
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                event.type = StaEvent.TYPE_NETWORK_CONNECTION_EVENT;
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                event.type = StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT;
                event.reason = msg.arg2;
                event.localGen = msg.arg1 == 0 ? false : true;
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                logEvent = false;
                StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                mSupplicantStateChangeBitmask |= supplicantStateToBit(stateChangeResult.state);
                break;
            case WifiMonitor.ASSOCIATED_BSSID_EVENT:
                event.type = StaEvent.TYPE_CMD_ASSOCIATED_BSSID;
                break;
            case WifiMonitor.TARGET_BSSID_EVENT:
                event.type = StaEvent.TYPE_CMD_TARGET_BSSID;
                break;
            default:
                return;
        }
        if (logEvent) {
            addStaEvent(event);
        }
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     */
    public void logStaEvent(int type) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, null);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, WifiConfiguration config) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, config);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     */
    public void logStaEvent(int type, int frameworkDisconnectReason) {
        logStaEvent(type, frameworkDisconnectReason, null);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, int frameworkDisconnectReason, WifiConfiguration config) {
        switch (type) {
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
            case StaEvent.TYPE_CMD_START_CONNECT:
            case StaEvent.TYPE_CMD_START_ROAM:
            case StaEvent.TYPE_CONNECT_NETWORK:
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
                mWifiStatusBuilder.setValidated(true);
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
            case StaEvent.TYPE_SCORE_BREACH:
            case StaEvent.TYPE_MAC_CHANGE:
            case StaEvent.TYPE_WIFI_ENABLED:
            case StaEvent.TYPE_WIFI_DISABLED:
            case StaEvent.TYPE_WIFI_USABILITY_SCORE_BREACH:
                break;
            default:
                Log.e(TAG, "Unknown StaEvent:" + type);
                return;
        }
        StaEvent event = new StaEvent();
        event.type = type;
        if (frameworkDisconnectReason != StaEvent.DISCONNECT_UNKNOWN) {
            event.frameworkDisconnectReason = frameworkDisconnectReason;
        }
        event.configInfo = createConfigInfo(config);
        addStaEvent(event);
    }

    private void addStaEvent(StaEvent staEvent) {
        staEvent.startTimeMillis = mClock.getElapsedSinceBootMillis();
        staEvent.lastRssi = mLastPollRssi;
        staEvent.lastFreq = mLastPollFreq;
        staEvent.lastLinkSpeed = mLastPollLinkSpeed;
        staEvent.supplicantStateChangesBitmask = mSupplicantStateChangeBitmask;
        staEvent.lastScore = mLastScore;
        staEvent.lastWifiUsabilityScore = mLastWifiUsabilityScore;
        staEvent.lastPredictionHorizonSec = mLastPredictionHorizonSec;
        staEvent.mobileTxBytes = mFacade.getMobileTxBytes();
        staEvent.mobileRxBytes = mFacade.getMobileRxBytes();
        staEvent.totalTxBytes = mFacade.getTotalTxBytes();
        staEvent.totalRxBytes = mFacade.getTotalRxBytes();
        staEvent.screenOn = mScreenOn;
        if (mWifiDataStall != null) {
            staEvent.isCellularDataAvailable = mWifiDataStall.isCellularDataAvailable();
        }
        staEvent.isAdaptiveConnectivityEnabled = mAdaptiveConnectivityEnabled;
        mSupplicantStateChangeBitmask = 0;
        mLastPollRssi = -127;
        mLastPollFreq = -1;
        mLastPollLinkSpeed = -1;
        mLastPollRxLinkSpeed = -1;
        mLastScore = -1;
        mLastWifiUsabilityScore = -1;
        mLastPredictionHorizonSec = -1;
        synchronized (mLock) {
            mStaEventList.add(new StaEventWithTime(staEvent, mClock.getWallClockMillis()));
            // Prune StaEventList if it gets too long
            if (mStaEventList.size() > MAX_STA_EVENTS) mStaEventList.remove();
        }
    }

    private ConfigInfo createConfigInfo(WifiConfiguration config) {
        if (config == null) return null;
        ConfigInfo info = new ConfigInfo();
        info.allowedKeyManagement = bitSetToInt(config.allowedKeyManagement);
        info.allowedProtocols = bitSetToInt(config.allowedProtocols);
        info.allowedAuthAlgorithms = bitSetToInt(config.allowedAuthAlgorithms);
        info.allowedPairwiseCiphers = bitSetToInt(config.allowedPairwiseCiphers);
        info.allowedGroupCiphers = bitSetToInt(config.allowedGroupCiphers);
        info.hiddenSsid = config.hiddenSSID;
        info.isPasspoint = config.isPasspoint();
        info.isEphemeral = config.isEphemeral();
        info.hasEverConnected = config.getNetworkSelectionStatus().hasEverConnected();
        ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
        if (candidate != null) {
            info.scanRssi = candidate.level;
            info.scanFreq = candidate.frequency;
        }
        return info;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public WifiAwareMetrics getWifiAwareMetrics() {
        return mWifiAwareMetrics;
    }

    public WifiWakeMetrics getWakeupMetrics() {
        return mWifiWakeMetrics;
    }

    public RttMetrics getRttMetrics() {
        return mRttMetrics;
    }

    // Rather than generate a StaEvent for each SUPPLICANT_STATE_CHANGE, cache these in a bitmask
    // and attach it to the next event which is generated.
    private int mSupplicantStateChangeBitmask = 0;

    /**
     * Converts a SupplicantState value to a single bit, with position defined by
     * {@code StaEvent.SupplicantState}
     */
    public static int supplicantStateToBit(SupplicantState state) {
        switch(state) {
            case DISCONNECTED:
                return 1 << StaEvent.STATE_DISCONNECTED;
            case INTERFACE_DISABLED:
                return 1 << StaEvent.STATE_INTERFACE_DISABLED;
            case INACTIVE:
                return 1 << StaEvent.STATE_INACTIVE;
            case SCANNING:
                return 1 << StaEvent.STATE_SCANNING;
            case AUTHENTICATING:
                return 1 << StaEvent.STATE_AUTHENTICATING;
            case ASSOCIATING:
                return 1 << StaEvent.STATE_ASSOCIATING;
            case ASSOCIATED:
                return 1 << StaEvent.STATE_ASSOCIATED;
            case FOUR_WAY_HANDSHAKE:
                return 1 << StaEvent.STATE_FOUR_WAY_HANDSHAKE;
            case GROUP_HANDSHAKE:
                return 1 << StaEvent.STATE_GROUP_HANDSHAKE;
            case COMPLETED:
                return 1 << StaEvent.STATE_COMPLETED;
            case DORMANT:
                return 1 << StaEvent.STATE_DORMANT;
            case UNINITIALIZED:
                return 1 << StaEvent.STATE_UNINITIALIZED;
            case INVALID:
                return 1 << StaEvent.STATE_INVALID;
            default:
                Log.wtf(TAG, "Got unknown supplicant state: " + state.ordinal());
                return 0;
        }
    }

    private static String supplicantStateChangesBitmaskToString(int mask) {
        StringBuilder sb = new StringBuilder();
        sb.append("supplicantStateChangeEvents: {");
        if ((mask & (1 << StaEvent.STATE_DISCONNECTED)) > 0) sb.append(" DISCONNECTED");
        if ((mask & (1 << StaEvent.STATE_INTERFACE_DISABLED)) > 0) sb.append(" INTERFACE_DISABLED");
        if ((mask & (1 << StaEvent.STATE_INACTIVE)) > 0) sb.append(" INACTIVE");
        if ((mask & (1 << StaEvent.STATE_SCANNING)) > 0) sb.append(" SCANNING");
        if ((mask & (1 << StaEvent.STATE_AUTHENTICATING)) > 0) sb.append(" AUTHENTICATING");
        if ((mask & (1 << StaEvent.STATE_ASSOCIATING)) > 0) sb.append(" ASSOCIATING");
        if ((mask & (1 << StaEvent.STATE_ASSOCIATED)) > 0) sb.append(" ASSOCIATED");
        if ((mask & (1 << StaEvent.STATE_FOUR_WAY_HANDSHAKE)) > 0) sb.append(" FOUR_WAY_HANDSHAKE");
        if ((mask & (1 << StaEvent.STATE_GROUP_HANDSHAKE)) > 0) sb.append(" GROUP_HANDSHAKE");
        if ((mask & (1 << StaEvent.STATE_COMPLETED)) > 0) sb.append(" COMPLETED");
        if ((mask & (1 << StaEvent.STATE_DORMANT)) > 0) sb.append(" DORMANT");
        if ((mask & (1 << StaEvent.STATE_UNINITIALIZED)) > 0) sb.append(" UNINITIALIZED");
        if ((mask & (1 << StaEvent.STATE_INVALID)) > 0) sb.append(" INVALID");
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Returns a human readable string from a Sta Event. Only adds information relevant to the event
     * type.
     */
    public static String staEventToString(StaEvent event) {
        if (event == null) return "<NULL>";
        StringBuilder sb = new StringBuilder();
        switch (event.type) {
            case StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT:
                sb.append("ASSOCIATION_REJECTION_EVENT")
                        .append(" timedOut=").append(event.associationTimedOut)
                        .append(" status=").append(event.status).append(":")
                        .append(ISupplicantStaIfaceCallback.StatusCode.toString(event.status));
                break;
            case StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT:
                sb.append("AUTHENTICATION_FAILURE_EVENT reason=").append(event.authFailureReason)
                        .append(":").append(authFailureReasonToString(event.authFailureReason));
                break;
            case StaEvent.TYPE_NETWORK_CONNECTION_EVENT:
                sb.append("NETWORK_CONNECTION_EVENT");
                break;
            case StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT:
                sb.append("NETWORK_DISCONNECTION_EVENT")
                        .append(" local_gen=").append(event.localGen)
                        .append(" reason=").append(event.reason).append(":")
                        .append(ISupplicantStaIfaceCallback.ReasonCode.toString(
                                (event.reason >= 0 ? event.reason : -1 * event.reason)));
                break;
            case StaEvent.TYPE_CMD_ASSOCIATED_BSSID:
                sb.append("CMD_ASSOCIATED_BSSID");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
                sb.append("CMD_IP_CONFIGURATION_SUCCESSFUL");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
                sb.append("CMD_IP_CONFIGURATION_LOST");
                break;
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
                sb.append("CMD_IP_REACHABILITY_LOST");
                break;
            case StaEvent.TYPE_CMD_TARGET_BSSID:
                sb.append("CMD_TARGET_BSSID");
                break;
            case StaEvent.TYPE_CMD_START_CONNECT:
                sb.append("CMD_START_CONNECT");
                break;
            case StaEvent.TYPE_CMD_START_ROAM:
                sb.append("CMD_START_ROAM");
                break;
            case StaEvent.TYPE_CONNECT_NETWORK:
                sb.append("CONNECT_NETWORK");
                break;
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
                sb.append("NETWORK_AGENT_VALID_NETWORK");
                break;
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
                sb.append("FRAMEWORK_DISCONNECT")
                        .append(" reason=")
                        .append(frameworkDisconnectReasonToString(event.frameworkDisconnectReason));
                break;
            case StaEvent.TYPE_SCORE_BREACH:
                sb.append("SCORE_BREACH");
                break;
            case StaEvent.TYPE_MAC_CHANGE:
                sb.append("MAC_CHANGE");
                break;
            case StaEvent.TYPE_WIFI_ENABLED:
                sb.append("WIFI_ENABLED");
                break;
            case StaEvent.TYPE_WIFI_DISABLED:
                sb.append("WIFI_DISABLED");
                break;
            case StaEvent.TYPE_WIFI_USABILITY_SCORE_BREACH:
                sb.append("WIFI_USABILITY_SCORE_BREACH");
                break;
            case StaEvent.TYPE_LINK_PROBE:
                sb.append("LINK_PROBE");
                sb.append(" linkProbeWasSuccess=").append(event.linkProbeWasSuccess);
                if (event.linkProbeWasSuccess) {
                    sb.append(" linkProbeSuccessElapsedTimeMs=")
                            .append(event.linkProbeSuccessElapsedTimeMs);
                } else {
                    sb.append(" linkProbeFailureReason=").append(event.linkProbeFailureReason);
                }
                break;
            default:
                sb.append("UNKNOWN " + event.type + ":");
                break;
        }
        if (event.lastRssi != -127) sb.append(" lastRssi=").append(event.lastRssi);
        if (event.lastFreq != -1) sb.append(" lastFreq=").append(event.lastFreq);
        if (event.lastLinkSpeed != -1) sb.append(" lastLinkSpeed=").append(event.lastLinkSpeed);
        if (event.lastScore != -1) sb.append(" lastScore=").append(event.lastScore);
        if (event.lastWifiUsabilityScore != -1) {
            sb.append(" lastWifiUsabilityScore=").append(event.lastWifiUsabilityScore);
            sb.append(" lastPredictionHorizonSec=").append(event.lastPredictionHorizonSec);
        }
        if (event.mobileTxBytes > 0) sb.append(" mobileTxBytes=").append(event.mobileTxBytes);
        if (event.mobileRxBytes > 0) sb.append(" mobileRxBytes=").append(event.mobileRxBytes);
        if (event.totalTxBytes > 0) sb.append(" totalTxBytes=").append(event.totalTxBytes);
        if (event.totalRxBytes > 0) sb.append(" totalRxBytes=").append(event.totalRxBytes);
        sb.append(" screenOn=").append(event.screenOn);
        sb.append(" cellularData=").append(event.isCellularDataAvailable);
        sb.append(" adaptiveConnectivity=").append(event.isAdaptiveConnectivityEnabled);
        if (event.supplicantStateChangesBitmask != 0) {
            sb.append(", ").append(supplicantStateChangesBitmaskToString(
                    event.supplicantStateChangesBitmask));
        }
        if (event.configInfo != null) {
            sb.append(", ").append(configInfoToString(event.configInfo));
        }

        return sb.toString();
    }

    private static String authFailureReasonToString(int authFailureReason) {
        switch (authFailureReason) {
            case StaEvent.AUTH_FAILURE_NONE:
                return "ERROR_AUTH_FAILURE_NONE";
            case StaEvent.AUTH_FAILURE_TIMEOUT:
                return "ERROR_AUTH_FAILURE_TIMEOUT";
            case StaEvent.AUTH_FAILURE_WRONG_PSWD:
                return "ERROR_AUTH_FAILURE_WRONG_PSWD";
            case StaEvent.AUTH_FAILURE_EAP_FAILURE:
                return "ERROR_AUTH_FAILURE_EAP_FAILURE";
            default:
                return "";
        }
    }

    private static String frameworkDisconnectReasonToString(int frameworkDisconnectReason) {
        switch (frameworkDisconnectReason) {
            case StaEvent.DISCONNECT_API:
                return "DISCONNECT_API";
            case StaEvent.DISCONNECT_GENERIC:
                return "DISCONNECT_GENERIC";
            case StaEvent.DISCONNECT_UNWANTED:
                return "DISCONNECT_UNWANTED";
            case StaEvent.DISCONNECT_ROAM_WATCHDOG_TIMER:
                return "DISCONNECT_ROAM_WATCHDOG_TIMER";
            case StaEvent.DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST:
                return "DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST";
            case StaEvent.DISCONNECT_RESET_SIM_NETWORKS:
                return "DISCONNECT_RESET_SIM_NETWORKS";
            default:
                return "DISCONNECT_UNKNOWN=" + frameworkDisconnectReason;
        }
    }

    private static String configInfoToString(ConfigInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("ConfigInfo:")
                .append(" allowed_key_management=").append(info.allowedKeyManagement)
                .append(" allowed_protocols=").append(info.allowedProtocols)
                .append(" allowed_auth_algorithms=").append(info.allowedAuthAlgorithms)
                .append(" allowed_pairwise_ciphers=").append(info.allowedPairwiseCiphers)
                .append(" allowed_group_ciphers=").append(info.allowedGroupCiphers)
                .append(" hidden_ssid=").append(info.hiddenSsid)
                .append(" is_passpoint=").append(info.isPasspoint)
                .append(" is_ephemeral=").append(info.isEphemeral)
                .append(" has_ever_connected=").append(info.hasEverConnected)
                .append(" scan_rssi=").append(info.scanRssi)
                .append(" scan_freq=").append(info.scanFreq);
        return sb.toString();
    }

    /**
     * Converts the first 31 bits of a BitSet to a little endian int
     */
    private static int bitSetToInt(BitSet bits) {
        int value = 0;
        int nBits = bits.length() < 31 ? bits.length() : 31;
        for (int i = 0; i < nBits; i++) {
            value += bits.get(i) ? (1 << i) : 0;
        }
        return value;
    }
    private void incrementSsid(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_CONNECTABLE_SSID_NETWORK_BUCKET));
    }
    private void incrementBssid(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_CONNECTABLE_BSSID_NETWORK_BUCKET));
    }
    private void incrementTotalScanResults(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_SCAN_RESULTS_BUCKET));
    }
    private void incrementTotalScanSsids(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_SCAN_RESULT_SSIDS_BUCKET));
    }
    private void incrementTotalPasspointAps(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_PASSPOINT_APS_BUCKET));
    }
    private void incrementTotalUniquePasspointEss(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_PASSPOINT_UNIQUE_ESS_BUCKET));
    }
    private void incrementPasspointPerUniqueEss(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_PASSPOINT_APS_PER_UNIQUE_ESS_BUCKET));
    }
    private void increment80211mcAps(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_80211MC_APS_BUCKET));
    }
    private void increment(SparseIntArray sia, int element) {
        int count = sia.get(element);
        sia.put(element, count + 1);
    }

    private static class StaEventWithTime {
        public StaEvent staEvent;
        public long wallClockMillis;

        StaEventWithTime(StaEvent event, long wallClockMillis) {
            staEvent = event;
            this.wallClockMillis = wallClockMillis;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(wallClockMillis);
            if (wallClockMillis != 0) {
                sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            } else {
                sb.append("                  ");
            }
            sb.append(" ").append(staEventToString(staEvent));
            return sb.toString();
        }
    }

    private LinkedList<WifiIsUnusableWithTime> mWifiIsUnusableList =
            new LinkedList<WifiIsUnusableWithTime>();
    private long mTxScucessDelta = 0;
    private long mTxRetriesDelta = 0;
    private long mTxBadDelta = 0;
    private long mRxSuccessDelta = 0;
    private long mLlStatsUpdateTimeDelta = 0;
    private long mLlStatsLastUpdateTime = 0;
    private int mLastScoreNoReset = -1;
    private long mLastDataStallTime = Long.MIN_VALUE;

    private static class WifiIsUnusableWithTime {
        public WifiIsUnusableEvent event;
        public long wallClockMillis;

        WifiIsUnusableWithTime(WifiIsUnusableEvent event, long wallClockMillis) {
            this.event = event;
            this.wallClockMillis = wallClockMillis;
        }

        public String toString() {
            if (event == null) return "<NULL>";
            StringBuilder sb = new StringBuilder();
            if (wallClockMillis != 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(wallClockMillis);
                sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            } else {
                sb.append("                  ");
            }
            sb.append(" ");

            switch(event.type) {
                case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
                    sb.append("DATA_STALL_BAD_TX");
                    break;
                case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
                    sb.append("DATA_STALL_TX_WITHOUT_RX");
                    break;
                case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                    sb.append("DATA_STALL_BOTH");
                    break;
                case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                    sb.append("FIRMWARE_ALERT");
                    break;
                case WifiIsUnusableEvent.TYPE_IP_REACHABILITY_LOST:
                    sb.append("IP_REACHABILITY_LOST");
                    break;
                default:
                    sb.append("UNKNOWN " + event.type);
                    break;
            }

            sb.append(" lastScore=").append(event.lastScore);
            sb.append(" txSuccessDelta=").append(event.txSuccessDelta);
            sb.append(" txRetriesDelta=").append(event.txRetriesDelta);
            sb.append(" txBadDelta=").append(event.txBadDelta);
            sb.append(" rxSuccessDelta=").append(event.rxSuccessDelta);
            sb.append(" packetUpdateTimeDelta=").append(event.packetUpdateTimeDelta)
                    .append("ms");
            if (event.firmwareAlertCode != -1) {
                sb.append(" firmwareAlertCode=").append(event.firmwareAlertCode);
            }
            sb.append(" lastWifiUsabilityScore=").append(event.lastWifiUsabilityScore);
            sb.append(" lastPredictionHorizonSec=").append(event.lastPredictionHorizonSec);
            sb.append(" screenOn=").append(event.screenOn);
            sb.append(" mobileTxBytes=").append(event.mobileTxBytes);
            sb.append(" mobileRxBytes=").append(event.mobileRxBytes);
            sb.append(" totalTxBytes=").append(event.totalTxBytes);
            sb.append(" totalRxBytes=").append(event.totalRxBytes);
            return sb.toString();
        }
    }

    /**
     * Converts MeteredOverride enum to UserActionEvent type.
     * @param value
     */
    public static int convertMeteredOverrideEnumToUserActionEventType(@MeteredOverride int value) {
        int result = UserActionEvent.EVENT_UNKNOWN;
        switch(value) {
            case WifiConfiguration.METERED_OVERRIDE_NONE:
                result = UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_AUTO;
                break;
            case WifiConfiguration.METERED_OVERRIDE_METERED:
                result = UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_METERED;
                break;
            case WifiConfiguration.METERED_OVERRIDE_NOT_METERED:
                result = UserActionEvent.EVENT_CONFIGURE_METERED_STATUS_UNMETERED;
                break;
        }
        return result;
    }

    /**
     * Converts Adaptive Connectivity state to UserActionEvent type.
     * @param value
     */
    public static int convertAdaptiveConnectivityStateToUserActionEventType(boolean value) {
        return value ? UserActionEvent.EVENT_CONFIGURE_ADAPTIVE_CONNECTIVITY_ON
                : UserActionEvent.EVENT_CONFIGURE_ADAPTIVE_CONNECTIVITY_OFF;
    }

    static class MeteredNetworkStatsBuilder {
        // A map from network identifier to MeteredDetail
        Map<String, MeteredDetail> mNetworkMap = new ArrayMap<>();

        void put(WifiConfiguration config, boolean detectedAsMetered) {
            MeteredDetail meteredDetail = new MeteredDetail();
            boolean isMetered = detectedAsMetered;
            if (config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED) {
                isMetered = true;
            } else if (config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_NOT_METERED) {
                isMetered = false;
            }
            meteredDetail.isMetered = isMetered;
            meteredDetail.isMeteredOverrideSet = config.meteredOverride
                    != WifiConfiguration.METERED_OVERRIDE_NONE;
            meteredDetail.isFromSuggestion = config.fromWifiNetworkSuggestion;
            mNetworkMap.put(config.getKey(), meteredDetail);
        }

        void clear() {
            mNetworkMap.clear();
        }

        MeteredNetworkStats toProto(boolean isFromSuggestion) {
            MeteredNetworkStats result = new MeteredNetworkStats();
            for (MeteredDetail meteredDetail : mNetworkMap.values()) {
                if (meteredDetail.isFromSuggestion != isFromSuggestion) {
                    continue;
                }
                if (meteredDetail.isMetered) {
                    result.numMetered++;
                } else {
                    result.numUnmetered++;
                }
                if (meteredDetail.isMeteredOverrideSet) {
                    if (meteredDetail.isMetered) {
                        result.numOverrideMetered++;
                    } else {
                        result.numOverrideUnmetered++;
                    }
                }
            }
            return result;
        }

        static class MeteredDetail {
            public boolean isMetered;
            public boolean isMeteredOverrideSet;
            public boolean isFromSuggestion;
        }
    }

    /**
     * Add metered information of this network.
     * @param config WifiConfiguration representing the netework.
     * @param detectedAsMetered is the network detected as metered.
     */
    public void addMeteredStat(WifiConfiguration config, boolean detectedAsMetered) {
        synchronized (mLock) {
            if (config == null) {
                return;
            }
            mMeteredNetworkStatsBuilder.put(config, detectedAsMetered);
        }
    }
    /**
     * Logs a UserActionEvent without a target network.
     * @param eventType the type of user action (one of WifiMetricsProto.UserActionEvent.EventType)
     */
    public void logUserActionEvent(int eventType) {
        logUserActionEvent(eventType, -1);
    }

    /**
     * Logs a UserActionEvent which has a target network.
     * @param eventType the type of user action (one of WifiMetricsProto.UserActionEvent.EventType)
     * @param networkId networkId of the target network.
     */
    public void logUserActionEvent(int eventType, int networkId) {
        synchronized (mLock) {
            mUserActionEventList.add(new UserActionEventWithTime(eventType, networkId));
            if (mUserActionEventList.size() > MAX_USER_ACTION_EVENTS) {
                mUserActionEventList.remove();
            }
        }
    }

    /**
     * Logs a UserActionEvent, directly specifying the target network's properties.
     * @param eventType the type of user action (one of WifiMetricsProto.UserActionEvent.EventType)
     * @param isEphemeral true if the target network is ephemeral.
     * @param isPasspoint true if the target network is passpoint.
     */
    public void logUserActionEvent(int eventType, boolean isEphemeral, boolean isPasspoint) {
        synchronized (mLock) {
            TargetNetworkInfo networkInfo = new TargetNetworkInfo();
            networkInfo.isEphemeral = isEphemeral;
            networkInfo.isPasspoint = isPasspoint;
            mUserActionEventList.add(new UserActionEventWithTime(eventType, networkInfo));
            if (mUserActionEventList.size() > MAX_USER_ACTION_EVENTS) {
                mUserActionEventList.remove();
            }
        }
    }

    /**
     * Update the difference between the last two WifiLinkLayerStats for WifiIsUnusableEvent
     */
    public void updateWifiIsUnusableLinkLayerStats(long txSuccessDelta, long txRetriesDelta,
            long txBadDelta, long rxSuccessDelta, long updateTimeDelta) {
        mTxScucessDelta = txSuccessDelta;
        mTxRetriesDelta = txRetriesDelta;
        mTxBadDelta = txBadDelta;
        mRxSuccessDelta = rxSuccessDelta;
        mLlStatsUpdateTimeDelta = updateTimeDelta;
        mLlStatsLastUpdateTime = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Clear the saved difference between the last two WifiLinkLayerStats
     */
    public void resetWifiIsUnusableLinkLayerStats() {
        mTxScucessDelta = 0;
        mTxRetriesDelta = 0;
        mTxBadDelta = 0;
        mRxSuccessDelta = 0;
        mLlStatsUpdateTimeDelta = 0;
        mLlStatsLastUpdateTime = 0;
        mLastDataStallTime = Long.MIN_VALUE;
    }

    /**
     * Log a WifiIsUnusableEvent
     * @param triggerType WifiIsUnusableEvent.type describing the event
     */
    public void logWifiIsUnusableEvent(int triggerType) {
        logWifiIsUnusableEvent(triggerType, -1);
    }

    /**
     * Log a WifiIsUnusableEvent
     * @param triggerType WifiIsUnusableEvent.type describing the event
     * @param firmwareAlertCode WifiIsUnusableEvent.firmwareAlertCode for firmware alert code
     */
    public void logWifiIsUnusableEvent(int triggerType, int firmwareAlertCode) {
        mScoreBreachLowTimeMillis = -1;
        if (!mContext.getResources().getBoolean(R.bool.config_wifiIsUnusableEventMetricsEnabled)) {
            return;
        }

        long currentBootTime = mClock.getElapsedSinceBootMillis();
        switch (triggerType) {
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
            case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                // Have a time-based throttle for generating WifiIsUnusableEvent from data stalls
                if (currentBootTime < mLastDataStallTime + MIN_DATA_STALL_WAIT_MS) {
                    return;
                }
                mLastDataStallTime = currentBootTime;
                break;
            case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                break;
            case WifiIsUnusableEvent.TYPE_IP_REACHABILITY_LOST:
                break;
            default:
                Log.e(TAG, "Unknown WifiIsUnusableEvent: " + triggerType);
                return;
        }

        WifiIsUnusableEvent event = new WifiIsUnusableEvent();
        event.type = triggerType;
        if (triggerType == WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT) {
            event.firmwareAlertCode = firmwareAlertCode;
        }
        event.startTimeMillis = currentBootTime;
        event.lastScore = mLastScoreNoReset;
        event.lastWifiUsabilityScore = mLastWifiUsabilityScoreNoReset;
        event.lastPredictionHorizonSec = mLastPredictionHorizonSecNoReset;
        event.txSuccessDelta = mTxScucessDelta;
        event.txRetriesDelta = mTxRetriesDelta;
        event.txBadDelta = mTxBadDelta;
        event.rxSuccessDelta = mRxSuccessDelta;
        event.packetUpdateTimeDelta = mLlStatsUpdateTimeDelta;
        event.lastLinkLayerStatsUpdateTime = mLlStatsLastUpdateTime;
        event.screenOn = mScreenOn;
        event.mobileTxBytes = mFacade.getMobileTxBytes();
        event.mobileRxBytes = mFacade.getMobileRxBytes();
        event.totalTxBytes = mFacade.getTotalTxBytes();
        event.totalRxBytes = mFacade.getTotalRxBytes();

        mWifiIsUnusableList.add(new WifiIsUnusableWithTime(event, mClock.getWallClockMillis()));
        if (mWifiIsUnusableList.size() > MAX_UNUSABLE_EVENTS) {
            mWifiIsUnusableList.removeFirst();
        }
    }

    /**
     * Extract data from |info| and |stats| to build a WifiUsabilityStatsEntry and then adds it
     * into an internal ring buffer.
     * @param info
     * @param stats
     */
    public void updateWifiUsabilityStatsEntries(WifiInfo info, WifiLinkLayerStats stats) {
        synchronized (mLock) {
            if (info == null) {
                return;
            }
            if (stats == null) {
                // For devices lacking vendor hal, fill in the parts that we can
                stats = new WifiLinkLayerStats();
                stats.timeStampInMs = mClock.getElapsedSinceBootMillis();
                stats.txmpdu_be = info.txSuccess;
                stats.retries_be = info.txRetries;
                stats.lostmpdu_be = info.txBad;
                stats.rxmpdu_be = info.rxSuccess;
            }
            WifiUsabilityStatsEntry wifiUsabilityStatsEntry =
                    mWifiUsabilityStatsEntriesList.size()
                    < MAX_WIFI_USABILITY_STATS_ENTRIES_LIST_SIZE
                    ? new WifiUsabilityStatsEntry() : mWifiUsabilityStatsEntriesList.remove();
            wifiUsabilityStatsEntry.timeStampMs = stats.timeStampInMs;
            wifiUsabilityStatsEntry.totalTxSuccess = stats.txmpdu_be + stats.txmpdu_bk
                    + stats.txmpdu_vi + stats.txmpdu_vo;
            wifiUsabilityStatsEntry.totalTxRetries = stats.retries_be + stats.retries_bk
                    + stats.retries_vi + stats.retries_vo;
            wifiUsabilityStatsEntry.totalTxBad = stats.lostmpdu_be + stats.lostmpdu_bk
                    + stats.lostmpdu_vi + stats.lostmpdu_vo;
            wifiUsabilityStatsEntry.totalRxSuccess = stats.rxmpdu_be + stats.rxmpdu_bk
                    + stats.rxmpdu_vi + stats.rxmpdu_vo;
            wifiUsabilityStatsEntry.totalRadioOnTimeMs = stats.on_time;
            wifiUsabilityStatsEntry.totalRadioTxTimeMs = stats.tx_time;
            wifiUsabilityStatsEntry.totalRadioRxTimeMs = stats.rx_time;
            wifiUsabilityStatsEntry.totalScanTimeMs = stats.on_time_scan;
            wifiUsabilityStatsEntry.totalNanScanTimeMs = stats.on_time_nan_scan;
            wifiUsabilityStatsEntry.totalBackgroundScanTimeMs = stats.on_time_background_scan;
            wifiUsabilityStatsEntry.totalRoamScanTimeMs = stats.on_time_roam_scan;
            wifiUsabilityStatsEntry.totalPnoScanTimeMs = stats.on_time_pno_scan;
            wifiUsabilityStatsEntry.totalHotspot2ScanTimeMs = stats.on_time_hs20_scan;
            wifiUsabilityStatsEntry.rssi = info.getRssi();
            wifiUsabilityStatsEntry.linkSpeedMbps = info.getLinkSpeed();
            WifiLinkLayerStats.ChannelStats statsMap =
                    stats.channelStatsMap.get(info.getFrequency());
            if (statsMap != null) {
                wifiUsabilityStatsEntry.totalRadioOnFreqTimeMs = statsMap.radioOnTimeMs;
                wifiUsabilityStatsEntry.totalCcaBusyFreqTimeMs = statsMap.ccaBusyTimeMs;
            }
            wifiUsabilityStatsEntry.totalBeaconRx = stats.beacon_rx;

            boolean isSameBssidAndFreq = mLastBssid == null || mLastFrequency == -1
                    || (mLastBssid.equals(info.getBSSID())
                    && mLastFrequency == info.getFrequency());
            mLastBssid = info.getBSSID();
            mLastFrequency = info.getFrequency();
            wifiUsabilityStatsEntry.wifiScore = mLastScoreNoReset;
            wifiUsabilityStatsEntry.wifiUsabilityScore = mLastWifiUsabilityScoreNoReset;
            wifiUsabilityStatsEntry.seqNumToFramework = mSeqNumToFramework;
            wifiUsabilityStatsEntry.predictionHorizonSec = mLastPredictionHorizonSecNoReset;
            switch (mProbeStatusSinceLastUpdate) {
                case android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
                    break;
                case android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
                    break;
                case android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
                    break;
                default:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_UNKNOWN;
                    Log.e(TAG, "Unknown link probe status: " + mProbeStatusSinceLastUpdate);
            }
            wifiUsabilityStatsEntry.probeElapsedTimeSinceLastUpdateMs =
                    mProbeElapsedTimeSinceLastUpdateMs;
            wifiUsabilityStatsEntry.probeMcsRateSinceLastUpdate = mProbeMcsRateSinceLastUpdate;
            wifiUsabilityStatsEntry.rxLinkSpeedMbps = info.getRxLinkSpeedMbps();
            wifiUsabilityStatsEntry.isSameBssidAndFreq = isSameBssidAndFreq;
            wifiUsabilityStatsEntry.seqNumInsideFramework = mSeqNumInsideFramework;
            wifiUsabilityStatsEntry.deviceMobilityState = mCurrentDeviceMobilityState;

            mWifiUsabilityStatsEntriesList.add(wifiUsabilityStatsEntry);
            mWifiUsabilityStatsCounter++;
            if (mWifiUsabilityStatsCounter >= NUM_WIFI_USABILITY_STATS_ENTRIES_PER_WIFI_GOOD) {
                addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_GOOD,
                        WifiUsabilityStats.TYPE_UNKNOWN, -1);
            }
            if (mScoreBreachLowTimeMillis != -1) {
                long elapsedTime =  mClock.getElapsedSinceBootMillis() - mScoreBreachLowTimeMillis;
                if (elapsedTime >= MIN_SCORE_BREACH_TO_GOOD_STATS_WAIT_TIME_MS) {
                    mScoreBreachLowTimeMillis = -1;
                    if (elapsedTime <= VALIDITY_PERIOD_OF_SCORE_BREACH_LOW_MS) {
                        addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_GOOD,
                                WifiUsabilityStats.TYPE_UNKNOWN, -1);
                    }
                }
            }

            // Invoke Wifi usability stats listener.
            sendWifiUsabilityStats(mSeqNumInsideFramework, isSameBssidAndFreq,
                    createNewWifiUsabilityStatsEntryParcelable(wifiUsabilityStatsEntry));

            mSeqNumInsideFramework++;
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
            mProbeElapsedTimeSinceLastUpdateMs = -1;
            mProbeMcsRateSinceLastUpdate = -1;
        }
    }

    /**
     * Send Wifi usability stats.
     * @param seqNum
     * @param isSameBssidAndFreq
     * @param statsEntry
     */
    private void sendWifiUsabilityStats(int seqNum, boolean isSameBssidAndFreq,
            android.net.wifi.WifiUsabilityStatsEntry statsEntry) {
        for (IOnWifiUsabilityStatsListener listener : mOnWifiUsabilityListeners.getCallbacks()) {
            try {
                listener.onWifiUsabilityStats(seqNum, isSameBssidAndFreq, statsEntry);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke Wifi usability stats entry listener "
                        + listener, e);
            }
        }
    }

    private android.net.wifi.WifiUsabilityStatsEntry createNewWifiUsabilityStatsEntryParcelable(
            WifiUsabilityStatsEntry s) {
        int probeStatus;
        switch (s.probeStatusSinceLastUpdate) {
            case WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
                break;
            case WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
                break;
            case WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
                break;
            default:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_UNKNOWN;
                Log.e(TAG, "Unknown link probe status: " + s.probeStatusSinceLastUpdate);
        }
        // TODO: remove the following hardcoded values once if they are removed from public API
        return new android.net.wifi.WifiUsabilityStatsEntry(s.timeStampMs, s.rssi,
                s.linkSpeedMbps, s.totalTxSuccess, s.totalTxRetries,
                s.totalTxBad, s.totalRxSuccess, s.totalRadioOnTimeMs,
                s.totalRadioTxTimeMs, s.totalRadioRxTimeMs, s.totalScanTimeMs,
                s.totalNanScanTimeMs, s.totalBackgroundScanTimeMs, s.totalRoamScanTimeMs,
                s.totalPnoScanTimeMs, s.totalHotspot2ScanTimeMs, s.totalCcaBusyFreqTimeMs,
                s.totalRadioOnFreqTimeMs, s.totalBeaconRx, probeStatus,
                s.probeElapsedTimeSinceLastUpdateMs, s.probeMcsRateSinceLastUpdate,
                s.rxLinkSpeedMbps, 0, 0, 0, false
        );
    }

    private WifiUsabilityStatsEntry createNewWifiUsabilityStatsEntry(WifiUsabilityStatsEntry s) {
        WifiUsabilityStatsEntry out = new WifiUsabilityStatsEntry();
        out.timeStampMs = s.timeStampMs;
        out.totalTxSuccess = s.totalTxSuccess;
        out.totalTxRetries = s.totalTxRetries;
        out.totalTxBad = s.totalTxBad;
        out.totalRxSuccess = s.totalRxSuccess;
        out.totalRadioOnTimeMs = s.totalRadioOnTimeMs;
        out.totalRadioTxTimeMs = s.totalRadioTxTimeMs;
        out.totalRadioRxTimeMs = s.totalRadioRxTimeMs;
        out.totalScanTimeMs = s.totalScanTimeMs;
        out.totalNanScanTimeMs = s.totalNanScanTimeMs;
        out.totalBackgroundScanTimeMs = s.totalBackgroundScanTimeMs;
        out.totalRoamScanTimeMs = s.totalRoamScanTimeMs;
        out.totalPnoScanTimeMs = s.totalPnoScanTimeMs;
        out.totalHotspot2ScanTimeMs = s.totalHotspot2ScanTimeMs;
        out.rssi = s.rssi;
        out.linkSpeedMbps = s.linkSpeedMbps;
        out.totalCcaBusyFreqTimeMs = s.totalCcaBusyFreqTimeMs;
        out.totalRadioOnFreqTimeMs = s.totalRadioOnFreqTimeMs;
        out.totalBeaconRx = s.totalBeaconRx;
        out.wifiScore = s.wifiScore;
        out.wifiUsabilityScore = s.wifiUsabilityScore;
        out.seqNumToFramework = s.seqNumToFramework;
        out.predictionHorizonSec = s.predictionHorizonSec;
        out.probeStatusSinceLastUpdate = s.probeStatusSinceLastUpdate;
        out.probeElapsedTimeSinceLastUpdateMs = s.probeElapsedTimeSinceLastUpdateMs;
        out.probeMcsRateSinceLastUpdate = s.probeMcsRateSinceLastUpdate;
        out.rxLinkSpeedMbps = s.rxLinkSpeedMbps;
        out.isSameBssidAndFreq = s.isSameBssidAndFreq;
        out.seqNumInsideFramework = s.seqNumInsideFramework;
        out.deviceMobilityState = s.deviceMobilityState;
        return out;
    }

    private WifiUsabilityStats createWifiUsabilityStatsWithLabel(int label, int triggerType,
            int firmwareAlertCode) {
        WifiUsabilityStats wifiUsabilityStats = new WifiUsabilityStats();
        wifiUsabilityStats.label = label;
        wifiUsabilityStats.triggerType = triggerType;
        wifiUsabilityStats.firmwareAlertCode = firmwareAlertCode;
        wifiUsabilityStats.timeStampMs = mClock.getElapsedSinceBootMillis();
        wifiUsabilityStats.stats =
                new WifiUsabilityStatsEntry[mWifiUsabilityStatsEntriesList.size()];
        for (int i = 0; i < mWifiUsabilityStatsEntriesList.size(); i++) {
            wifiUsabilityStats.stats[i] =
                    createNewWifiUsabilityStatsEntry(mWifiUsabilityStatsEntriesList.get(i));
        }
        return wifiUsabilityStats;
    }

    /**
     * Label the current snapshot of WifiUsabilityStatsEntrys and save the labeled data in memory.
     * @param label WifiUsabilityStats.LABEL_GOOD or WifiUsabilityStats.LABEL_BAD
     * @param triggerType what event triggers WifiUsabilityStats
     * @param firmwareAlertCode the firmware alert code when the stats was triggered by a
     *        firmware alert
     */
    public void addToWifiUsabilityStatsList(int label, int triggerType, int firmwareAlertCode) {
        synchronized (mLock) {
            if (mWifiUsabilityStatsEntriesList.isEmpty() || !mScreenOn) {
                return;
            }
            if (label == WifiUsabilityStats.LABEL_GOOD) {
                // Only add a good event if at least |MIN_WIFI_GOOD_USABILITY_STATS_PERIOD_MS|
                // has passed.
                if (mWifiUsabilityStatsListGood.isEmpty()
                        || mWifiUsabilityStatsListGood.getLast().stats[mWifiUsabilityStatsListGood
                        .getLast().stats.length - 1].timeStampMs
                        + MIN_WIFI_GOOD_USABILITY_STATS_PERIOD_MS
                        < mWifiUsabilityStatsEntriesList.getLast().timeStampMs) {
                    while (mWifiUsabilityStatsListGood.size()
                            >= MAX_WIFI_USABILITY_STATS_LIST_SIZE_PER_TYPE) {
                        mWifiUsabilityStatsListGood.remove(
                                mRand.nextInt(mWifiUsabilityStatsListGood.size()));
                    }
                    mWifiUsabilityStatsListGood.add(
                            createWifiUsabilityStatsWithLabel(label, triggerType,
                                    firmwareAlertCode));
                }
            } else {
                // Only add a bad event if at least |MIN_DATA_STALL_WAIT_MS|
                // has passed.
                mScoreBreachLowTimeMillis = -1;
                if (mWifiUsabilityStatsListBad.isEmpty()
                        || (mWifiUsabilityStatsListBad.getLast().stats[mWifiUsabilityStatsListBad
                        .getLast().stats.length - 1].timeStampMs
                        + MIN_DATA_STALL_WAIT_MS
                        < mWifiUsabilityStatsEntriesList.getLast().timeStampMs)) {
                    while (mWifiUsabilityStatsListBad.size()
                            >= MAX_WIFI_USABILITY_STATS_LIST_SIZE_PER_TYPE) {
                        mWifiUsabilityStatsListBad.remove(
                                mRand.nextInt(mWifiUsabilityStatsListBad.size()));
                    }
                    mWifiUsabilityStatsListBad.add(
                            createWifiUsabilityStatsWithLabel(label, triggerType,
                                    firmwareAlertCode));
                }
            }
            mWifiUsabilityStatsCounter = 0;
            mWifiUsabilityStatsEntriesList.clear();
        }
    }

    private DeviceMobilityStatePnoScanStats getOrCreateDeviceMobilityStatePnoScanStats(
            @DeviceMobilityState int deviceMobilityState) {
        DeviceMobilityStatePnoScanStats stats = mMobilityStatePnoStatsMap.get(deviceMobilityState);
        if (stats == null) {
            stats = new DeviceMobilityStatePnoScanStats();
            stats.deviceMobilityState = deviceMobilityState;
            stats.numTimesEnteredState = 0;
            stats.totalDurationMs = 0;
            stats.pnoDurationMs = 0;
            mMobilityStatePnoStatsMap.put(deviceMobilityState, stats);
        }
        return stats;
    }

    /**
     * Updates the current device mobility state's total duration. This method should be called
     * before entering a new device mobility state.
     */
    private void updateCurrentMobilityStateTotalDuration(long now) {
        DeviceMobilityStatePnoScanStats stats =
                getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
        stats.totalDurationMs += now - mCurrentDeviceMobilityStateStartMs;
        mCurrentDeviceMobilityStateStartMs = now;
    }

    /**
     * Convert the IntCounter of passpoint profile types and counts to proto's
     * repeated IntKeyVal array.
     *
     * @param passpointProfileTypes passpoint profile types and counts.
     */
    private PasspointProfileTypeCount[] convertPasspointProfilesToProto(
                IntCounter passpointProfileTypes) {
        return passpointProfileTypes.toProto(PasspointProfileTypeCount.class, (key, count) -> {
            PasspointProfileTypeCount entry = new PasspointProfileTypeCount();
            entry.eapMethodType = key;
            entry.count = count;
            return entry;
        });
    }

    /**
     * Reports that the device entered a new mobility state.
     *
     * @param newState the new device mobility state.
     */
    public void enterDeviceMobilityState(@DeviceMobilityState int newState) {
        synchronized (mLock) {
            long now = mClock.getElapsedSinceBootMillis();
            updateCurrentMobilityStateTotalDuration(now);

            if (newState == mCurrentDeviceMobilityState) return;

            mCurrentDeviceMobilityState = newState;
            DeviceMobilityStatePnoScanStats stats =
                    getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
            stats.numTimesEnteredState++;
        }
    }

    /**
     * Logs the start of a PNO scan.
     */
    public void logPnoScanStart() {
        synchronized (mLock) {
            long now = mClock.getElapsedSinceBootMillis();
            mCurrentDeviceMobilityStatePnoScanStartMs = now;
            updateCurrentMobilityStateTotalDuration(now);
        }
    }

    /**
     * Logs the end of a PNO scan. This is attributed to the current device mobility state, as
     * logged by {@link #enterDeviceMobilityState(int)}. Thus, if the mobility state changes during
     * a PNO scan, one should call {@link #logPnoScanStop()}, {@link #enterDeviceMobilityState(int)}
     * , then {@link #logPnoScanStart()} so that the portion of PNO scan before the mobility state
     * change can be correctly attributed to the previous mobility state.
     */
    public void logPnoScanStop() {
        synchronized (mLock) {
            if (mCurrentDeviceMobilityStatePnoScanStartMs < 0) {
                Log.e(TAG, "Called WifiMetrics#logPNoScanStop() without calling "
                        + "WifiMetrics#logPnoScanStart() first!");
                return;
            }
            DeviceMobilityStatePnoScanStats stats =
                    getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
            long now = mClock.getElapsedSinceBootMillis();
            stats.pnoDurationMs += now - mCurrentDeviceMobilityStatePnoScanStartMs;
            mCurrentDeviceMobilityStatePnoScanStartMs = -1;
            updateCurrentMobilityStateTotalDuration(now);
        }
    }

    /**
     * Logs that wifi bug report is taken
     */
    public void logBugReport() {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                mCurrentConnectionEvent.mConnectionEvent.automaticBugReportTaken = true;
            }
        }
    }

    /**
     * Add a new listener for Wi-Fi usability stats handling.
     */
    public void addOnWifiUsabilityListener(IBinder binder, IOnWifiUsabilityStatsListener listener,
            int listenerIdentifier) {
        if (!mOnWifiUsabilityListeners.add(binder, listener, listenerIdentifier)) {
            Log.e(TAG, "Failed to add listener");
            return;
        }
        if (DBG) {
            Log.v(TAG, "Adding listener. Num listeners: "
                    + mOnWifiUsabilityListeners.getNumCallbacks());
        }
    }

    /**
     * Remove an existing listener for Wi-Fi usability stats handling.
     */
    public void removeOnWifiUsabilityListener(int listenerIdentifier) {
        mOnWifiUsabilityListeners.remove(listenerIdentifier);
        if (DBG) {
            Log.v(TAG, "Removing listener. Num listeners: "
                    + mOnWifiUsabilityListeners.getNumCallbacks());
        }
    }

    /**
     * Updates the Wi-Fi usability score and increments occurence of a particular Wifi usability
     * score passed in from outside framework. Scores are bounded within
     * [MIN_WIFI_USABILITY_SCORE, MAX_WIFI_USABILITY_SCORE].
     *
     * Also records events when the Wifi usability score breaches significant thresholds.
     *
     * @param seqNum Sequence number of the Wi-Fi usability score.
     * @param score The Wi-Fi usability score.
     * @param predictionHorizonSec Prediction horizon of the Wi-Fi usability score.
     */
    public void incrementWifiUsabilityScoreCount(int seqNum, int score, int predictionHorizonSec) {
        if (score < MIN_WIFI_USABILITY_SCORE || score > MAX_WIFI_USABILITY_SCORE) {
            return;
        }
        synchronized (mLock) {
            mSeqNumToFramework = seqNum;
            mLastWifiUsabilityScore = score;
            mLastWifiUsabilityScoreNoReset = score;
            mWifiUsabilityScoreCounts.put(score, mWifiUsabilityScoreCounts.get(score) + 1);
            mLastPredictionHorizonSec = predictionHorizonSec;
            mLastPredictionHorizonSecNoReset = predictionHorizonSec;

            boolean wifiWins = mWifiWinsUsabilityScore;
            if (score > LOW_WIFI_USABILITY_SCORE) {
                wifiWins = true;
            } else if (score < LOW_WIFI_USABILITY_SCORE) {
                wifiWins = false;
            }

            if (wifiWins != mWifiWinsUsabilityScore) {
                mWifiWinsUsabilityScore = wifiWins;
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_WIFI_USABILITY_SCORE_BREACH;
                addStaEvent(event);
                // Only record the first score breach by checking whether mScoreBreachLowTimeMillis
                // has been set to -1
                if (!wifiWins && mScoreBreachLowTimeMillis == -1) {
                    mScoreBreachLowTimeMillis = mClock.getElapsedSinceBootMillis();
                }
            }
        }
    }

    /**
     * Reports stats for a successful link probe.
     *
     * @param timeSinceLastTxSuccessMs At {@code startTimestampMs}, the number of milliseconds since
     *                                 the last Tx success (according to
     *                                 {@link WifiInfo#txSuccess}).
     * @param rssi The Rx RSSI at {@code startTimestampMs}.
     * @param linkSpeed The Tx link speed in Mbps at {@code startTimestampMs}.
     * @param elapsedTimeMs The number of milliseconds between when the command to transmit the
     *                      probe was sent to the driver and when the driver responded that the
     *                      probe was ACKed. Note: this number should be correlated with the number
     *                      of retries that the driver attempted before the probe was ACKed.
     */
    public void logLinkProbeSuccess(long timeSinceLastTxSuccessMs,
            int rssi, int linkSpeed, int elapsedTimeMs) {
        synchronized (mLock) {
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
            mProbeElapsedTimeSinceLastUpdateMs = elapsedTimeMs;

            mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram.increment(
                    (int) (timeSinceLastTxSuccessMs / 1000));
            mLinkProbeSuccessRssiCounts.increment(rssi);
            mLinkProbeSuccessLinkSpeedCounts.increment(linkSpeed);
            mLinkProbeSuccessElapsedTimeMsHistogram.increment(elapsedTimeMs);

            if (mLinkProbeStaEventCount < MAX_LINK_PROBE_STA_EVENTS) {
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_LINK_PROBE;
                event.linkProbeWasSuccess = true;
                event.linkProbeSuccessElapsedTimeMs = elapsedTimeMs;
                addStaEvent(event);
            }
            mLinkProbeStaEventCount++;
        }
    }

    /**
     * Reports stats for an unsuccessful link probe.
     *
     * @param timeSinceLastTxSuccessMs At {@code startTimestampMs}, the number of milliseconds since
     *                                 the last Tx success (according to
     *                                 {@link WifiInfo#txSuccess}).
     * @param rssi The Rx RSSI at {@code startTimestampMs}.
     * @param linkSpeed The Tx link speed in Mbps at {@code startTimestampMs}.
     * @param reason The error code for the failure. See
     * {@link WifiNl80211Manager.SendMgmtFrameError}.
     */
    public void logLinkProbeFailure(long timeSinceLastTxSuccessMs,
            int rssi, int linkSpeed, int reason) {
        synchronized (mLock) {
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
            mProbeElapsedTimeSinceLastUpdateMs = Integer.MAX_VALUE;

            mLinkProbeFailureSecondsSinceLastTxSuccessHistogram.increment(
                    (int) (timeSinceLastTxSuccessMs / 1000));
            mLinkProbeFailureRssiCounts.increment(rssi);
            mLinkProbeFailureLinkSpeedCounts.increment(linkSpeed);
            mLinkProbeFailureReasonCounts.increment(reason);

            if (mLinkProbeStaEventCount < MAX_LINK_PROBE_STA_EVENTS) {
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_LINK_PROBE;
                event.linkProbeWasSuccess = false;
                event.linkProbeFailureReason = linkProbeFailureReasonToProto(reason);
                addStaEvent(event);
            }
            mLinkProbeStaEventCount++;
        }
    }

    /**
     * Increments the number of probes triggered by the experiment `experimentId`.
     */
    public void incrementLinkProbeExperimentProbeCount(String experimentId) {
        synchronized (mLock) {
            mLinkProbeExperimentProbeCounts.increment(experimentId);
        }
    }

    /**
     * Update wifi config store read duration.
     *
     * @param timeMs Time it took to complete the operation, in milliseconds
     */
    public void noteWifiConfigStoreReadDuration(int timeMs) {
        synchronized (mLock) {
            MetricsUtils.addValueToLinearHistogram(timeMs, mWifiConfigStoreReadDurationHistogram,
                    WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS);
        }
    }

    /**
     * Update wifi config store write duration.
     *
     * @param timeMs Time it took to complete the operation, in milliseconds
     */
    public void noteWifiConfigStoreWriteDuration(int timeMs) {
        synchronized (mLock) {
            MetricsUtils.addValueToLinearHistogram(timeMs, mWifiConfigStoreWriteDurationHistogram,
                    WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS);
        }
    }

    /**
     * Logs the decision of a network selection algorithm when compared against another network
     * selection algorithm.
     *
     * @param experiment1Id ID of one experiment
     * @param experiment2Id ID of the other experiment
     * @param isSameDecision did the 2 experiments make the same decision?
     * @param numNetworkChoices the number of non-null network choices there were, where the null
     *                          choice is not selecting any network
     */
    public void logNetworkSelectionDecision(int experiment1Id, int experiment2Id,
            boolean isSameDecision, int numNetworkChoices) {
        if (numNetworkChoices < 0) {
            Log.e(TAG, "numNetworkChoices cannot be negative!");
            return;
        }
        if (experiment1Id == experiment2Id) {
            Log.e(TAG, "comparing the same experiment id: " + experiment1Id);
            return;
        }

        Pair<Integer, Integer> key = new Pair<>(experiment1Id, experiment2Id);
        synchronized (mLock) {
            NetworkSelectionExperimentResults results =
                    mNetworkSelectionExperimentPairNumChoicesCounts
                            .computeIfAbsent(key, k -> new NetworkSelectionExperimentResults());

            IntCounter counter = isSameDecision
                    ? results.sameSelectionNumChoicesCounter
                    : results.differentSelectionNumChoicesCounter;

            counter.increment(numNetworkChoices);
        }
    }

    /** Increment number of network request API usage stats */
    public void incrementNetworkRequestApiNumRequest() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numRequest++;
        }
    }

    /** Add to the network request API match size histogram */
    public void incrementNetworkRequestApiMatchSizeHistogram(int matchSize) {
        synchronized (mLock) {
            mWifiNetworkRequestApiMatchSizeHistogram.increment(matchSize);
        }
    }

    /** Increment number of connection success via network request API */
    public void incrementNetworkRequestApiNumConnectSuccess() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numConnectSuccess++;
        }
    }

    /** Increment number of requests that bypassed user approval via network request API */
    public void incrementNetworkRequestApiNumUserApprovalBypass() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numUserApprovalBypass++;
        }
    }

    /** Increment number of requests that user rejected via network request API */
    public void incrementNetworkRequestApiNumUserReject() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numUserReject++;
        }
    }

    /** Increment number of requests from unique apps via network request API */
    public void incrementNetworkRequestApiNumApps() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numApps++;
        }
    }

    /** Increment number of network suggestion API modification by app stats */
    public void incrementNetworkSuggestionApiNumModification() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.numModification++;
        }
    }

    /** Increment number of connection success via network suggestion API */
    public void incrementNetworkSuggestionApiNumConnectSuccess() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.numConnectSuccess++;
        }
    }

    /** Increment number of connection failure via network suggestion API */
    public void incrementNetworkSuggestionApiNumConnectFailure() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.numConnectFailure++;
        }
    }

    /** Increment number of user revoke suggestion permission. Including from settings or
     * disallowed from UI.
     */
    public void incrementNetworkSuggestionUserRevokePermission() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.userRevokeAppSuggestionPermission++;
        }
    }

    /** Clear and set the latest network suggestion API max list size histogram */
    public void noteNetworkSuggestionApiListSizeHistogram(List<Integer> listSizes) {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiListSizeHistogram.clear();
            for (Integer listSize : listSizes) {
                mWifiNetworkSuggestionApiListSizeHistogram.increment(listSize);
            }
        }
    }

    /** Increment number of app add suggestion with different privilege */
    public void incrementNetworkSuggestionApiUsageNumOfAppInType(int appType) {
        int typeCode;
        synchronized (mLock) {
            switch (appType) {
                case WifiNetworkSuggestionsManager.APP_TYPE_CARRIER_PRIVILEGED:
                    typeCode = WifiNetworkSuggestionApiLog.TYPE_CARRIER_PRIVILEGED;
                    break;
                case WifiNetworkSuggestionsManager.APP_TYPE_NETWORK_PROVISIONING:
                    typeCode = WifiNetworkSuggestionApiLog.TYPE_NETWORK_PROVISIONING;
                    break;
                case WifiNetworkSuggestionsManager.APP_TYPE_NON_PRIVILEGED:
                    typeCode = WifiNetworkSuggestionApiLog.TYPE_NON_PRIVILEGED;
                    break;
                default:
                    typeCode = WifiNetworkSuggestionApiLog.TYPE_UNKNOWN;
            }
            mWifiNetworkSuggestionApiAppTypeCounter.increment(typeCode);
        }
    }

    /** Add user action to the approval suggestion app UI */
    public void addUserApprovalSuggestionAppUiReaction(@WifiNetworkSuggestionsManager.UserActionCode
            int actionType, boolean isDialog) {
        int actionCode;
        switch (actionType) {
            case WifiNetworkSuggestionsManager.ACTION_USER_ALLOWED_APP:
                actionCode = UserReactionToApprovalUiEvent.ACTION_ALLOWED;
                break;
            case WifiNetworkSuggestionsManager.ACTION_USER_DISALLOWED_APP:
                actionCode = UserReactionToApprovalUiEvent.ACTION_DISALLOWED;
                break;
            case WifiNetworkSuggestionsManager.ACTION_USER_DISMISS:
                actionCode = UserReactionToApprovalUiEvent.ACTION_DISMISS;
                break;
            default:
                actionCode = UserReactionToApprovalUiEvent.ACTION_UNKNOWN;
        }
        UserReaction event = new UserReaction();
        event.userAction = actionCode;
        event.isDialog = isDialog;
        synchronized (mLock) {
            mUserApprovalSuggestionAppUiReactionList.add(event);
        }
    }

    /** Add user action to the approval Carrier Imsi protection exemption UI */
    public void addUserApprovalCarrierUiReaction(@WifiCarrierInfoManager.UserActionCode
            int actionType, boolean isDialog) {
        int actionCode;
        switch (actionType) {
            case WifiCarrierInfoManager.ACTION_USER_ALLOWED_CARRIER:
                actionCode = UserReactionToApprovalUiEvent.ACTION_ALLOWED;
                break;
            case WifiCarrierInfoManager.ACTION_USER_DISALLOWED_CARRIER:
                actionCode = UserReactionToApprovalUiEvent.ACTION_DISALLOWED;
                break;
            case WifiCarrierInfoManager.ACTION_USER_DISMISS:
                actionCode = UserReactionToApprovalUiEvent.ACTION_DISMISS;
                break;
            default:
                actionCode = UserReactionToApprovalUiEvent.ACTION_UNKNOWN;
        }
        UserReaction event = new UserReaction();
        event.userAction = actionCode;
        event.isDialog = isDialog;

        synchronized (mLock) {
            mUserApprovalCarrierUiReactionList.add(event);
        }
    }

    /**
     * Sets the nominator for a network (i.e. which entity made the suggestion to connect)
     * @param networkId the ID of the network, from its {@link WifiConfiguration}
     * @param nominatorId the entity that made the suggestion to connect to this network,
     *                    from {@link WifiMetricsProto.ConnectionEvent.ConnectionNominator}
     */
    public void setNominatorForNetwork(int networkId, int nominatorId) {
        synchronized (mLock) {
            if (networkId == WifiConfiguration.INVALID_NETWORK_ID) return;
            mNetworkIdToNominatorId.put(networkId, nominatorId);

            // user connect choice is preventing switcing off from the connected network
            if (nominatorId
                    == WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED_USER_CONNECT_CHOICE
                    && mWifiStatusBuilder.getNetworkId() == networkId) {
                mWifiStatusBuilder.setUserChoice(true);
            }
        }
    }

    /**
     * Sets the numeric CandidateScorer id.
     */
    public void setNetworkSelectorExperimentId(int expId) {
        synchronized (mLock) {
            mNetworkSelectorExperimentId = expId;
        }
    }

    /** Add a WifiLock acqusition session */
    public void addWifiLockAcqSession(int lockType, long duration) {
        switch (lockType) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                mWifiLockHighPerfAcqDurationSecHistogram.increment((int) (duration / 1000));
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                mWifiLockLowLatencyAcqDurationSecHistogram.increment((int) (duration / 1000));
                break;

            default:
                Log.e(TAG, "addWifiLockAcqSession: Invalid lock type: " + lockType);
                break;
        }
    }

    /** Add a WifiLock active session */
    public void addWifiLockActiveSession(int lockType, long duration) {
        switch (lockType) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                mWifiLockStats.highPerfActiveTimeMs += duration;
                mWifiLockHighPerfActiveSessionDurationSecHistogram.increment(
                        (int) (duration / 1000));
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                mWifiLockStats.lowLatencyActiveTimeMs += duration;
                mWifiLockLowLatencyActiveSessionDurationSecHistogram.increment(
                        (int) (duration / 1000));
                break;

            default:
                Log.e(TAG, "addWifiLockActiveSession: Invalid lock type: " + lockType);
                break;
        }
    }

    /** Increments metrics counting number of addOrUpdateNetwork calls. **/
    public void incrementNumAddOrUpdateNetworkCalls() {
        synchronized (mLock) {
            mWifiLogProto.numAddOrUpdateNetworkCalls++;
        }
    }

    /** Increments metrics counting number of enableNetwork calls. **/
    public void incrementNumEnableNetworkCalls() {
        synchronized (mLock) {
            mWifiLogProto.numEnableNetworkCalls++;
        }
    }

    /** Add to WifiToggleStats **/
    public void incrementNumWifiToggles(boolean isPrivileged, boolean enable) {
        synchronized (mLock) {
            if (isPrivileged && enable) {
                mWifiToggleStats.numToggleOnPrivileged++;
            } else if (isPrivileged && !enable) {
                mWifiToggleStats.numToggleOffPrivileged++;
            } else if (!isPrivileged && enable) {
                mWifiToggleStats.numToggleOnNormal++;
            } else {
                mWifiToggleStats.numToggleOffNormal++;
            }
        }
    }

    /**
     * Increment number of passpoint provision failure
     * @param failureCode indicates error condition
     */
    public void incrementPasspointProvisionFailure(int failureCode) {
        int provisionFailureCode;
        synchronized (mLock) {
            switch (failureCode) {
                case ProvisioningCallback.OSU_FAILURE_AP_CONNECTION:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_AP_CONNECTION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVER_URL_INVALID:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_SERVER_URL_INVALID;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_SERVER_CONNECTION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_SERVER_VALIDATION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_PROVISIONING_ABORTED;
                    break;
                case ProvisioningCallback.OSU_FAILURE_PROVISIONING_NOT_AVAILABLE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_PROVISIONING_NOT_AVAILABLE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU;
                    break;
                case ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_UNEXPECTED_COMMAND_TYPE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_SOAP_MESSAGE_EXCHANGE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_START_REDIRECT_LISTENER:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_START_REDIRECT_LISTENER;
                    break;
                case ProvisioningCallback.OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_OSU_ACTIVITY_FOUND:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_OSU_ACTIVITY_FOUND;
                    break;
                case ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_STATUS:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_STATUS;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_PPS_MO:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_NO_PPS_MO;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_AAA_SERVER_TRUST_ROOT_NODE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_AAA_SERVER_TRUST_ROOT_NODE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_REMEDIATION_SERVER_TRUST_ROOT_NODE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_REMEDIATION_SERVER_TRUST_ROOT_NODE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_POLICY_SERVER_TRUST_ROOT_NODE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_POLICY_SERVER_TRUST_ROOT_NODE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_AAA_TRUST_ROOT_CERTIFICATE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_AAA_TRUST_ROOT_CERTIFICATE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_OSU_PROVIDER_NOT_FOUND:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_OSU_PROVIDER_NOT_FOUND;
                    break;
                default:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_UNKNOWN;
            }
            mPasspointProvisionFailureCounts.increment(provisionFailureCode);
        }
    }

    /**
     * Add to the histogram of number of BSSIDs filtered out from network selection.
     */
    public void incrementNetworkSelectionFilteredBssidCount(int numBssid) {
        mBssidBlocklistStats.networkSelectionFilteredBssidCount.increment(numBssid);
    }

    /**
     * Increment the number of network connections skipped due to the high movement feature.
     */
    public void incrementNumHighMovementConnectionSkipped() {
        mBssidBlocklistStats.numHighMovementConnectionSkipped++;
    }

    /**
     * Increment the number of network connections initiated while under the high movement
     * feature.
     */
    public void incrementNumHighMovementConnectionStarted() {
        mBssidBlocklistStats.numHighMovementConnectionStarted++;
    }

    /**
     * Increment number of passpoint provision success
     */
    public void incrementPasspointProvisionSuccess() {
        synchronized (mLock) {
            mNumProvisionSuccess++;
        }
    }

    /**
     * Increment number of IP renewal failures.
     */
    public void incrementIpRenewalFailure() {
        synchronized (mLock) {
            mWifiLogProto.numIpRenewalFailure++;
        }
    }

    /**
     * Sets the duration for evaluating Wifi condition to trigger a data stall
     */
    public void setDataStallDurationMs(int duration) {
        synchronized (mLock) {
            mExperimentValues.dataStallDurationMs = duration;
        }
    }

    /**
     * Sets the threshold of Tx throughput below which to trigger a data stall
     */
    public void setDataStallTxTputThrKbps(int txTputThr) {
        synchronized (mLock) {
            mExperimentValues.dataStallTxTputThrKbps = txTputThr;
        }
    }

    /**
     * Sets the threshold of Rx throughput below which to trigger a data stall
     */
    public void setDataStallRxTputThrKbps(int rxTputThr) {
        synchronized (mLock) {
            mExperimentValues.dataStallRxTputThrKbps = rxTputThr;
        }
    }

    /**
     * Sets the threshold of Tx packet error rate above which to trigger a data stall
     */
    public void setDataStallTxPerThr(int txPerThr) {
        synchronized (mLock) {
            mExperimentValues.dataStallTxPerThr = txPerThr;
        }
    }

    /**
     * Sets the threshold of CCA level above which to trigger a data stall
     */
    public void setDataStallCcaLevelThr(int ccaLevel) {
        synchronized (mLock) {
            mExperimentValues.dataStallCcaLevelThr = ccaLevel;
        }
    }

    /**
     * Sets health monitor RSSI poll valid time in ms
     */
    public void setHealthMonitorRssiPollValidTimeMs(int rssiPollValidTimeMs) {
        synchronized (mLock) {
            mExperimentValues.healthMonitorRssiPollValidTimeMs = rssiPollValidTimeMs;
        }
    }

    /**
     * Increment connection duration while link layer stats report are on
     */
    public void incrementConnectionDuration(int timeDeltaLastTwoPollsMs,
            boolean isThroughputSufficient, boolean isCellularDataAvailable) {
        synchronized (mLock) {
            mConnectionDurationStats.incrementDurationCount(timeDeltaLastTwoPollsMs,
                    isThroughputSufficient, isCellularDataAvailable, mWifiWins);
        }
    }

    /**
     * Sets the status to indicate whether external WiFi connected network scorer is present or not.
     */
    public void setIsExternalWifiScorerOn(boolean value) {
        synchronized (mLock) {
            mWifiLogProto.isExternalWifiScorerOn = value;
        }
    }

    /**
     * Note Wi-Fi off metrics
     */
    public void noteWifiOff(boolean isDeferred, boolean isTimeout, int duration) {
        synchronized (mLock) {
            mWifiOffMetrics.numWifiOff++;
            if (isDeferred) {
                mWifiOffMetrics.numWifiOffDeferring++;
                if (isTimeout) {
                    mWifiOffMetrics.numWifiOffDeferringTimeout++;
                }
                mWifiOffMetrics.wifiOffDeferringTimeHistogram.increment(duration);
            }
        }
    }

    /**
     * Increment number of BSSIDs filtered out from network selection due to MBO Association
     * disallowed indication.
     */
    public void incrementNetworkSelectionFilteredBssidCountDueToMboAssocDisallowInd() {
        synchronized (mLock) {
            mWifiLogProto.numBssidFilteredDueToMboAssocDisallowInd++;
        }
    }

    /**
     * Increment number of times force scan is triggered due to a
     * BSS transition management request frame from AP.
     */
    public void incrementForceScanCountDueToSteeringRequest() {
        synchronized (mLock) {
            mWifiLogProto.numForceScanDueToSteeringRequest++;
        }
    }

    /**
     * Increment number of times STA received cellular switch
     * request from MBO supported AP.
     */
    public void incrementMboCellularSwitchRequestCount() {
        synchronized (mLock) {
            mWifiLogProto.numMboCellularSwitchRequest++;
        }
    }

    /**
     * Increment number of times STA received steering request
     * including MBO association retry delay.
     */
    public void incrementSteeringRequestCountIncludingMboAssocRetryDelay() {
        synchronized (mLock) {
            mWifiLogProto.numSteeringRequestIncludingMboAssocRetryDelay++;
        }
    }

    /**
     * Increment number of connect request to AP adding FILS AKM.
     */
    public void incrementConnectRequestWithFilsAkmCount() {
        synchronized (mLock) {
            mWifiLogProto.numConnectRequestWithFilsAkm++;
        }
    }

    /**
     * Increment number of times STA connected through FILS
     * authentication.
     */
    public void incrementL2ConnectionThroughFilsAuthCount() {
        synchronized (mLock) {
            mWifiLogProto.numL2ConnectionThroughFilsAuthentication++;
        }
    }

    /**
     * Note SoftapConfig Reset Metrics
     */
    public void noteSoftApConfigReset(SoftApConfiguration originalConfig,
            SoftApConfiguration newConfig) {
        synchronized (mLock) {
            if (originalConfig.getSecurityType() != newConfig.getSecurityType()) {
                mSoftApConfigLimitationMetrics.numSecurityTypeResetToDefault++;
            }
            if (originalConfig.getMaxNumberOfClients() != newConfig.getMaxNumberOfClients()) {
                mSoftApConfigLimitationMetrics.numMaxClientSettingResetToDefault++;
            }
            if (originalConfig.isClientControlByUserEnabled()
                    != newConfig.isClientControlByUserEnabled()) {
                mSoftApConfigLimitationMetrics.numClientControlByUserResetToDefault++;
            }
        }
    }

    /**
     * Note Softap client blocked due to max client limitation
     */
    public void noteSoftApClientBlocked(int maxClient) {
        mSoftApConfigLimitationMetrics.maxClientSettingWhenReachHistogram.increment(maxClient);
    }

    /**
     * Increment number of connection with different BSSID between framework and firmware selection.
     */
    public void incrementNumBssidDifferentSelectionBetweenFrameworkAndFirmware() {
        synchronized (mLock) {
            mWifiLogProto.numBssidDifferentSelectionBetweenFrameworkAndFirmware++;
        }
    }

    /**
     * Note the carrier wifi network connected successfully.
     */
    public void incrementNumOfCarrierWifiConnectionSuccess() {
        synchronized (mLock) {
            mCarrierWifiMetrics.numConnectionSuccess++;
        }
    }

    /**
     * Note the carrier wifi network connection authentication failure.
     */
    public void incrementNumOfCarrierWifiConnectionAuthFailure() {
        synchronized (mLock) {
            mCarrierWifiMetrics.numConnectionAuthFailure++;
        }
    }

    /**
     * Note the carrier wifi network connection non-authentication failure.
     */
    public void incrementNumOfCarrierWifiConnectionNonAuthFailure() {
        synchronized (mLock) {
            mCarrierWifiMetrics.numConnectionNonAuthFailure++;
        }
    }

    /**
     *  Set Adaptive Connectivity state (On/Off)
     */
    public void setAdaptiveConnectivityState(boolean adaptiveConnectivityEnabled) {
        synchronized (mLock) {
            mAdaptiveConnectivityEnabled = adaptiveConnectivityEnabled;
        }
    }

    /** Note whether Wifi was enabled at boot time. */
    public void noteWifiEnabledDuringBoot(boolean isWifiEnabled) {
        synchronized (mLock) {
            if (mIsFirstConnectionAttemptComplete
                    || mFirstConnectAfterBootStats == null
                    || mFirstConnectAfterBootStats.wifiEnabledAtBoot != null) {
                return;
            }
            Attempt wifiEnabledAtBoot = new Attempt();
            wifiEnabledAtBoot.isSuccess = isWifiEnabled;
            wifiEnabledAtBoot.timestampSinceBootMillis = mClock.getElapsedSinceBootMillis();
            mFirstConnectAfterBootStats.wifiEnabledAtBoot = wifiEnabledAtBoot;
            if (!isWifiEnabled) {
                mIsFirstConnectionAttemptComplete = true;
            }
        }
    }

    /** Note the first network selection after boot. */
    public void noteFirstNetworkSelectionAfterBoot(boolean wasAnyCandidatesFound) {
        synchronized (mLock) {
            if (mIsFirstConnectionAttemptComplete
                    || mFirstConnectAfterBootStats == null
                    || mFirstConnectAfterBootStats.firstNetworkSelection != null) {
                return;
            }
            Attempt firstNetworkSelection = new Attempt();
            firstNetworkSelection.isSuccess = wasAnyCandidatesFound;
            firstNetworkSelection.timestampSinceBootMillis = mClock.getElapsedSinceBootMillis();
            mFirstConnectAfterBootStats.firstNetworkSelection = firstNetworkSelection;
            if (!wasAnyCandidatesFound) {
                mIsFirstConnectionAttemptComplete = true;
            }
        }
    }

    /** Note the first L2 connection after boot. */
    public void noteFirstL2ConnectionAfterBoot(boolean wasConnectionSuccessful) {
        synchronized (mLock) {
            if (mIsFirstConnectionAttemptComplete
                    || mFirstConnectAfterBootStats == null
                    || mFirstConnectAfterBootStats.firstL2Connection != null) {
                return;
            }
            Attempt firstL2Connection = new Attempt();
            firstL2Connection.isSuccess = wasConnectionSuccessful;
            firstL2Connection.timestampSinceBootMillis = mClock.getElapsedSinceBootMillis();
            mFirstConnectAfterBootStats.firstL2Connection = firstL2Connection;
            if (!wasConnectionSuccessful) {
                mIsFirstConnectionAttemptComplete = true;
            }
        }
    }

    /** Note the first L3 connection after boot. */
    public void noteFirstL3ConnectionAfterBoot(boolean wasConnectionSuccessful) {
        synchronized (mLock) {
            if (mIsFirstConnectionAttemptComplete
                    || mFirstConnectAfterBootStats == null
                    || mFirstConnectAfterBootStats.firstL3Connection != null) {
                return;
            }
            Attempt firstL3Connection = new Attempt();
            firstL3Connection.isSuccess = wasConnectionSuccessful;
            firstL3Connection.timestampSinceBootMillis = mClock.getElapsedSinceBootMillis();
            mFirstConnectAfterBootStats.firstL3Connection = firstL3Connection;
            if (!wasConnectionSuccessful) {
                mIsFirstConnectionAttemptComplete = true;
            }
        }
    }

    private static String attemptToString(@Nullable Attempt attempt) {
        if (attempt == null) return "Attempt=null";
        return "Attempt{"
                + "timestampSinceBootMillis=" + attempt.timestampSinceBootMillis
                + ",isSuccess=" + attempt.isSuccess
                + "}";
    }

    private static String firstConnectAfterBootStatsToString(
            @Nullable FirstConnectAfterBootStats stats) {
        if (stats == null) return "FirstConnectAfterBootStats=null";
        return "FirstConnectAfterBootStats{"
                + "wifiEnabledAtBoot=" + attemptToString(stats.wifiEnabledAtBoot)
                + ",firstNetworkSelection" + attemptToString(stats.firstNetworkSelection)
                + ",firstL2Connection" + attemptToString(stats.firstL2Connection)
                + ",firstL3Connection" + attemptToString(stats.firstL3Connection)
                + "}";
    }
}
