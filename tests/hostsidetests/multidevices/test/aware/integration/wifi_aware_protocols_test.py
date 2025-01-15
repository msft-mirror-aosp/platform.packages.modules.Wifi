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
"""Wi-Fi Aware Protocols test reimplemented in Mobly."""
import logging
import sys
import time

from aware import aware_lib_utils as autils
from aware import constants
from mobly import asserts
from mobly import base_test
from mobly import records
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import callback_handler_v2
from mobly.snippet import callback_event

RUNTIME_PERMISSIONS = (
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.NEARBY_WIFI_DEVICES',
)
PACKAGE_NAME = constants.WIFI_AWARE_SNIPPET_PACKAGE_NAME

_CALLBACK_NAME = constants.DiscoverySessionCallbackParamsType.CALLBACK_NAME

# Publish & Subscribe Config keys.
_PAYLOAD_SIZE_MIN = 0
_PAYLOAD_SIZE_TYPICAL = 1
_PAYLOAD_SIZE_MAX = 2

# number of second to 'reasonably' wait to make sure that devices synchronize
# with each other - useful for OOB test cases, where the OOB discovery would
# take some time
_WAIT_FOR_CLUSTER = 5


class WifiAwareProtocolsTest(base_test.BaseTestClass):
  """Set of tests for Wi-Fi Aware data-paths: validating protocols running ontop of a data-path."""
  # message ID counter to make sure all uses are unique
  msg_id = 0
  device_startup_offset = 1

  ads: list[android_device.AndroidDevice]
  SERVICE_NAME = 'GoogleTestXYZ'

  def setup_class(self):
    # Register two Android devices.
    logging.basicConfig(level=logging.INFO, force=True)
    self.ads = self.register_controller(android_device, min_number=2)

    def setup_device(device: android_device.AndroidDevice):
      autils.control_wifi(device, True)
      device.load_snippet('wifi_aware_snippet', PACKAGE_NAME)
      for permission in RUNTIME_PERMISSIONS:
        device.adb.shell(['pm', 'grant', PACKAGE_NAME, permission])
      asserts.abort_all_if(
          not device.wifi_aware_snippet.wifiAwareIsAvailable(),
          f'{device} Wi-Fi Aware is not available.',
      )

    # Set up devices in parallel.
    utils.concurrent_exec(
        setup_device,
        param_list=[[ad] for ad in self.ads],
        max_workers=1,
        raise_on_exception=True,
    )

  def setup_test(self):
    for ad in self.ads:
      ad.log.info('setup_test: open wifi')
      autils.control_wifi(ad, True)
      aware_avail = ad.wifi_aware_snippet.wifiAwareIsAvailable()
      if not aware_avail:
        ad.log.info('Aware not available. Waiting ...')
        state_handler = ad.wifi_aware_snippet.wifiAwareMonitorStateChange()
        state_handler.waitAndGet(
            constants.WifiAwareBroadcast.WIFI_AWARE_AVAILABLE
        )

  def teardown_test(self):
    utils.concurrent_exec(
        self._teardown_test_on_device,
        param_list=[[ad] for ad in self.ads],
        max_workers=1,
        raise_on_exception=True,
    )
    utils.concurrent_exec(
        lambda d: d.services.create_output_excerpts_all(self.current_test_info),
        param_list=[[ad] for ad in self.ads],
        raise_on_exception=True,
    )

  def _teardown_test_on_device(self, ad: android_device.AndroidDevice) -> None:
    ad.wifi_aware_snippet.wifiAwareCloseAllWifiAwareSession()
    ad.wifi_aware_snippet.wifiAwareMonitorStopStateChange()
    autils.control_wifi(ad, True)

  def on_fail(self, record: records.TestResult) -> None:
    android_device.take_bug_reports(
        self.ads, destination=self.current_test_info.output_path
    )

  def create_ib_ndp(
      self,
      p_dut: android_device.AndroidDevice,
      s_dut: android_device.AndroidDevice,
      p_config: dict[str, any],
      s_config: dict[str, any],
      device_startup_offset,
  ) -> tuple[
      callback_event.CallbackEvent,
      callback_event.CallbackEvent,
      str,
      str,
      str,
      str,
  ]:
    """Create an NDP (using in-band discovery).

    Args:
      p_dut: Device to use as publisher.
      s_dut: Device to use as subscriber.
      p_config: Publish configuration.
      s_config: Subscribe configuration.
      device_startup_offset: Number of seconds to offset the enabling of NAN on
        the two devices.

    Returns:
      A tuple containing the following:
        - Publisher network capabilities.
        - Subscriber network capabilities.
        - Publisher network interface name.
        - Subscriber network interface name.
        - Publisher IPv6 address.
        - Subscriber IPv6 address.
    """
    (_, _, p_disc_id, s_disc_id, peer_id_on_sub, peer_id_on_pub) = (
        autils.create_discovery_pair(
            p_dut, s_dut, p_config, s_config, device_startup_offset, msg_id=9999
        )
    )

    pub_accept_handler = (
        p_dut.wifi_aware_snippet.connectivityServerSocketAccept()
    )
    network_id = pub_accept_handler.callback_id

    # Request network Publisher (responder).
    pub_network_cb_handler = autils.request_network(
        ad=p_dut,
        discovery_session=p_disc_id.callback_id,
        peer=peer_id_on_pub,
        net_work_request_id=network_id,
    )
    # Request network for Subscriber (initiator).
    sub_network_cb_handler = autils.request_network(
        ad=s_dut,
        discovery_session=s_disc_id.callback_id,
        peer=peer_id_on_sub,
        net_work_request_id=network_id,
    )
    pub_network_cap = autils.wait_for_network(
        ad=p_dut,
        request_network_cb_handler=pub_network_cb_handler,
        expected_channel=None,
    )
    sub_network_cap = autils.wait_for_network(
        ad=s_dut,
        request_network_cb_handler=sub_network_cb_handler,
        expected_channel=None,
    )
    pub_network_link = autils.wait_for_link(
        ad=p_dut,
        request_network_cb_handler=pub_network_cb_handler,
    )
    p_aware_if = pub_network_link.data[
        constants.NetworkCbEventKey.NETWORK_INTERFACE_NAME
    ]
    sub_network_link = autils.wait_for_link(
        ad=s_dut,
        request_network_cb_handler=sub_network_cb_handler,
    )
    s_aware_if = sub_network_link.data[
        constants.NetworkCbEventKey.NETWORK_INTERFACE_NAME
    ]

    p_ipv6 = p_dut.wifi_aware_snippet.connectivityGetLinkLocalIpv6Address(
        p_aware_if
    )
    p_dut.log.info('interfaceName = %s, ipv6=%s', p_aware_if, p_ipv6)
    s_ipv6 = s_dut.wifi_aware_snippet.connectivityGetLinkLocalIpv6Address(
        s_aware_if
    )
    s_dut.log.info('interfaceName = %s, ipv6=%s', s_aware_if, s_ipv6)

    return (
        pub_network_cap,
        sub_network_cap,
        p_aware_if,
        s_aware_if,
        p_ipv6,
        s_ipv6,
    )

  def test_ping6_ib_unsolicited_passive(self):
    """Validate ping6 works with NSOLICITED/PASSIVE sessions..

    Validate that ping6 works correctly on an NDP created using Aware
    discovery with UNSOLICITED/PASSIVE sessions.
    """
    p_dut = self.ads[0]
    s_dut = self.ads[1]
    # Create NDP.
    (_, _, p_aware_if, s_aware_if, p_ipv6, s_ipv6) = (
        self.create_ib_ndp(
            p_dut,
            s_dut,
            p_config=autils.create_discovery_config(
                self.SERVICE_NAME, p_type=constants.PublishType.UNSOLICITED
            ),
            s_config=autils.create_discovery_config(
                self.SERVICE_NAME, s_type=constants.SubscribeType.PASSIVE
            ),
            device_startup_offset=self.device_startup_offset,
        )
    )
    logging.info('Interface names: P=%s, S=%s', p_aware_if, s_aware_if)
    logging.info('Interface addresses (IPv6): P=%s, S=%s', p_ipv6, s_ipv6)

    autils.run_ping6(p_dut, s_ipv6)
    time.sleep(1)
    autils.run_ping6(s_dut, p_ipv6)

    # Session clean-up.
    p_dut.wifi_aware_snippet.wifiAwareCloseAllWifiAwareSession()
    s_dut.wifi_aware_snippet.wifiAwareCloseAllWifiAwareSession()

  def test_ping6_ib_solicited_active(self):
    """"Validate ping6 works with  SOLICITED/ACTIVE sessions.

    Validate that ping6 works correctly on an NDP created using Aware
    discovery with SOLICITED/ACTIVE sessions.
    """
    p_dut = self.ads[0]
    s_dut = self.ads[1]
    # Create NDP.
    (_, _, p_aware_if, s_aware_if, p_ipv6, s_ipv6) = (
        self.create_ib_ndp(
            p_dut,
            s_dut,
            p_config=autils.create_discovery_config(
                self.SERVICE_NAME, p_type=constants.PublishType.SOLICITED),
            s_config=autils.create_discovery_config(
                self.SERVICE_NAME, s_type=constants.SubscribeType.ACTIVE),
            device_startup_offset=self.device_startup_offset,
        )
    )
    logging.info('Interface names: P=%s, S=%s', p_aware_if, s_aware_if)
    logging.info('Interface addresses (IPv6): P=%s, S=%s', p_ipv6, s_ipv6)

    autils.run_ping6(p_dut, s_ipv6)
    time.sleep(1)
    autils.run_ping6(s_dut, p_ipv6)

    # Session clean-up.
    p_dut.wifi_aware_snippet.wifiAwareCloseAllWifiAwareSession()
    s_dut.wifi_aware_snippet.wifiAwareCloseAllWifiAwareSession()


if __name__ == '__main__':
  # Take test args
  if '--' in sys.argv:
    index = sys.argv.index('--')
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]

  test_runner.main()
