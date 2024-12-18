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
"""CTS Wi-Fi Direct test suite."""

from mobly import base_suite
from mobly import suite_runner

from direct import group_owner_negotiation_test
from direct import group_owner_test


class CtsWifiDirectTestSuite(base_suite.BaseSuite):
    """CTS Wi-Fi Direct test suite."""

    def setup_suite(self, config):
        del config  # unused
        self.add_test_class(
            group_owner_negotiation_test.GroupOwnerNegotiationTest
        )
        self.add_test_class(group_owner_test.GroupOwnerTest)


if __name__ == '__main__':
    suite_runner.run_suite_class()
