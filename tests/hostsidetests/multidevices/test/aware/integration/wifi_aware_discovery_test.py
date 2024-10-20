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
"""Wi-Fi Aware Discovery test reimplemented in Mobly."""
import enum
import logging
import random
import string
import sys
import time
from typing import Any, Dict, Union

from aware import aware_lib_utils as autils
from aware import constants
from mobly import asserts
from mobly import base_test
from mobly import records
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import callback_handler_v2

RUNTIME_PERMISSIONS = (
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.NEARBY_WIFI_DEVICES',
)
PACKAGE_NAME = constants.WIFI_AWARE_SNIPPET_PACKAGE_NAME
_DEFAULT_TIMEOUT = constants.WAIT_WIFI_STATE_TIME_OUT.total_seconds()
_MSG_ID_SUB_TO_PUB = random.randint(1000, 5000)
_MSG_ID_PUB_TO_SUB = random.randint(5001, 9999)
_MSG_SUB_TO_PUB = "Let's talk [Random Identifier: %s]" % utils.rand_ascii_str(5)
_MSG_PUB_TO_SUB = 'Ready [Random Identifier: %s]' % utils.rand_ascii_str(5)
_CALLBACK_NAME = constants.DiscoverySessionCallbackParamsType.CALLBACK_NAME
_IS_SESSION_INIT = constants.DiscoverySessionCallbackParamsType.IS_SESSION_INIT

# Publish & Subscribe Config keys.
_PAYLOAD_SIZE_MIN = 0
_PAYLOAD_SIZE_TYPICAL = 1
_PAYLOAD_SIZE_MAX = 2
_PUBLISH_TYPE_UNSOLICITED = 0
_PUBLISH_TYPE_SOLICITED = 1
_SUBSCRIBE_TYPE_PASSIVE = 0
_SUBSCRIBE_TYPE_ACTIVE = 1


@enum.unique
class AttachCallBackMethodType(enum.StrEnum):
    """Represents Attach Callback Method Type in Wi-Fi Aware.

    https://developer.android.com/reference/android/net/wifi/aware/AttachCallback
    """
    ATTACHED = 'onAttached'
    ATTACH_FAILED = 'onAttachFailed'
    AWARE_SESSION_TERMINATED = 'onAwareSessionTerminated'


class WifiAwareDiscoveryTest(base_test.BaseTestClass):
    """Wi-Fi Aware test class."""

    ads: list[android_device.AndroidDevice]
    publisher: android_device.AndroidDevice
    subscriber: android_device.AndroidDevice

    def setup_class(self):
        # Register two Android devices.
        self.ads = self.register_controller(android_device, min_number=2)
        self.publisher = self.ads[0]
        self.subscriber = self.ads[1]

        def setup_device(device: android_device.AndroidDevice):
            device.load_snippet(
                'wifi_aware_snippet', PACKAGE_NAME
            )
            for permission in RUNTIME_PERMISSIONS:
                device.adb.shell(['pm', 'grant', PACKAGE_NAME, permission])
            asserts.abort_all_if(
                not device.wifi_aware_snippet.wifiAwareIsAvailable(),
                f'{device} Wi-Fi Aware is not available.',
            )

        # Set up devices in parallel.
        utils.concurrent_exec(
            setup_device,
            ((self.publisher,), (self.subscriber,)),
            max_workers=2,
            raise_on_exception=True,
        )

    def setup_test(self):
        for ad in self.ads:
            autils.control_wifi(ad, True)
            aware_avail = ad.wifi_aware_snippet.wifiAwareIsAvailable()
            if not aware_avail:
                ad.log.info('Aware not available. Waiting ...')
                state_handler = ad.wifi_aware_snippet.wifiAwareMonitorStateChange()
                state_handler.waitAndGet(
                    constants.WifiAwareBroadcast.WIFI_AWARE_AVAILABLE)

    def teardown_test(self):
        utils.concurrent_exec(
            self._teardown_test_on_device,
            ((self.publisher,), (self.subscriber,)),
            max_workers=2,
            raise_on_exception=True,
        )
        utils.concurrent_exec(
            lambda d: d.services.create_output_excerpts_all(
                self.current_test_info),
            param_list=[[ad] for ad in self.ads],
            raise_on_exception=True,
        )

    def _teardown_test_on_device(self, ad: android_device.AndroidDevice) -> None:
        ad.wifi_aware_snippet.wifiAwareCloseAllWifiAwareSession()
        autils.reset_device_parameters(ad)
        autils.validate_forbidden_callbacks(ad)
        autils.reset_device_statistics(ad)

    def on_fail(self, record: records.TestResult) -> None:
        android_device.take_bug_reports(self.ads,
                                        destination =
                                        self.current_test_info.output_path)

    def _start_attach(self, ad: android_device.AndroidDevice) -> str:
        """Starts the attach process on the provided device."""
        handler = ad.wifi_aware_snippet.wifiAwareAttach()
        attach_event = handler.waitAndGet(
            event_name=AttachCallBackMethodType.ATTACHED,
            timeout=_DEFAULT_TIMEOUT,
        )
        asserts.assert_true(
            ad.wifi_aware_snippet.wifiAwareIsSessionAttached(),
            f'{ad} attach succeeded, but Wi-Fi Aware session is still null.'
        )
        ad.log.info('Attach Wi-Fi Aware session succeeded.')
        return attach_event.callback_id

    def _send_msg_and_check_received(
        self,
        *,
        sender: android_device.AndroidDevice,
        sender_aware_session_cb_handler: callback_handler_v2.CallbackHandlerV2,
        receiver: android_device.AndroidDevice,
        receiver_aware_session_cb_handler: callback_handler_v2.CallbackHandlerV2,
        discovery_session: str,
        peer: int,
        send_message: str,
        send_message_id: int,
    ) -> int:
        sender.wifi_aware_snippet.wifiAwareSendMessage(
            discovery_session, peer, send_message_id, send_message
        )
        message_send_result = sender_aware_session_cb_handler.waitAndGet(
            event_name =
            constants.DiscoverySessionCallbackMethodType.MESSAGE_SEND_RESULT,
            timeout =_DEFAULT_TIMEOUT,
        )
        callback_name = message_send_result.data[
            constants.DiscoverySessionCallbackParamsType.CALLBACK_NAME
        ]
        asserts.assert_equal(
            callback_name,
            constants.DiscoverySessionCallbackMethodType.MESSAGE_SEND_SUCCEEDED,
            f'{sender} failed to send message with an unexpected callback.',
        )
        actual_send_message_id = message_send_result.data[
            constants.DiscoverySessionCallbackParamsType.MESSAGE_ID
        ]
        asserts.assert_equal(
            actual_send_message_id,
            send_message_id,
            f'{sender} send message succeeded but message ID mismatched.'
        )
        receive_message_event = receiver_aware_session_cb_handler.waitAndGet(
            event_name = constants.DiscoverySessionCallbackMethodType.MESSAGE_RECEIVED,
            timeout = _DEFAULT_TIMEOUT,
        )
        received_message_raw = receive_message_event.data[
            constants.WifiAwareSnippetParams.RECEIVED_MESSAGE
        ]
        received_message = bytes(received_message_raw).decode('utf-8')
        asserts.assert_equal(
            received_message,
            send_message,
            f'{receiver} received the message but message content mismatched.'
        )
        return receive_message_event.data[
            constants.WifiAwareSnippetParams.PEER_ID]

    def create_base_config(self,
                           caps: Dict[str, Union[bool, int, str]],
                           is_publish: bool,
                           ptype: Union[int, None],
                           stype: Union[int, None],
                           payload_size: int,
                           ttl: int,
                           term_ind_on: bool,
                           null_match: bool) -> Dict[str, Any]:
        config = {}
        if is_publish:
            config["DiscoveryType"] = ptype
        else:
            config["DiscoveryType"] = stype
        config[constants.TTL_SEC] = ttl
        config[constants.TERMINATE_NOTIFICATION_ENABLED] = term_ind_on
        if payload_size == _PAYLOAD_SIZE_MIN:
            config[constants.SERVICE_NAME] = "a"
            config[constants.SERVICE_SPECIFIC_INFO] = None
            config[constants.MATCH_FILTER] = []
        elif payload_size == _PAYLOAD_SIZE_TYPICAL:
            config[constants.SERVICE_NAME] = "GoogleTestServiceX"
            if is_publish:
                config[constants.SERVICE_SPECIFIC_INFO] = string.ascii_letters
            else:
                config[constants.SERVICE_SPECIFIC_INFO] = string.ascii_letters[
                    ::-1]
            config[constants.MATCH_FILTER] = autils.encode_list(
                [(10).to_bytes(1, byteorder="big"),"hello there string"
                 if not null_match else None,bytes(range(40))])
        else:  # aware_constant.PAYLOAD_SIZE_MAX
            config[constants.SERVICE_NAME] = "VeryLong" + "X" * (
                len("maxServiceNameLen") - 8)
            config[constants.SERVICE_SPECIFIC_INFO] = (
                "P" if is_publish else "S") * len("maxServiceSpecificInfoLen")
            mf = autils.construct_max_match_filter(len("maxMatchFilterLen"))
            if null_match:
                mf[2] = None
            config[constants.MATCH_FILTER] = autils.encode_list(mf)
        return config

    def create_publish_config(self, caps: Dict[str, Union[bool, int, str]],
                              ptype: int, payload_size: int, ttl: int,
                              term_ind_on: bool,
                              null_match: bool) -> Dict[str, Any]:
        return self.create_base_config(caps, True, ptype, None, payload_size,
                                       ttl, term_ind_on, null_match)

    def create_subscribe_config(self, caps: Dict[str, Union[bool, int, str]],
                                stype: int, payload_size: int, ttl: int,
                                term_ind_on: bool,
                                null_match: bool) -> Dict[str, Any]:
        return self.create_base_config(caps, False,  None, stype, payload_size,
                                       ttl, term_ind_on, null_match)

    def _positive_discovery_logic(self, ptype: int, stype: int,
                                payload_size: int) -> None:
        """Utility function for positive discovery test.

        1. Attach both publisher + subscriber to WiFi Aware service.
        2. Publisher publishes a service.
        3. Subscriber discoveries service(s) from publisher.
        4. Exchange messages both publisher + subscriber.
        5. Update publish/subscribe.
        6. Terminate publish/subscribe.

        Args:
            ptype: Publish discovery type.
            stype: Subscribe discovery type.
            payload_size: One of PAYLOAD_SIZE_* constants - MIN, TYPICAL, MAX.

        """
        pid = self._start_attach(self.publisher)
        sid = self._start_attach(self.subscriber)
        p_config = self.create_publish_config(
            self.publisher.wifi_aware_snippet.getCharacteristics(),
            ptype,
            payload_size,
            ttl=0,
            term_ind_on=False,
            null_match=False,
            )
        p_disc_id = self.publisher.wifi_aware_snippet.wifiAwarePublish(
            pid, p_config
            )
        self.publisher.log.info('Created the publish session.')
        p_discovery = p_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT)
        callback_name = p_discovery.data[_CALLBACK_NAME]
        asserts.assert_equal(
            constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
            callback_name,
            f'{self.publisher} publish failed, got callback: {callback_name}.',
            )
        s_config = self.create_subscribe_config(
            self.subscriber.wifi_aware_snippet.getCharacteristics(),
            stype,
            payload_size,
            ttl=0,
            term_ind_on=False,
            null_match=True,
            )
        s_disc_id = self.subscriber.wifi_aware_snippet.wifiAwareSubscribe(
            sid, s_config
            )
        s_discovery = s_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT)
        callback_name = s_discovery.data[_CALLBACK_NAME]
        asserts.assert_equal(
            constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
            callback_name,
            f'{self.subscriber} subscribe failed, got callback: {callback_name}.'
        )
        is_session_init = s_discovery.data[_IS_SESSION_INIT]
        asserts.assert_true(
            is_session_init,
            f'{self.subscriber} subscribe succeeded, but null session returned.'
        )
        discovered_event = s_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.SERVICE_DISCOVERED)
        subscriber_peer = discovered_event.data[constants.WifiAwareSnippetParams.PEER_ID]
        if p_config[constants.SERVICE_SPECIFIC_INFO] == None:
            p_ssi_1st_disc = "null"
        else:
            p_ssi_1st_disc = p_config[constants.SERVICE_SPECIFIC_INFO]
        s_ssi_1st_disc =  bytes(
            discovered_event.data[
                constants.WifiAwareSnippetParams.SERVICE_SPECIFIC_INFO]
            ).decode("utf-8")
        asserts.assert_equal(s_ssi_1st_disc, p_ssi_1st_disc,
                             "Discovery mismatch: service specific info (SSI)")
        p_filter_list_1 = autils.decode_list(p_config[constants.MATCH_FILTER])
        s_filter_list_1 = discovered_event.data[
            constants.WifiAwareSnippetParams.MATCH_FILTER]
        s_filter_list_1 = [bytes(filter[
            constants.WifiAwareSnippetParams.MATCH_FILTER_VALUE
            ]).decode("utf-8")
                           for filter in s_filter_list_1]
        s_filter_list_1 = autils.decode_list(s_filter_list_1)
        asserts.assert_equal(s_filter_list_1, p_filter_list_1,
                             "Discovery mismatch: match filter")
        # Subscriber sends a message to publisher.
        publisher_peer = self._send_msg_and_check_received(
            sender=self.subscriber,
            sender_aware_session_cb_handler=s_disc_id,
            receiver=self.publisher,
            receiver_aware_session_cb_handler=p_disc_id,
            discovery_session=s_disc_id.callback_id,
            peer=subscriber_peer,
            send_message=_MSG_SUB_TO_PUB,
            send_message_id=_MSG_ID_SUB_TO_PUB,
            )
        logging.info(
            'The subscriber sent a message and the publisher received it.'
            )
        # Publisher sends a message to subscriber.
        self._send_msg_and_check_received(
            sender=self.publisher,
            sender_aware_session_cb_handler=p_disc_id,
            receiver=self.subscriber,
            receiver_aware_session_cb_handler=s_disc_id,
            discovery_session=p_disc_id.callback_id,
            peer=publisher_peer,
            send_message=_MSG_PUB_TO_SUB,
            send_message_id=_MSG_ID_PUB_TO_SUB,
        )
        logging.info(
            'The publisher sent a message and the subscriber received it.'
        )
        p_config[constants.SERVICE_SPECIFIC_INFO] = "something else"
        self.publisher.wifi_aware_snippet.wifiAwareUpdatePublish(
            p_disc_id.callback_id, p_config
            )
        p_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.SESSION_CONFIG_UPDATED)
        discovered_event_1 = s_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.SERVICE_DISCOVERED)
        p_ssi_2st_disc = p_config[constants.SERVICE_SPECIFIC_INFO]
        s_ssi_2st_disc = bytes(
            discovered_event_1.data[
                constants.WifiAwareSnippetParams.SERVICE_SPECIFIC_INFO]
        ).decode("utf-8")
        asserts.assert_equal(s_ssi_2st_disc, p_ssi_2st_disc,
                             "Discovery mismatch (after pub update): service specific info (SSI")
        p_filter_list_2 = autils.decode_list(p_config[constants.MATCH_FILTER])
        s_filter_list_2 = discovered_event_1.data[
            constants.WifiAwareSnippetParams.MATCH_FILTER]
        s_filter_list_2 = [bytes(filter[
            constants.WifiAwareSnippetParams.MATCH_FILTER_VALUE]).decode("utf-8")
                           for filter in s_filter_list_2]
        s_filter_list_2 = autils.decode_list(s_filter_list_2)
        asserts.assert_equal(s_filter_list_2, p_filter_list_2,
                             "Discovery mismatch: match filter")
        disc_peer_id = discovered_event_1.data[
            constants.WifiAwareSnippetParams.PEER_ID]
        asserts.assert_equal(subscriber_peer, disc_peer_id,
                             "Peer ID changed when publish was updated!?")
        s_config = self.create_subscribe_config(
            self.subscriber.wifi_aware_snippet.getCharacteristics(),
            stype,
            payload_size,
            ttl=0,
            term_ind_on=False,
            null_match=False,
            )
        s_disc_id_1 = self.subscriber.wifi_aware_snippet.wifiAwareUpdateSubscribe(
            discovered_event_1.callback_id, s_config
            )
        s_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.SESSION_CONFIG_UPDATED)
        self.publisher.wifi_aware_snippet.wifiAwareCloseDiscoverSession(
            p_disc_id.callback_id)
        self.subscriber.wifi_aware_snippet.wifiAwareCloseDiscoverSession(
            s_disc_id.callback_id)
        p_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.SESSION_TERMINATED)
        s_disc_id.waitAndGet(
            constants.DiscoverySessionCallbackMethodType.SESSION_TERMINATED)
        time.sleep(10)
        if self.publisher.is_adb_root:
            # Verify that forbidden callbacks aren't called.
            autils.validate_forbidden_callbacks(self.publisher, {"4": 0})
        self.publisher.wifi_aware_snippet.wifiAwareDetach(pid)
        self.subscriber.wifi_aware_snippet.wifiAwareDetach(sid)

    def verify_discovery_session_term(self, dut, disc_id, config, is_publish,
                                      term_ind_on):
        """Utility to verify that the specified discovery session has terminated.
        (by waiting for the TTL and then attempting to reconfigure).

        Args:
            dut: device under test
            disc_id: discovery id for the existing session
            config: configuration of the existing session
            is_publish: True if the configuration was publish, False if subscribe
            term_ind_on: True if a termination indication is expected, False otherwise
        """
        # Wait for session termination
        if term_ind_on:
            disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.SESSION_TERMINATED,
                timeout = _DEFAULT_TIMEOUT)
        else:
            autils.callback_no_response(
                disc_id,
                constants.DiscoverySessionCallbackMethodType.SESSION_TERMINATED,
                timeout = _DEFAULT_TIMEOUT)
        config[constants.SERVICE_SPECIFIC_INFO] = "something else"
        if is_publish:
            dut.wifi_aware_snippet.wifiAwareUpdatePublish(
            disc_id.callback_id, config
            )
        else:
            dut.wifi_aware_snippet.wifiAwareUpdateSubscribe(
            disc_id.callback_id, config
            )
        if not term_ind_on:
            disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.SESSION_CONFIG_FAILED,
                timeout =_DEFAULT_TIMEOUT
                )

    def positive_ttl_test_utility(self, is_publish, ptype, stype, term_ind_on):
        """Utility which runs a positive discovery session TTL configuration test.

        Iteration 1: Verify session started with TTL
        Iteration 2: Verify session started without TTL and reconfigured with TTL
        Iteration 3: Verify session started with (long) TTL and reconfigured with
                 (short) TTL

        Args:
            is_publish: True if testing publish, False if testing subscribe
            ptype: Publish discovery type (used if is_publish is True)
            stype: Subscribe discovery type (used if is_publish is False)
            term_ind_on: Configuration of termination indication
        """
        SHORT_TTL = 5  # 5 seconds
        LONG_TTL = 100  # 100 seconds
        dut = self.ads[0]
        id = self._start_attach(dut)
        # Iteration 1: Start discovery session with TTL
        config = self.create_base_config(
            dut.wifi_aware_snippet.getCharacteristics(),
            is_publish,
            ptype, stype,
            _PAYLOAD_SIZE_TYPICAL,
            SHORT_TTL,
            term_ind_on,
            False)
        if is_publish:
            disc_id = dut.wifi_aware_snippet.wifiAwarePublish(
                id, config
                )
            p_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = p_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        else:
            disc_id = dut.wifi_aware_snippet.wifiAwareSubscribe(
                id, config
                )
            s_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = s_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        # Wait for session termination & verify
        self.verify_discovery_session_term(dut, disc_id, config, is_publish,
                                           term_ind_on)
        # Iteration 2: Start a discovery session without TTL
        config = self.create_base_config(
            dut.wifi_aware_snippet.getCharacteristics(),
            is_publish,
            ptype, stype,
            _PAYLOAD_SIZE_TYPICAL,
            0,
            term_ind_on,
            False)
        if is_publish:
            disc_id = dut.wifi_aware_snippet.wifiAwarePublish(
                id, config
                )
            p_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = p_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        else:
            disc_id = dut.wifi_aware_snippet.wifiAwareSubscribe(
                id, config
                )
            s_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = s_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        # Update with a TTL
        config = self.create_base_config(
            dut.wifi_aware_snippet.getCharacteristics(),
            is_publish,
            ptype, stype,
            _PAYLOAD_SIZE_TYPICAL,
            SHORT_TTL,
            term_ind_on,
            False)
        if is_publish:
            disc_id = dut.wifi_aware_snippet.wifiAwarePublish(
                id, config
                )
            p_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = p_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        else:
            disc_id = dut.wifi_aware_snippet.wifiAwareSubscribe(
                id, config
                )
            s_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = s_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        # Wait for session termination & verify
        self.verify_discovery_session_term(dut, disc_id, config, is_publish,
                                           term_ind_on)
        # Iteration 3: Start a discovery session with (long) TTL
        config = self.create_base_config(
            dut.wifi_aware_snippet.getCharacteristics(),
            is_publish,
            ptype, stype,
            _PAYLOAD_SIZE_TYPICAL,
            LONG_TTL,
            term_ind_on,
            False)
        if is_publish:
            disc_id = dut.wifi_aware_snippet.wifiAwarePublish(
                id, config
                )
            p_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = p_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        else:
            disc_id = dut.wifi_aware_snippet.wifiAwareSubscribe(
                id, config
                )
            s_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = s_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        # Update with a TTL
        config = self.create_base_config(
            dut.wifi_aware_snippet.getCharacteristics(),
            is_publish,
            ptype, stype,
            _PAYLOAD_SIZE_TYPICAL,
            SHORT_TTL,
            term_ind_on,
            False)
        if is_publish:
            disc_id = dut.wifi_aware_snippet.wifiAwarePublish(
                id, config
                )
            p_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = p_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        else:
            disc_id = dut.wifi_aware_snippet.wifiAwareSubscribe(
                id, config
                )
            s_discovery = disc_id.waitAndGet(
                constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT,
                timeout=_DEFAULT_TIMEOUT)
            callback_name = s_discovery.data[_CALLBACK_NAME]
            asserts.assert_equal(
                constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
                callback_name,
                f'{self.publisher} publish failed, got callback: {callback_name}.',
                )
        # Wait for session termination & verify
        self.verify_discovery_session_term(dut, disc_id, config, is_publish,
                                           term_ind_on)
        # verify that forbidden callbacks aren't called
        if not term_ind_on:
             autils.validate_forbidden_callbacks(
                 dut,{
                    "2": 0,
                    "3": 0})


    def test_positive_unsolicited_passive_typical(self)-> None:
        """Functional test case / Discovery test cases / positive test case:
        - Unsolicited publish + passive subscribe
        - Typical payload fields size

        Verifies that discovery and message exchange succeeds.
        """
        self._positive_discovery_logic(
             _PUBLISH_TYPE_UNSOLICITED,
             _SUBSCRIBE_TYPE_PASSIVE,
             _PAYLOAD_SIZE_TYPICAL
            )

    def test_positive_unsolicited_passive_min(self)-> None:
        """Functional test case / Discovery test cases / positive test case:
        - Unsolicited publish + passive subscribe
        - Minimal payload fields size

        Verifies that discovery and message exchange succeeds.
        """
        self._positive_discovery_logic(
             _PUBLISH_TYPE_UNSOLICITED,
             _SUBSCRIBE_TYPE_PASSIVE,
             _PAYLOAD_SIZE_MIN
            )

    def test_positive_unsolicited_passive_max(self)-> None:
        """Functional test case / Discovery test cases / positive test case:
        - Unsolicited publish + passive subscribe
        - Maximal payload fields size

        Verifies that discovery and message exchange succeeds.
        """
        self._positive_discovery_logic(
             _PUBLISH_TYPE_UNSOLICITED,
             _SUBSCRIBE_TYPE_PASSIVE,
             _PAYLOAD_SIZE_MAX
            )

    def test_positive_solicited_active_typical(self)-> None:
        """Functional test case / Discovery test cases / positive test case:
        - Solicited publish + active subscribe
        - Typical payload fields size

        Verifies that discovery and message exchange succeeds.
        """
        self._positive_discovery_logic(
             _PUBLISH_TYPE_SOLICITED,
             _SUBSCRIBE_TYPE_ACTIVE,
             _PAYLOAD_SIZE_TYPICAL
            )

    def test_positive_solicited_active_min(self)-> None:
        """Functional test case / Discovery test cases / positive test case:
        - Solicited publish + active subscribe
        - Minimal payload fields size

        Verifies that discovery and message exchange succeeds.
        """
        self._positive_discovery_logic(
             _PUBLISH_TYPE_SOLICITED,
             _SUBSCRIBE_TYPE_ACTIVE,
             _PAYLOAD_SIZE_MIN
            )

    def test_positive_solicited_active_max(self)-> None:
        """Functional test case / Discovery test cases / positive test case:
        - Solicited publish + active subscribe
        - Maximal payload fields size

        Verifies that discovery and message exchange succeeds.
        """
        self._positive_discovery_logic(
             _PUBLISH_TYPE_SOLICITED,
             _SUBSCRIBE_TYPE_ACTIVE,
             _PAYLOAD_SIZE_MAX
            )

    #######################################
    # TTL tests key:
    #
    # names is: test_ttl_<pub_type|sub_type>_<term_ind>
    # where:
    #
    # pub_type: Type of publish discovery session: unsolicited or solicited.
    # sub_type: Type of subscribe discovery session: passive or active.
    # term_ind: ind_on or ind_off
    #######################################

    def test_ttl_unsolicited_ind_on(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Unsolicited publish
        - Termination indication enabled
        """
        self.positive_ttl_test_utility(
            is_publish=True,
            ptype=_PUBLISH_TYPE_UNSOLICITED,
            stype=None,
            term_ind_on=True)

    def test_ttl_unsolicited_ind_off(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Unsolicited publish
        - Termination indication disabled
        """
        self.positive_ttl_test_utility(
            is_publish=True,
            ptype=_PUBLISH_TYPE_UNSOLICITED,
            stype=None,
            term_ind_on=False)

    def test_ttl_solicited_ind_on(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Solicited publish
        - Termination indication enabled
        """
        self.positive_ttl_test_utility(
            is_publish=True,
            ptype=_PUBLISH_TYPE_SOLICITED,
            stype=None,
            term_ind_on=True)

    def test_ttl_solicited_ind_off(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Solicited publish
        - Termination indication disabled
        """
        self.positive_ttl_test_utility(
            is_publish=True,
            ptype=_PUBLISH_TYPE_SOLICITED,
            stype=None,
            term_ind_on=False)

    def test_ttl_passive_ind_on(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Passive subscribe
        - Termination indication enabled
        """
        self.positive_ttl_test_utility(
            is_publish=False,
            ptype=None,
            stype=_SUBSCRIBE_TYPE_PASSIVE,
            term_ind_on=True)

    def test_ttl_passive_ind_off(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Passive subscribe
        - Termination indication disabled
        """
        self.positive_ttl_test_utility(
            is_publish=False,
            ptype=None,
            stype=_SUBSCRIBE_TYPE_PASSIVE,
            term_ind_on=False)

    def test_ttl_active_ind_on(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Active subscribe
        - Termination indication enabled
        """
        self.positive_ttl_test_utility(
            is_publish=False,
            ptype=None,
            stype=_SUBSCRIBE_TYPE_ACTIVE,
            term_ind_on=True)

    def test_ttl_active_ind_off(self)-> None:
        """Functional test case / Discovery test cases / TTL test case:
        - Active subscribe
        - Termination indication disabled
        """
        self.positive_ttl_test_utility(
            is_publish=False,
            ptype=None,
            stype=_SUBSCRIBE_TYPE_ACTIVE,
            term_ind_on=False)


if __name__ == '__main__':
    # Take test args
    if '--' in sys.argv:
        index = sys.argv.index('--')
        sys.argv = sys.argv[:1] + sys.argv[index + 1:]

    test_runner.main()
