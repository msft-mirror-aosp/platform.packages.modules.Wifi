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
"""Test cases for Wi-Fi p2p service discovery."""
from collections.abc import Sequence
import logging

from android.platform.test.annotations import ApiTest
from direct import constants
from direct import p2p_utils
from mobly import base_test
from mobly import records
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device

import wifi_test_utils


@ApiTest(
    [
        "android.net.wifi.p2p.WifiP2pManager#discoverServices(android.net.wifi.p2p.WifiP2pManager.Channel, android.net.wifi.p2p.WifiP2pManager.ActionListener)",
        "android.net.wifi.p2p.WifiP2pManager#addServiceRequest(android.net.wifi.p2p.WifiP2pManager.Channel, android.net.wifi.p2p.WifiP2pManager.WifiP2pServiceRequest, android.net.wifi.p2p.WifiP2pManager.ActionListener)",
        "android.net.wifi.p2p.WifiP2pManager#setUpnpServiceResponseListener(android.net.wifi.p2p.WifiP2pManager.Channel, android.net.wifi.p2p.WifiP2pManager.UpnpServiceResponseListener)",
        "android.net.wifi.p2p.WifiP2pManager#setDnsSdResponseListeners(android.net.wifi.p2p.WifiP2pManager.Channel, android.net.wifi.p2p.WifiP2pManager.DnsSdServiceResponseListener, android.net.wifi.p2p.WifiP2pManager.DnsSdTxtRecordListener)",
        "android.net.wifi.p2p.WifiP2pManager#removeServiceRequest(android.net.wifi.p2p.WifiP2pManager.Channel, android.net.wifi.p2p.WifiP2pManager.WifiP2pServiceRequest, android.net.wifi.p2p.WifiP2pManager.ActionListener)",
        "android.net.wifi.p2p.WifiP2pManager#clearServiceRequests(android.net.wifi.p2p.WifiP2pManager.Channel, android.net.wifi.p2p.WifiP2pManager.ActionListener)"
    ]
)
class ServiceDiscoveryTest(base_test.BaseTestClass):
    """Test cases for Wi-Fi p2p service discovery.

    Test Preconditions:
        Two Android phones that support Wi-Fi Direct.

    Test steps are described in the docstring of each test case.
    """

    ads: Sequence[android_device.AndroidDevice]
    responder_ad: android_device.AndroidDevice
    requester_ad: android_device.AndroidDevice

    def setup_class(self) -> None:
        super().setup_class()
        self.ads = self.register_controller(android_device, min_number=2)
        utils.concurrent_exec(
            self._setup_device,
            param_list=[[ad] for ad in self.ads],
            raise_on_exception=True,
        )
        self.responder_ad, self.requester_ad, *_ = self.ads
        self.responder_ad.debug_tag = (
            f'{self.responder_ad.serial}(Responder)'
        )
        self.requester_ad.debug_tag = f'{self.requester_ad.serial}(Requester)'

    def _setup_device(self, ad: android_device.AndroidDevice) -> None:
        ad.load_snippet('wifi', constants.WIFI_SNIPPET_PACKAGE_NAME)
        wifi_test_utils.set_screen_on_and_unlock(ad)
        # Clear all saved Wi-Fi networks.
        ad.wifi.wifiDisable()
        ad.wifi.wifiClearConfiguredNetworks()
        ad.wifi.wifiEnable()

    def test_search_all_services_1(self) -> None:
        """Searches all p2p services with API
        `WifiP2pServiceRequest.newInstance(WifiP2pServiceInfo.SERVICE_TYPE_ALL)`.

        Test Steps:
            1. Initialize Wi-Fi p2p on both devices.
            2. Add local services UPnP and Bonjour and initiate peer discovery on one device
               (responder).
            3. Add service request on another device (requester) with API
               `WifiP2pServiceRequest.newInstance(WifiP2pServiceInfo.SERVICE_TYPE_ALL)`.
            4. Initiate p2p service discovery on the requester. Verify that the requester
               discovers all services.
        """
        requester, responder = self._setup_wifi_p2p()
        self._add_p2p_services(responder)
        requester.ad.wifi.wifiP2pAddServiceRequest(
            constants.ServiceType.ALL
        )
        self._search_p2p_services(
            responder,
            requester,
            expected_dns_sd_sequence=constants.ServiceData.ALL_DNS_SD,
            expected_dns_txt_sequence=constants.ServiceData.ALL_DNS_TXT,
            expected_upnp_sequence=constants.ServiceData.ALL_UPNP_SERVICES,
        )

    def test_search_all_services_2(self) -> None:
        """Searches all p2p services with API
        `WifiP2pServiceRequest.newInstance(SERVICE_TYPE_BONJOUR/SERVICE_TYPE_UPNP)`

        Test Steps:
            1. Initialize Wi-Fi p2p on both devices.
            2. Add local services UPnP and Bonjour and initiate peer discovery on one device
               (responder).
            3. Add service request on another device (requester) with API
               `WifiP2pServiceRequest.newInstance(SERVICE_TYPE_BONJOUR)` and
               `WifiP2pServiceRequest.newInstance(SERVICE_TYPE_UPNP)`.
            4. Initiate p2p service discovery on the requester. Verify that the requester discovers
               all services.
        """
        requester, responder = self._setup_wifi_p2p()
        self._add_p2p_services(responder)
        requester.ad.wifi.wifiP2pAddServiceRequest(
            constants.ServiceType.BONJOUR,
        )
        requester.ad.wifi.wifiP2pAddServiceRequest(
            constants.ServiceType.UPNP,
        )
        self._search_p2p_services(
            responder,
            requester,
            expected_dns_sd_sequence=constants.ServiceData.ALL_DNS_SD,
            expected_dns_txt_sequence=constants.ServiceData.ALL_DNS_TXT,
            expected_upnp_sequence=constants.ServiceData.ALL_UPNP_SERVICES,
        )

    def test_search_all_services_3(self) -> None:
        """Searches all p2p services with API `WifiP2pDnsSdServiceRequest.newInstance()`
        and `WifiP2pUpnpServiceRequest.newInstance()`.

        Test Steps:
            1. Initialize Wi-Fi p2p on both devices.
            2. Add local services UPnP and Bonjour and initiate peer discovery on one device
               (responder).
            3. Add service request on another device (requester) with API
               `WifiP2pUpnpServiceRequest.newInstance()` and
               `WifiP2pDnsSdServiceRequest.newInstance()`.
            4. Initiate p2p service discovery on the requester. Verify that the requester discovers
               all services.
        """
        requester, responder = self._setup_wifi_p2p()
        self._add_p2p_services(responder)
        requester.ad.wifi.wifiP2pAddBonjourServiceRequest()
        requester.ad.wifi.wifiP2pAddUpnpServiceRequest()
        self._search_p2p_services(
            responder,
            requester,
            expected_dns_sd_sequence=constants.ServiceData.ALL_DNS_SD,
            expected_dns_txt_sequence=constants.ServiceData.ALL_DNS_TXT,
            expected_upnp_sequence=constants.ServiceData.ALL_UPNP_SERVICES,
        )

    def _search_p2p_services(
        self,
        responder: p2p_utils.DeviceState,
        requester: p2p_utils.DeviceState,
        expected_dns_sd_sequence: Sequence[Sequence[str, dict[str, str]]],
        expected_dns_txt_sequence: Sequence[Sequence[str, str]],
        expected_upnp_sequence: Sequence[str],
    ) -> None:
        """Initiate service discovery and assert expected p2p services are discovered.

        Args:
            responder: The responder device state.
            requester: The requester device state.
            expected_dns_sd_sequence: Expected DNS-SD responses.
            expected_dns_txt_sequence: Expected DNS TXT records.
            expected_upnp_sequence: Expected UPNP services.
        """
        requester.ad.log.info('Searching for expected services.')
        p2p_utils.set_upnp_response_listener(requester)
        p2p_utils.set_dns_sd_response_listeners(requester)
        requester.ad.wifi.wifiP2pDiscoverServices()

        responder_address = responder.p2p_device.device_address
        p2p_utils.check_discovered_dns_sd_response(
            requester,
            expected_responses=expected_dns_sd_sequence,
            expected_src_device_address=responder_address,
        )
        p2p_utils.check_discovered_dns_sd_txt_record(
            requester,
            expected_records=expected_dns_txt_sequence,
            expected_src_device_address=responder_address,
        )
        p2p_utils.check_discovered_upnp_services(
            requester,
            expected_services=expected_upnp_sequence,
            expected_src_device_address=responder_address,
        )

    def _add_p2p_services(self, responder: p2p_utils.DeviceState):
        """Sets up P2P services on the responder device.

        This method adds local UPNP and Bonjour services to the responder device and
        initiates peer discovery.
        """
        responder.ad.log.info('Setting up p2p local services UpnP and Bonjour.')
        p2p_utils.add_upnp_local_service(
            responder, constants.ServiceData.DEFAULT_UPNP_SERVICE_CONF
        )
        p2p_utils.add_bonjour_local_service(
            responder, constants.ServiceData.DEFAULT_IPP_SERVICE_CONF
        )
        p2p_utils.add_bonjour_local_service(
            responder, constants.ServiceData.DEFAULT_AFP_SERVICE_CONF
        )
        responder.ad.wifi.wifiP2pDiscoverPeers()

    def _setup_wifi_p2p(self):
        logging.info('Initializing Wi-Fi p2p.')
        responder = p2p_utils.setup_wifi_p2p(self.responder_ad)
        requester = p2p_utils.setup_wifi_p2p(self.requester_ad)
        return requester, responder

    def _teardown_wifi_p2p(self, ad: android_device.AndroidDevice):
        p2p_utils.reset_p2p_service_state(ad)
        p2p_utils.teardown_wifi_p2p(ad)
        ad.services.create_output_excerpts_all(
            self.current_test_info
        )

    def teardown_test(self) -> None:
        utils.concurrent_exec(
            self._teardown_wifi_p2p,
            param_list=[[ad] for ad in self.ads],
            raise_on_exception=False,
        )

    def on_fail(self, record: records.TestResult) -> None:
        logging.info('Collecting bugreports...')
        android_device.take_bug_reports(
            self.ads, destination=self.current_test_info.output_path
        )


if __name__ == '__main__':
    test_runner.main()
