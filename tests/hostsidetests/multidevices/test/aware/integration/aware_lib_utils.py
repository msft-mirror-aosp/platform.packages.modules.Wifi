

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
"""Util for aware test."""
import datetime
import logging
import time
from typing import Any, Callable

from aware import constants

from mobly import asserts
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import callback_handler_v2
from mobly.snippet import errors


_WAIT_DOZE_MODE_IN_SEC = 5
_TIMEOUT_INTERVAL_IN_SEC = 1
_WAIT_WIFI_STATE_TIME_OUT = datetime.timedelta(seconds=10)
_WAIT_TIME_SEC = 3


def callback_no_response(
    callback: callback_handler_v2.CallbackHandlerV2,
    event_name: str,
    timeout: int = _WAIT_WIFI_STATE_TIME_OUT.total_seconds(),
    use_callbackid: bool = False,
    ):
  """Makes a callback call and expects no response within a given timeout.

  Args:
    callback: Snippet callback object.
    event_name: event name to wait.
    timeout: Timeout in second.
    use_callbackid: Using callbackid in eventname, default False.

  Raises:
    CallBackError: if receive response.
  """
  if use_callbackid:
    event_name += callback.callback_id
  try:
    data = callback.waitAndGet(event_name=event_name, timeout=timeout)
    raise CallBackError(f' Unexpected response {data}')
  except errors.CallbackHandlerTimeoutError:
    return


class CallBackError(Exception):
  """Error raised when there is a problem to get callback response."""


def control_wifi(ad: android_device.AndroidDevice,
                 wifi_state: bool):
  """Control Android Wi-Fi status.

  Args:
    ad: Android test device.
    wifi_state: True if or Wifi on False if WiFi off.
  """
  if _check_wifi_status(ad) != wifi_state:
    if wifi_state:
      ad.adb.shell("svc wifi enable")
    else:
      ad.adb.shell("svc wifi disable")


def _check_wifi_status(ad: android_device.AndroidDevice):
  """Check Android Wi-Fi status.

  Args:
      ad: android device object.

  Returns:
    True if wifi on, False if wifi off.
  """
  cmd = ad.adb.shell("cmd wifi status").decode("utf-8").strip()
  first_line = cmd.split("\n")[0]
  logging.info("device wifi status: %s", first_line)
  if "enabled" in first_line:
    return True
  else:
    return False


def set_doze_mode(ad: android_device.AndroidDevice, state: bool) -> bool:
  """Enables/Disables Android doze mode.

  Args:
      ad: android device object.
      state: bool, True if intent to enable Android doze mode, False otherwise.

  Returns:
    True if doze mode is enabled, False otherwise.

  Raises:
    TimeoutError: If timeout is hit.
  """
  if state:
    ad.log.info("Enables Android doze mode")
    _dumpsys(ad, "battery unplug")
    _dumpsys(ad, "deviceidle enable")
    _dumpsys(ad, "deviceidle force-idle")
    time.sleep(_WAIT_DOZE_MODE_IN_SEC)
  else:
    ad.log.info("Disables Android doze mode")
    _dumpsys(ad, "deviceidle disable")
    _dumpsys(ad, "battery reset")
  for _ in range(10 + 1):
    adb_shell_result = _dumpsys(ad, "deviceidle get deep")
    logging.info("dumpsys deviceidle get deep: %s", adb_shell_result)
    if adb_shell_result.startswith(constants.DeviceidleState.IDLE.value):
      return True
    if adb_shell_result.startswith(constants.DeviceidleState.ACTIVE.value):
      return False
    time.sleep(_TIMEOUT_INTERVAL_IN_SEC)
  # At this point, timeout must have occurred.
  raise errors.CallbackHandlerTimeoutError(
      ad, "Timed out after waiting for doze_mode set to {state}"
  )


def _dumpsys(ad: android_device.AndroidDevice, command: str) -> str:
  """Dumpsys device info.

  Args:
      ad: android device object.
      command: adb command.

  Returns:
    Android dumsys info
  """
  return ad.adb.shell(f"dumpsys {command}").decode().strip()


def check_android_os_version(
    ad: android_device.AndroidDevice,
    operator_func: Callable[[Any, Any], bool],
    android_version: constants.AndroidVersion,
    ) -> bool:
  """Compares device's Android OS version with the given one.

  Args:
    ad: Android devices.
    operator_func: Operator used in the comparison.
    android_version: The given Android OS version.

  Returns:
    bool: The comparison result.
  """
  device_os_version = int(ad.adb.shell("getprop ro.build.version.release"))
  result = False
  if isinstance(operator_func, constants.Operator):
    return operator_func.value(device_os_version, android_version)
  return result


def _get_airplane_mode(ad: android_device.AndroidDevice) -> bool:
  """Gets the airplane mode.

  Args:
    ad: android device object.

  Returns:
    True if airplane mode On, False for Off.
  """
  state = ad.adb.shell("settings get global airplane_mode_on")
  return bool(int(state))


def set_airplane_mode(ad: android_device.AndroidDevice, state: bool):
  """Sets the airplane mode to the given state.

  Args:
    ad: android device object.
    state: bool, True for Airplane mode on, False for off.
  """
  ad.adb.shell(
      ["settings", "put", "global", "airplane_mode_on", str(int(state))]
  )
  ad.adb.shell([
      "am",
      "broadcast",
      "-a",
      "android.intent.action.AIRPLANE_MODE",
      "--ez",
      "state",
      str(state),
  ])
  start_time = time.time()
  while _get_airplane_mode(ad) != state:
    time.sleep(_TIMEOUT_INTERVAL_IN_SEC)
    asserts.assert_greater(
        time.time() - start_time > _WAIT_TIME_SEC,
        f"Failed to set airplane mode to: {state}",
    )
