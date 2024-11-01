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

import static android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.net.wifi.IBooleanListener;
import android.net.wifi.flags.Flags;
import android.net.wifi.util.Environment;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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

    private static final SparseArray<IAvailabilityCallback> sAvailabilityCallbackMap =
            new SparseArray<>();

    /** @hide */
    public UsdManager(@NonNull Context context, @NonNull IUsdManager service) {
        mContext = context;
        mService = service;
    }

    /** @hide */
    public void sendMessage(int peerId, @NonNull byte[] message, @NonNull Executor executor,
            @NonNull Consumer<Boolean> resultCallback) {
        try {
            mService.sendMessage(peerId, message, new IBooleanListener.Stub() {
                @Override
                public void onResult(boolean value) throws RemoteException {
                    Binder.clearCallingIdentity();
                    executor.execute(() -> resultCallback.accept(value));
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void cancelSubscribe(int sessionId) {
        try {
            mService.cancelSubscribe(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether subscriber is supported or not.
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public boolean isSubscriberSupported() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        try {
            return mService.isSubscriberSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether publish is supported or not.
     * <p>
     * The publisher support is controlled by an overlay config_wifiUsdPublisherSupported.
     * By default, the feature will be disabled because the publisher operation impacts other
     * concurrency operation such as Station. The USD publisher switches channels and dwells a
     * longer time (500 milliseconds to 1 second) on non-home channel which disrupts other
     * concurrency operation.
     *
     * @return true if publisher feature is supported, otherwise false.
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public boolean isPublisherSupported() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        try {
            return mService.isPublisherSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the subscriber feature is currently available or not. Due to concurrent operations
     * such as Station, SoftAP, Wi-Fi Aware, Wi-Fi Direct ..etc. the subscriber functionality
     * may not be available.
     *
     * @return true if subscriber feature is available, otherwise false.
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public boolean isSubscriberAvailable() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        try {
            return mService.isSubscriberAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Checks if the publisher feature is currently available or not. Due to concurrent
     * operations such as Station, SoftAP, Wi-Fi Aware, Wi-Fi Direct ..etc.  the publisher
     * functionality may not be available.
     *
     * @return true if publisher feature is available, otherwise false.
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public boolean isPublisherAvailable() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        try {
            return mService.isPublisherAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class AvailabilityCallbackProxy extends IAvailabilityCallback.Stub {
        private final Executor mExecutor;
        private final AvailabilityCallback mAvailabilityCallback;

        private AvailabilityCallbackProxy(Executor executor,
                AvailabilityCallback availabilityCallback) {
            mExecutor = executor;
            mAvailabilityCallback = availabilityCallback;
        }

        @Override
        public void onSubscriberAvailable() {
            Log.d(TAG, "onSubscriberAvailable");
            Binder.clearCallingIdentity();
            mExecutor.execute(mAvailabilityCallback::onSubscriberAvailable);
        }

        @Override
        public void onPublisherAvailable() {
            Log.d(TAG, "onPublisherAvailable");
            Binder.clearCallingIdentity();
            mExecutor.execute(mAvailabilityCallback::onPublisherAvailable);
        }

    }

    /**
     * Interface for indicating publisher or subscriber availability.
     */
    public interface AvailabilityCallback {
        /**
         * Callback to notify subscriber functionality is available.
         */
        default void onSubscriberAvailable() {
        }

        /**
         * Callback to notify publisher functionality is available.
         */
        default void onPublisherAvailable() {
        }
    }

    /**
     * Register for publisher or subscriber availability. Concurrent operations such as Station,
     * SoftAP, Wi-Fi Aware, Wi-Fi Direct ..etc. impact the current availability of publisher or
     * subscriber functionality.
     *
     * @param executor The Executor on whose thread to execute the callbacks of the {@code
     *                 callback} object
     * @param callback Callback for USD roles availability
     * @throws NullPointerException if executor or callback is null
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public void registerAvailabilityCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AvailabilityCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        final int callbackHash = System.identityHashCode(callback);
        synchronized (sAvailabilityCallbackMap) {
            try {
                IAvailabilityCallback.Stub availabilityCallbackProxy =
                        new AvailabilityCallbackProxy(executor, callback);
                sAvailabilityCallbackMap.put(callbackHash, availabilityCallbackProxy);
                mService.registerAvailabilityCallback(availabilityCallbackProxy);
            } catch (RemoteException e) {
                sAvailabilityCallbackMap.remove(callbackHash);
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregister the callback previously registered with
     * {@link #registerAvailabilityCallback(Executor, AvailabilityCallback)}.
     *
     * @param callback a registered callback
     * @throws NullPointerException if callback is null
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public void unregisterAvailabilityCallback(@NonNull AvailabilityCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        final int callbackHash = System.identityHashCode(callback);
        synchronized (sAvailabilityCallbackMap) {
            try {
                if (!sAvailabilityCallbackMap.contains(callbackHash)) {
                    Log.w(TAG, "Unknown callback");
                    return;
                }
                mService.unregisterAvailabilityCallback(sAvailabilityCallbackMap.get(callbackHash));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                sAvailabilityCallbackMap.remove(callbackHash);
            }
        }
    }

    /**
     * Gets the characteristics of USD: a set of parameters which specify limitations on
     * configurations, e.g. maximum service name length.
     *
     * @return An object specifying the configuration limitation of USD. Return {@code null} if
     * USD feature is not supported.
     */
    @RequiresPermission(MANAGE_WIFI_NETWORK_SELECTION)
    public @Nullable Characteristics getCharacteristics() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException();
        }
        try {
            return mService.getCharacteristics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
