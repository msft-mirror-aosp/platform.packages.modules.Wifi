/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_AP;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_AP_BRIDGE;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_NAN;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_P2P;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_STA;

import android.annotation.IntDef;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.NetworkInfo;
import android.net.wifi.WifiContext;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Message;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.WaitingState;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Displays dialogs asking the user to approve or reject interface priority decisions.
 */
public class InterfaceConflictManager {
    private static final String TAG = "InterfaceConflictManager";
    private boolean mVerboseLoggingEnabled = false;

    private final WifiContext mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final HalDeviceManager mHdm;
    private final WifiThreadRunner mThreadRunner;
    private final WifiDialogManager mWifiDialogManager;

    private boolean mUserApprovalNeeded = false;
    private Set<String> mUserApprovalExemptedPackages = new ArraySet<>();
    private boolean mUserApprovalNotRequireForDisconnectedP2p = false;
    private boolean mUserApprovalNeededOverride = false;
    private boolean mUserApprovalNeededOverrideValue = false;

    private Object mLock = new Object();
    private boolean mUserApprovalPending = false;
    private String mUserApprovalPendingTag = null;
    private boolean mUserJustApproved = false;
    private boolean mIsP2pConnected = false;

    private WaitingState mCurrentWaitingState;
    private State mCurrentTargetState;
    private WifiDialogManager.DialogHandle mCurrentDialogHandle;

    private static final String MESSAGE_BUNDLE_KEY_PENDING_USER = "pending_user_decision";

    public InterfaceConflictManager(WifiContext wifiContext, FrameworkFacade frameworkFacade,
            HalDeviceManager hdm, WifiThreadRunner threadRunner,
            WifiDialogManager wifiDialogManager) {
        mContext = wifiContext;
        mFrameworkFacade = frameworkFacade;
        mHdm = hdm;
        mThreadRunner = threadRunner;
        mWifiDialogManager = wifiDialogManager;

        // Monitor P2P connection for auto-approval
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                    NetworkInfo mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (mNetworkInfo.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
                        mIsP2pConnected = true;
                    } else {
                        mIsP2pConnected = false;
                    }
                }

            }
        }, intentFilter);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verboseEnabled) {
        mVerboseLoggingEnabled = verboseEnabled;
    }

    /**
     * Returns an indication as to whether user approval is needed for this specific request. User
     * approval is controlled by:
     * - A global overlay `config_wifiUserApprovalRequiredForD2dInterfacePriority`
     * - An exemption list overlay `config_wifiExcludedFromUserApprovalForD2dInterfacePriority`
     *   which is a list of packages which are *exempted* from user approval
     * - A shell command which can be used to override
     *
     * @param requestorWs The WorkSource of the requestor - used to determine whether it is exempted
     *                    from user approval. All requesting packages must be exempted for the
     *                    dialog to NOT be displayed.
     */
    private boolean isUserApprovalNeeded(WorkSource requestorWs) {
        if (mUserApprovalNeededOverride) return mUserApprovalNeededOverrideValue;
        if (!mUserApprovalNeeded || mUserApprovalExemptedPackages.isEmpty()) {
            return mUserApprovalNeeded;
        }

        for (int i = 0; i < requestorWs.size(); ++i) {
            if (!mUserApprovalExemptedPackages.contains(requestorWs.getPackageName(i))) {
                return true;
            }
        }

        return false; // all packages of the requestor are excluded
    }

    /**
     * Override (potentially) the user approval needed device configuration. Intended for debugging
     * via the shell command.
     *
     * @param override      Enable overriding the default.
     * @param overrideValue The actual override value (i.e. disable or enable).
     */
    public void setUserApprovalNeededOverride(boolean override, boolean overrideValue) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "setUserApprovalNeededOverride: override=" + override + ", overrideValue="
                    + overrideValue);
        }
        mUserApprovalNeededOverride = override;
        mUserApprovalNeededOverrideValue = overrideValue;
    }

    /**
     * Return values for {@link #manageInterfaceConflictForStateMachine}
     */

    // Caller should continue and execute command: no need for user approval, or user approval
    // already granted, or command bound to fail so just fail through the normal path
    public static final int ICM_EXECUTE_COMMAND = 0;

    // Caller should skip executing the command for now (do not defer it - already done!). The user
    // was asked for permission and the command will be executed again when we get a response.
    public static final int ICM_SKIP_COMMAND_WAIT_FOR_USER = 1;

    // Caller should abort the command and execute whatever failure code is necessary - this
    // command was rejected by the user or we cannot ask the user since there's a pending user
    // request.
    public static final int ICM_ABORT_COMMAND = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ICM_"}, value = {
            ICM_EXECUTE_COMMAND,
            ICM_SKIP_COMMAND_WAIT_FOR_USER,
            ICM_ABORT_COMMAND
    })
    @interface IcmResult {}

    /**
     * Manages interface conflicts for a State Machine based caller. Possible scenarios:
     * - New request:
     *     - ok to proceed inline (i.e. caller can just proceed normally - no conflict)
     *       [nop]
     *     - need to request user approval (there's conflict, caller need to wait for user response)
     *       [msg get tagged + deferred, transition to waiting state]
     * - Previously executed command (i.e. already asked the user)
     *     - user rejected request
     *       [discard request, execute any necessary error callbacks]
     *     - user approved request
     *       [~nop (i.e. proceed)]
     * - Busy asking approval for another request:
     *     - If from another caller: reject
     *     - If from the same caller: defer the caller (possibly will be approved when gets to ask
     *       again).
     *
     * Synchronization:
     * - Multiple threads accessing this method will be blocked until the processing of the other
     *   thread is done. The "processing" is simply the decision making - i.e. not the waiting for
     *   user response.
     * - If a user response is pending then subsequent requests are auto-rejected if they require
     *   user approval. Note that this will result in race condition if this approval changes
     *   the conditions for the user approval request: e.g. it may increase the impact of a user
     *   approval (w/o telling the user) or it may be rejected even if approved by the user (if
     *   the newly allocated interface now has higher priority).
     *
     * @param tag Tag of the caller for logging
     * @param msg The command which needs to be evaluated or executed for user approval
     * @param stateMachine The source state machine
     * @param waitingState The {@link WaitingState} added to the above state machine
     * @param targetState The target state to transition to on user response
     * @param createIfaceType The interface which needs to be created
     * @param requestorWs The requestor WorkSource
     *
     * @param bypassDialog
     * @return ICM_EXECUTE_COMMAND caller should execute the command,
     * ICM_SKIP_COMMAND_WAIT_FOR_USER caller should skip the command (for now),
     * ICM_ABORT_COMMAND caller should abort this command and execute whatever failure code is
     * necessary.
     */
    public @IcmResult int manageInterfaceConflictForStateMachine(String tag, Message msg,
            StateMachine stateMachine, WaitingState waitingState, State targetState,
            @HalDeviceManager.HdmIfaceTypeForCreation int createIfaceType, WorkSource requestorWs,
            boolean bypassDialog) {
        synchronized (mLock) {
            if (mUserApprovalPending && !TextUtils.equals(tag, mUserApprovalPendingTag)) {
                Log.w(TAG, tag + ": rejected since there's a pending user approval for "
                        + mUserApprovalPendingTag);
                return ICM_ABORT_COMMAND; // caller should not proceed with operation
            }

            // is this a command which was waiting for a user decision?
            boolean isReexecutedCommand = msg.getData().getBoolean(
                    MESSAGE_BUNDLE_KEY_PENDING_USER, false);
            // is this a command that was issued while we were already waiting for a user decision?
            boolean wasInWaitingState = WaitingState.wasMessageInWaitingState(msg);
            if (isReexecutedCommand || (wasInWaitingState && !mUserJustApproved)) {
                mUserApprovalPending = false;
                mUserApprovalPendingTag = null;

                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, tag + ": Executing a command with user approval result: "
                            + mUserJustApproved + ", isReexecutedCommand: " + isReexecutedCommand
                            + ", wasInWaitingState: " + wasInWaitingState);
                }
                return mUserJustApproved ? ICM_EXECUTE_COMMAND : ICM_ABORT_COMMAND;
            }

            if (mUserApprovalPending) {
                Log.w(TAG, tag
                        + ": trying for another potentially waiting operation - but should be"
                        + " in a waiting state!?");
                stateMachine.deferMessage(msg);
                return ICM_SKIP_COMMAND_WAIT_FOR_USER; // same effect
            }

            if (bypassDialog || !isUserApprovalNeeded(requestorWs)) return ICM_EXECUTE_COMMAND;

            List<Pair<Integer, WorkSource>> impact = mHdm.reportImpactToCreateIface(createIfaceType,
                    false, requestorWs);
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, tag + ": Asking user about creating the interface, impact=" + impact);
            }
            if (impact == null || impact.isEmpty()) {
                Log.d(TAG, tag
                        + ": Either can't create interface or can w/o sid-effects - proceeding");
                return ICM_EXECUTE_COMMAND;
            }

            if (mUserApprovalNotRequireForDisconnectedP2p && !mIsP2pConnected
                    && impact.size() == 1 && impact.get(0).first == HDM_CREATE_IFACE_P2P) {
                Log.d(TAG, tag
                        + ": existing inferface is p2p and it is not connected - proceeding");
                return ICM_EXECUTE_COMMAND;
            }

            boolean shouldShowDialogToDelete = false;
            for (Pair<Integer, WorkSource> ifaceToDelete : impact) {
                if (mHdm.needsUserApprovalToDelete(createIfaceType, requestorWs,
                        ifaceToDelete.first, ifaceToDelete.second)) {
                    shouldShowDialogToDelete = true;
                    break;
                }
            }
            // None of the interfaces to delete require us to show a dialog.
            if (!shouldShowDialogToDelete) {
                return ICM_EXECUTE_COMMAND;
            }

            // defer message to have it executed again automatically when switching
            // states - want to do it now so that it will be at the top of the queue
            // when we switch back. Will need to skip it if the user rejected it!
            msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_PENDING_USER, true);
            stateMachine.deferMessage(msg);
            stateMachine.transitionTo(waitingState);

            mUserApprovalPending = true;
            mUserApprovalPendingTag = tag;
            mCurrentWaitingState = waitingState;
            mCurrentTargetState = targetState;
            mUserJustApproved = false;
            mCurrentDialogHandle = createUserApprovalDialog(createIfaceType, requestorWs, impact,
                    (result) -> {
                        if (mVerboseLoggingEnabled) {
                            Log.d(TAG, tag + ": User response to creating " + getInterfaceName(
                                    createIfaceType) + ": " + result);
                        }
                        mUserJustApproved = result;
                        mCurrentWaitingState = null;
                        mCurrentTargetState = null;
                        mCurrentDialogHandle = null;
                        waitingState.sendTransitionStateCommand(targetState);
                    });
            mCurrentDialogHandle.launchDialog();

            return ICM_SKIP_COMMAND_WAIT_FOR_USER;
        }
    }

    /**
     * Trigger a dialog which requests user approval to resolve an interface priority confict.
     *
     * @param createIfaceType The interface to be created.
     * @param requestorWs The WorkSource of the requesting application.
     * @param impact The impact of creating this interface (a list of interfaces to be deleted and
     *               their corresponding impacted WorkSources).
     * @param handleResult A Consumer to execute with results.
     */
    private WifiDialogManager.DialogHandle createUserApprovalDialog(
            @HalDeviceManager.HdmIfaceTypeForCreation int createIfaceType,
            WorkSource requestorWs,
            List<Pair<Integer, WorkSource>> impact,
            Consumer<Boolean> handleResult) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "displayUserApprovalDialog: createIfaceType=" + createIfaceType
                    + ", requestorWs=" + requestorWs + ", impact=" + impact);
        }

        CharSequence requestorAppName = mFrameworkFacade.getAppName(mContext,
                requestorWs.getPackageName(0), requestorWs.getUid(0));
        String requestedInterface = getInterfaceName(createIfaceType);
        Set<String> impactedInterfacesSet = new HashSet<>();
        Set<String> impactedPackagesSet = new HashSet<>();
        for (Pair<Integer, WorkSource> detail : impact) {
            impactedInterfacesSet.add(getInterfaceName(detail.first));
            for (int j = 0; j < detail.second.size(); ++j) {
                impactedPackagesSet.add(
                        mFrameworkFacade.getAppName(mContext, detail.second.getPackageName(j),
                                detail.second.getUid(j)).toString());
            }
        }
        String impactedPackages = TextUtils.join(", ", impactedPackagesSet);
        String impactedInterfaces = TextUtils.join(", ", impactedInterfacesSet);

        Resources res = mContext.getResources();
        return mWifiDialogManager.createSimpleDialog(
                res.getString(R.string.wifi_interface_priority_title,
                        requestorAppName, requestedInterface, impactedPackages, impactedInterfaces),
                impactedPackagesSet.size() == 1 ? res.getString(
                        R.string.wifi_interface_priority_message, requestorAppName,
                        requestedInterface, impactedPackages, impactedInterfaces)
                        : res.getString(R.string.wifi_interface_priority_message_plural,
                                requestorAppName, requestedInterface, impactedPackages,
                                impactedInterfaces),
                res.getString(R.string.wifi_interface_priority_approve),
                res.getString(R.string.wifi_interface_priority_reject),
                null,
                new WifiDialogManager.SimpleDialogCallback() {
                    @Override
                    public void onPositiveButtonClicked() {
                        if (mVerboseLoggingEnabled) {
                            Log.d(TAG, "User approved request for " + getInterfaceName(
                                    createIfaceType));
                        }
                        handleResult.accept(true);
                    }

                    @Override
                    public void onNegativeButtonClicked() {
                        if (mVerboseLoggingEnabled) {
                            Log.d(TAG, "User rejected request for " + getInterfaceName(
                                    createIfaceType));
                        }
                        handleResult.accept(false);
                    }

                    @Override
                    public void onNeutralButtonClicked() {
                        onNegativeButtonClicked();
                    }

                    @Override
                    public void onCancelled() {
                        onNegativeButtonClicked();
                    }
                }, mThreadRunner);
    }

    private String getInterfaceName(@HalDeviceManager.HdmIfaceTypeForCreation int createIfaceType) {
        Resources res = mContext.getResources();
        switch (createIfaceType) {
            case HDM_CREATE_IFACE_STA:
                return res.getString(R.string.wifi_interface_priority_interface_name_sta);
            case HDM_CREATE_IFACE_AP:
                return res.getString(R.string.wifi_interface_priority_interface_name_ap);
            case HDM_CREATE_IFACE_AP_BRIDGE:
                return res.getString(
                        R.string.wifi_interface_priority_interface_name_ap_bridge);
            case HDM_CREATE_IFACE_P2P:
                return res.getString(R.string.wifi_interface_priority_interface_name_p2p);
            case HDM_CREATE_IFACE_NAN:
                return res.getString(R.string.wifi_interface_priority_interface_name_nan);
        }
        return "Unknown";
    }

    /**
     * Reset the current state of InterfaceConflictManager, dismiss any open dialogs, and transition
     * any waiting StateMachines back to their target state.
     */
    public void reset() {
        synchronized (mLock) {
            if (mCurrentWaitingState != null && mCurrentTargetState != null) {
                mCurrentWaitingState.sendTransitionStateCommand(mCurrentTargetState);
            }
            mCurrentWaitingState = null;
            mCurrentTargetState = null;
            if (mCurrentDialogHandle != null) {
                mCurrentDialogHandle.dismissDialog();
            }
            mUserApprovalPending = false;
            mUserApprovalPendingTag = null;
            mUserJustApproved = false;
        }
    }

    /**
     * Initialization after boot completes to get boot-dependent resources.
     */
    public void handleBootCompleted() {
        Resources res = mContext.getResources();
        mUserApprovalNeeded = res.getBoolean(
                R.bool.config_wifiUserApprovalRequiredForD2dInterfacePriority);
        String[] packageList = res.getStringArray(
                R.array.config_wifiExcludedFromUserApprovalForD2dInterfacePriority);
        mUserApprovalExemptedPackages =
                (packageList == null || packageList.length == 0) ? Collections.emptySet()
                        : new ArraySet<>(packageList);
        mUserApprovalNotRequireForDisconnectedP2p = res.getBoolean(
                R.bool.config_wifiUserApprovalNotRequireForDisconnectedP2p);
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("dump of " + TAG + ":");
        pw.println("  mUserApprovalNeeded=" + mUserApprovalNeeded);
        pw.println("  mUserApprovalNeededOverride=" + mUserApprovalNeededOverride);
        pw.println("  mUserApprovalNeededOverrideValue=" + mUserApprovalNeededOverrideValue);
        pw.println("  mUserApprovalPending=" + mUserApprovalPending);
        pw.println("  mUserApprovalPendingTag=" + mUserApprovalPendingTag);
        pw.println("  mUserJustApproved=" + mUserJustApproved);
        pw.println("  mUserApprovalNotRequireForDisconnectedP2p="
                + mUserApprovalNotRequireForDisconnectedP2p);
    }
}
