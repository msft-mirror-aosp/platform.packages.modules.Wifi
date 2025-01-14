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
"""Wi-Fi Aware Discovery with ranging test reimplemented in Mobly."""
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

RUNTIME_PERMISSIONS = (
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.NEARBY_WIFI_DEVICES',
)
PACKAGE_NAME = constants.WIFI_AWARE_SNIPPET_PACKAGE_NAME

_CALLBACK_NAME = constants.DiscoverySessionCallbackParamsType.CALLBACK_NAME
_DEFAULT_TIMEOUT = constants.WAIT_WIFI_STATE_TIME_OUT.total_seconds()

_LARGE_ENOUGH_DISTANCE_MM = 1000000
_IS_SESSION_INIT = constants.DiscoverySessionCallbackParamsType.IS_SESSION_INIT


class WiFiAwareDiscoveryWithRangingTest(base_test.BaseTestClass):
  """Set of tests for Wi-Fi Aware discovery configured with ranging (RTT)."""
  SERVICE_NAME = 'GoogleTestServiceRRRRR'

  # Flag indicating whether the device has a limitation that does not allow it
  # to execute Aware-based Ranging (whether direct or as part of discovery)
  # whenever NDP is enabled.
  RANGING_NDP_CONCURRENCY_LIMITATION = True

  # Flag indicating whether the device has a limitation that does not allow it
  # to execute Aware-based Ranging (whether direct or as part of discovery)
  # for both Initiators and Responders. Only the first mode works.
  RANGING_INITIATOR_RESPONDER_CONCURRENCY_LIMITATION = True

  ads: list[android_device.AndroidDevice]
  device_startup_offset = 2
  publisher: android_device.AndroidDevice
  subscriber: android_device.AndroidDevice

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
    logging.info('setup_test')
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
    logging.info('teardown_test')
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
    logging.info('on_fail')
    android_device.take_bug_reports(
        self.ads, destination=self.current_test_info.output_path
    )

  def getname(self, level=1):
    """Python magic to return the name of the *calling* function.

    Args:
      level: How many levels up to go for the method name. Default = calling
        method.

    Returns:
      The name of the *calling* function.
    """
    logging.info('debug> %s', sys._getframe(level).f_code.co_name)
    return sys._getframe(level).f_code.co_name

  def run_discovery(
      self,
      p_config: dict[str, any],
      s_config: dict[str, any],
      expect_discovery: bool,
      expect_range: bool = False,
  ) -> tuple[android_device.AndroidDevice, android_device.AndroidDevice,
             callback_handler_v2.CallbackHandlerV2,
             callback_handler_v2.CallbackHandlerV2]:
    """Run discovery on the 2 input devices with the specified configurations.

    Args:
      p_config: Publisher discovery configuration.
      s_config: Subscriber discovery configuration.
      expect_discovery: True or False indicating whether discovery is expected
      with the specified configurations.
      expect_range: True if we expect distance results (i.e. ranging to happen).
      Only relevant if expect_discovery is True.

    Returns:
      p_dut, s_dut: Publisher/Subscribe DUT
      p_disc_id, s_disc_id: Publisher/Subscribe discovery session ID
    """
    p_dut = self.ads[0]
    p_dut.pretty_name = 'Publisher'
    s_dut = self.ads[1]
    s_dut.pretty_name = 'Subscriber'
    p_dut.log.info(p_config)
    p_dut.log.info(s_config)

    # Publisher+Subscriber: attach and wait for confirmation.
    p_id = p_dut.wifi_aware_snippet.wifiAwareAttached(False)
    p_id.waitAndGet(constants.AttachCallBackMethodType.ATTACHED)
    time.sleep(self.device_startup_offset)
    s_id = s_dut.wifi_aware_snippet.wifiAwareAttached(False)
    s_id.waitAndGet(constants.AttachCallBackMethodType.ATTACHED)

    # Publisher: start publish and wait for confirmation.
    p_dut.log.info('start publish')
    p_disc_id = p_dut.wifi_aware_snippet.wifiAwarePublish(p_id.callback_id,
                                                          p_config.to_dict())
    p_discovery = p_disc_id.waitAndGet(
        constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT)
    callback_name = p_discovery.data[_CALLBACK_NAME]
    asserts.assert_equal(
        constants.DiscoverySessionCallbackMethodType.PUBLISH_STARTED,
        callback_name,
        f'{p_dut} publish failed, got callback: {callback_name}.',
        )
    time.sleep(1)
    s_dut.log.info('start subscribe')
    # Subscriber: start subscribe and wait for confirmation.
    s_disc_id = s_dut.wifi_aware_snippet.wifiAwareSubscribe(s_id.callback_id,
                                                            s_config.to_dict())
    s_discovery = s_disc_id.waitAndGet(
        constants.DiscoverySessionCallbackMethodType.DISCOVER_RESULT)
    callback_name = s_discovery.data[_CALLBACK_NAME]
    asserts.assert_equal(
        constants.DiscoverySessionCallbackMethodType.SUBSCRIBE_STARTED,
        callback_name,
        f'{s_dut} subscribe failed, got callback: {callback_name}.',
        )

    # Subscriber: wait or fail on service discovery.
    if expect_discovery:
        event_name = (
            constants.DiscoverySessionCallbackMethodType.SERVICE_DISCOVERED
        )
        if expect_range:
            event_name = (
                constants.DiscoverySessionCallbackMethodType
                .SERVICE_DISCOVERED_WITHIN_RANGE
            )
        discover_data = s_disc_id.waitAndGet(
            event_name=event_name, timeout=_DEFAULT_TIMEOUT
        )

        if expect_range:
            asserts.assert_true(
                constants.WifiAwareSnippetParams.DISTANCE_MM
                in discover_data.data,
                'Discovery with ranging expected!',
            )
            s_dut.log.info('distance=%s', discover_data.data[
                constants.WifiAwareSnippetParams.DISTANCE_MM])
        else:
            asserts.assert_false(
                constants.WifiAwareSnippetParams.DISTANCE_MM
                in discover_data.data,
                'Discovery with ranging NOT expected!',
            )
    else:
        s_dut.log.info('onServiceDiscovered NOT expected! wait for timeout.')
        autils.callback_no_response(
            s_disc_id,
            constants.DiscoverySessionCallbackMethodType.SERVICE_DISCOVERED,
            timeout=_DEFAULT_TIMEOUT)

    return p_dut, s_dut, p_disc_id, s_disc_id

  def test_ranged_discovery_unsolicited_passive_prange_snorange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber disables ranging

    Expect: normal discovery (as if no ranging performed) - no distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_solicited_active_prange_snorange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber disables ranging

    Expect: normal discovery (as if no ranging performed) - no distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_unsolicited_passive_pnorange_smax_inrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher disables ranging
    - Subscriber enables ranging with max such that always within range (large
      max)

    Expect: normal discovery (as if no ranging performed) - no distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=False,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=None,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_solicited_active_pnorange_smax_inrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher disables ranging
    - Subscriber enables ranging with max such that always within range (large
      max)

    Expect: normal discovery (as if no ranging performed) - no distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=False,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=None,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_unsolicited_passive_pnorange_smin_outofrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher disables ranging
    - Subscriber enables ranging with min such that always out of range (large
      min)

    Expect: normal discovery (as if no ranging performed) - no distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=False,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
            max_distance_mm=None,
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_solicited_active_pnorange_smin_outofrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher disables ranging
    - Subscriber enables ranging with min such that always out of range (large
      min)

    Expect: normal discovery (as if no ranging performed) - no distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=False,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
            max_distance_mm=None,
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_unsolicited_passive_prange_smin_inrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min such that in range (min=0)

    Expect: discovery with distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=0,
            max_distance_mm=None,
        ),
        expect_discovery=True,
        expect_range=True,
    )

  def test_ranged_discovery_unsolicited_passive_prange_smax_inrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with max such that in range (max=large)

    Expect: discovery with distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=None,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
        ),
        expect_discovery=True,
        expect_range=True,
    )

  def test_ranged_discovery_unsolicited_passive_prange_sminmax_inrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min/max such that in range (min=0,
      max=large)

    Expect: discovery with distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=0,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
        ),
        expect_discovery=True,
        expect_range=True,
    )

  def test_ranged_discovery_solicited_active_prange_smin_inrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min such that in range (min=0)

    Expect: discovery with distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=0,
            max_distance_mm=None,
        ),
        expect_discovery=True,
        expect_range=True,
    )

  def test_ranged_discovery_solicited_active_prange_smax_inrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with max such that in range (max=large)

    Expect: discovery with distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=None,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
        ),
        expect_discovery=True,
        expect_range=True,
    )

  def test_ranged_discovery_solicited_active_prange_sminmax_inrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min/max such that in range (min=0,
      max=large)

    Expect: discovery with distance
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=0,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
        ),
        expect_discovery=True,
        expect_range=True,
    )

  def test_ranged_discovery_unsolicited_passive_prange_smin_outofrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min such that out of range (min=large)

    Expect: no discovery
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
            max_distance_mm=None,
        ),
        expect_discovery=False,
    )

  def test_ranged_discovery_unsolicited_passive_prange_smax_outofrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with max such that in range (max=0)

    Expect: no discovery
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=None,
            max_distance_mm=0,
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_unsolicited_passive_prange_sminmax_outofrange(self):
    """Verify discovery(unsolicited/passive) with ranging.

    - Unsolicited Publish/Passive Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min/max such that out of range (min=large,
      max=large+1)

    Expect: no discovery
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.UNSOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.PASSIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM + 1,
        ),
        expect_discovery=False,
    )

  def test_ranged_discovery_solicited_active_prange_smin_outofrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min such that out of range (min=large)

    Expect: no discovery
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
            max_distance_mm=None,
        ),
        expect_discovery=False,
    )

  def test_ranged_discovery_solicited_active_prange_smax_outofrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with max such that out of range (max=0)

    Expect: no discovery
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=None,
            max_distance_mm=0,
        ),
        expect_discovery=True,
        expect_range=False,
    )

  def test_ranged_discovery_solicited_active_prange_sminmax_outofrange(self):
    """Verify discovery(solicited/active) with ranging.

    - Solicited Publish/Active Subscribe
    - Publisher enables ranging
    - Subscriber enables ranging with min/max such that out of range (min=large,
      max=large+1)

    Expect: no discovery
    """
    self.run_discovery(
        p_config=constants.PublishConfig(
            service_name=self.SERVICE_NAME,
            publish_type=constants.PublishType.SOLICITED,
            service_specific_info=self.getname().encode(),
            ranging_enabled=True,
        ),
        s_config=constants.SubscribeConfig(
            service_name=self.SERVICE_NAME,
            subscribe_type=constants.SubscribeType.ACTIVE,
            service_specific_info=self.getname().encode(),
            min_distance_mm=_LARGE_ENOUGH_DISTANCE_MM,
            max_distance_mm=_LARGE_ENOUGH_DISTANCE_MM + 1,
        ),
        expect_discovery=False,
    )


if __name__ == '__main__':
  # Take test args
  if '--' in sys.argv:
    index = sys.argv.index('--')
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]

  test_runner.main()
