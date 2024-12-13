#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

# Lint as: python3

from collections.abc import Sequence
import dataclasses
import datetime
import logging

from mobly import asserts
from mobly.controllers.android_device_lib import callback_handler_v2
from mobly.controllers import android_device

from direct import constants

_DEFAULT_TIMEOUT = datetime.timedelta(seconds=30)


@dataclasses.dataclass(frozen=True)
class DeviceState:
    """All objects related to operating p2p snippet RPCs.

    Attributes:
        ad: The Android device controller object.
        p2p_device: The object that represents a Wi-Fi p2p device.
    """

    ad: android_device.AndroidDevice
    p2p_device: constants.WifiP2pDevice
    broadcast_receiver: callback_handler_v2.CallbackHandlerV2


def setup_wifi_p2p(ad: android_device.AndroidDevice) -> DeviceState:
    """Sets up Wi-Fi p2p for automation tests on an Android device."""
    broadcast_receiver = _init_wifi_p2p(ad)
    _delete_all_persistent_groups(ad)
    p2p_device = _get_p2p_device(ad)
    asserts.assert_not_equal(
        p2p_device.device_address,
        constants.ANONYMIZED_MAC_ADDRESS,
        f'{ad} failed to get p2p device MAC address, please check permissions '
        'required by API WifiP2pManager#requestConnectionInfo',
    )
    return DeviceState(
        ad=ad, p2p_device=p2p_device, broadcast_receiver=broadcast_receiver
    )


def _init_wifi_p2p(
    ad: android_device.AndroidDevice,
) -> callback_handler_v2.CallbackHandlerV2:
    """Registers the snippet app with the Wi-Fi p2p framework.

    This must be the first to be called before any p2p operations are performed.

    Args:
        ad: The Android device controller object.

    Returns:
        The broadcast receiver from which you can get snippet events
        corresponding to Wi-Fi p2p intents received on device.
    """
    broadcast_receiver = ad.wifi.wifiP2pInitialize()

    def _is_p2p_enabled(event):
        return (
            event.data[constants.EXTRA_WIFI_STATE]
            == constants.ExtraWifiState.WIFI_P2P_STATE_ENABLED
        )

    # Wait until receiving the "p2p enabled" event. We might receive a
    # "p2p disabled" event before that.
    broadcast_receiver.waitForEvent(
        event_name=constants.WIFI_P2P_STATE_CHANGED_ACTION,
        predicate=_is_p2p_enabled,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )
    return broadcast_receiver


def _capture_p2p_intents(
    ad: android_device.AndroidDevice,
) -> callback_handler_v2.CallbackHandlerV2:
    """Starts capturing Wi-Fi p2p intents and returns the intent receiver."""
    broadcast_receiver = ad.wifi.wifiP2pCaptureP2pIntents()
    return broadcast_receiver


def _delete_all_persistent_groups(
    ad: android_device.AndroidDevice,
) -> None:
    """Deletes all persistent Wi-Fi p2p groups."""
    groups = _request_persistent_group_info(ad)
    ad.log.debug('Wi-Fi p2p persistent groups before delete: %s', groups)
    for group in groups:
        result_data = ad.wifi.wifiP2pDeletePersistentGroup(group.network_id)
        result = result_data[constants.EVENT_KEY_CALLBACK_NAME]
        if result != constants.ACTION_LISTENER_ON_SUCCESS:
            reason = constants.ActionListenerOnFailure(
                result_data[constants.EVENT_KEY_REASON]
            )
            raise RuntimeError(
                'Failed to delete persistent group with network id '
                f'{group.network_id}. Reason: {reason.name}'
            )
    groups = _request_persistent_group_info(ad)
    ad.log.debug('Wi-Fi p2p persistent groups after delete: %s', groups)


def _request_persistent_group_info(
    ad: android_device.AndroidDevice,
) -> Sequence[constants.WifiP2pGroup]:
    """Requests persistent group information."""
    callback_handler = ad.wifi.wifiP2pRequestPersistentGroupInfo()
    event = callback_handler.waitAndGet(
        event_name=constants.ON_PERSISTENT_GROUP_INFO_AVAILABLE,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )
    groups = constants.WifiP2pGroup.from_dict_list(event.data['groupList'])
    return groups


def _get_p2p_device(
    ad: android_device.AndroidDevice,
) -> constants.WifiP2pDevice:
    """Gets the Wi-Fi p2p device information."""
    callback_handler = ad.wifi.wifiP2pRequestDeviceInfo()
    event = callback_handler.waitAndGet(
        event_name=constants.ON_DEVICE_INFO_AVAILABLE,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )
    return constants.WifiP2pDevice.from_dict(
        event.data[constants.EVENT_KEY_P2P_DEVICE]
    )


def find_p2p_device(
    requester: DeviceState,
    responder: DeviceState,
) -> constants.WifiP2pDevice:
    """Initiates Wi-Fi p2p discovery for the requester to find the responder.

    This initiates Wi-Fi p2p discovery on both devices and checks that the
    requester can discover responder and return peer p2p device.
    """
    requester.ad.log.debug('Discovering Wi-Fi P2P peers.')
    responder.ad.wifi.wifiP2pDiscoverPeers()

    _clear_events(requester, constants.WIFI_P2P_PEERS_CHANGED_ACTION)
    requester.ad.wifi.wifiP2pDiscoverPeers()

    event = requester.broadcast_receiver.waitAndGet(
        event_name=constants.WIFI_P2P_PEERS_CHANGED_ACTION,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )
    requester_peers = constants.WifiP2pDevice.from_dict_list(
        event.data[constants.EVENT_KEY_PEER_LIST]
    )

    responder_mac = responder.p2p_device.device_address
    filtered_peers = [
        peer for peer in requester_peers if peer.device_address == responder_mac
    ]
    if len(filtered_peers) == 0:
        asserts.fail(
            f'{requester.ad} did not find the responder device. Responder MAC '
            f'address: {responder_mac}, found peers: {requester_peers}.'
        )
    if len(filtered_peers) > 1:
        asserts.fail(
            f'{requester.ad} found more than one responder device. Responder '
            f'MAC address: {responder_mac}, found peers: {requester_peers}.'
        )
    return filtered_peers[0]


def p2p_connect(
    requester: DeviceState,
    responder: DeviceState,
    wps_config: constants.WpsInfo
) -> None:
    """Establishes Wi-Fi p2p connection with WPS configuration.

    This method instructs the requester to initiate a connection request and the
    responder to accept the connection. It then verifies the connection status
    on both devices.

    Args:
        requester: The requester device.
        responder: The responder device.
        wps_config: The WPS method to establish the connection.
    """
    logging.info('Establishing a p2p connection through WPS %s.', wps_config)

    # Clear events in broadcast receiver.
    _clear_events(requester, constants.WIFI_P2P_PEERS_CHANGED_ACTION)
    _clear_events(requester, constants.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    _clear_events(responder, constants.WIFI_P2P_PEERS_CHANGED_ACTION)
    _clear_events(responder, constants.WIFI_P2P_CONNECTION_CHANGED_ACTION)

    config = constants.WifiP2pConfig(
        device_address=responder.p2p_device.device_address,
        wps_setup=wps_config,
    )
    requester.ad.wifi.wifiP2pConnect(config.to_dict())
    requester.ad.log.info('Sent P2P connect invitation to responder.')
    if wps_config == constants.WpsInfo.PBC:
        responder.ad.wifi.wifiP2pAcceptInvitation(requester.p2p_device.device_name)
    elif wps_config == constants.WpsInfo.DISPLAY:
        pin = requester.ad.wifi.wifiP2pGetPinCode(responder.p2p_device.device_name)
        requester.ad.log.info('p2p connection PIN code: %s', pin)
        responder.ad.wifi.wifiP2pEnterPin(pin,requester.p2p_device.device_name)
    else:
        asserts.fail(f'Unsupported WPS configuration: {wps_config}')
    responder.ad.log.info('Accepted connect invitation.')

    # Check p2p status on requester.
    _wait_connection_notice(requester.broadcast_receiver)
    _wait_peer_connected(
        requester.broadcast_receiver,
        responder.p2p_device.device_address,
    )
    requester.ad.log.debug(
        'Connected with device %s through wifi p2p.',
        responder.p2p_device.device_address,
    )

    # Check p2p status on responder.
    _wait_connection_notice(responder.broadcast_receiver)
    _wait_peer_connected(
        responder.broadcast_receiver,
        requester.p2p_device.device_address,
    )
    responder.ad.log.debug(
        'Connected with device %s through wifi p2p.',
        requester.p2p_device.device_address,
    )

    logging.info('Established wifi p2p connection.')


def _wait_peer_connected(
    broadcast_receiver: callback_handler_v2.CallbackHandlerV2, peer_address: str
):
    """Waits for event that indicates expected Wi-Fi p2p peer is connected."""

    def _is_peer_connected(event):
        devices = constants.WifiP2pDevice.from_dict_list(event.data['peerList'])
        for device in devices:
            if (
                device.device_address == peer_address
                and device.status == constants.WifiP2pDeviceStatus.CONNECTED
            ):
                return True
        return False

    broadcast_receiver.waitForEvent(
        event_name=constants.WIFI_P2P_PEERS_CHANGED_ACTION,
        predicate=_is_peer_connected,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )


def _wait_connection_notice(
    broadcast_receiver: callback_handler_v2.CallbackHandlerV2,
):
    """Waits for event that indicates a p2p connection is established."""

    def _is_group_formed(event):
        try:
            p2p_info = constants.WifiP2pInfo.from_dict(
                event.data[constants.EVENT_KEY_P2P_INFO]
            )
            return p2p_info.group_formed
        except KeyError:
            return False

    event = broadcast_receiver.waitForEvent(
        event_name=constants.WIFI_P2P_CONNECTION_CHANGED_ACTION,
        predicate=_is_group_formed,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )


def remove_group_and_verify_disconnected(
    requester: DeviceState,
    responder: DeviceState,
):
    """Stops p2p connection and verifies disconnection status on devices."""
    logging.info('Stopping wifi p2p connection.')

    # Clear events in broadcast receiver.
    _clear_events(requester, constants.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    _clear_events(requester, constants.ON_DEVICE_INFO_AVAILABLE)
    _clear_events(responder, constants.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    _clear_events(responder, constants.ON_DEVICE_INFO_AVAILABLE)

    # Requester initiates p2p group removal.
    requester.ad.wifi.wifiP2pRemoveGroup()

    # Check p2p status on requester.
    _wait_disconnection_notice(requester.broadcast_receiver)
    _wait_peer_disconnected(
        requester.broadcast_receiver, responder.p2p_device.device_address
    )
    requester.ad.log.debug(
        'Disconnected with device %s through wifi p2p.',
        responder.p2p_device.device_address,
    )

    # Check p2p status on responder.
    _wait_disconnection_notice(responder.broadcast_receiver)
    _wait_peer_disconnected(
        responder.broadcast_receiver, requester.p2p_device.device_address
    )
    responder.ad.log.debug(
        'Disconnected with device %s through wifi p2p.',
        requester.p2p_device.device_address,
    )

    logging.info('Stopped wifi p2p connection.')


def _wait_disconnection_notice(broadcast_receiver):
    """Waits for event that indicates the p2p connection is disconnected."""

    def _is_disconnect_event(event):
        info = constants.WifiP2pInfo.from_dict(
            event.data[constants.EVENT_KEY_P2P_INFO]
        )
        return not info.group_formed

    broadcast_receiver.waitForEvent(
        event_name=constants.WIFI_P2P_CONNECTION_CHANGED_ACTION,
        predicate=_is_disconnect_event,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )


def _wait_peer_disconnected(broadcast_receiver, target_address):
    """Waits for event that indicates current Wi-Fi p2p peer is disconnected."""

    def _is_peer_disconnect_event(event):
        devices = constants.WifiP2pDevice.from_dict_list(
            event.data[constants.EVENT_KEY_PEER_LIST]
        )
        for device in devices:
            if device.device_address == target_address:
                return device.status != constants.WifiP2pDeviceStatus.CONNECTED
        # Target device not found also means it is disconnected.
        return True

    broadcast_receiver.waitForEvent(
        event_name=constants.WIFI_P2P_PEERS_CHANGED_ACTION,
        predicate=_is_peer_disconnect_event,
        timeout=_DEFAULT_TIMEOUT.total_seconds(),
    )


def _clear_events(device: DeviceState, event_name):
    """Clears the events with the given name in the broadcast receiver."""
    all_events = device.broadcast_receiver.getAll(event_name)
    device.ad.log.debug(
        'Cleared %d events of event name %s', len(all_events), event_name
    )


def teardown_wifi_p2p(ad: android_device.AndroidDevice):
    """Destroys all resources initialized in `_setup_wifi_p2p`."""
    try:
        ad.wifi.wifiP2pStopPeerDiscovery()
        ad.wifi.wifiP2pCancelConnect()
        ad.wifi.wifiP2pRemoveGroup()
    finally:
        # Make sure to call `p2pClose`, otherwise `_setup_wifi_p2p` won't be
        # able to run again.
        ad.wifi.p2pClose()