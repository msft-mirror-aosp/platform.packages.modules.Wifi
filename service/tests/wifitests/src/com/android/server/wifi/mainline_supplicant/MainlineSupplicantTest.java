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

package com.android.server.wifi.mainline_supplicant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.net.wifi.util.Environment;
import android.system.wifi.mainline_supplicant.IMainlineSupplicant;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link MainlineSupplicant}.
 */
public class MainlineSupplicantTest {
    private @Mock IMainlineSupplicant mIMainlineSupplicantMock;
    private MainlineSupplicantSpy mDut;

    // Spy version of this class allows us to override methods for testing.
    private class MainlineSupplicantSpy extends MainlineSupplicant {
        MainlineSupplicantSpy() {
            super();
        }

        @Override
        protected IMainlineSupplicant getNewServiceBinderMockable() {
            return mIMainlineSupplicantMock;
        }
    }

    @Before
    public void setUp() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        MockitoAnnotations.initMocks(this);
        mDut = new MainlineSupplicantSpy();
    }

    /**
     * Verify that the class can be started and stopped successfully.
     */
    @Test
    public void testStartAndStopSuccess() {
        assertTrue(mDut.startService());
        assertTrue(mDut.isActive());
        mDut.stopService();
        assertFalse(mDut.isActive());
    }
}
