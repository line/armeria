/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.logging;

import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_START;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.math.IntMath;

public class RequestLogAvailabilitySetTest {

    @Test
    public void allGetterFlagsAreAccepted() {
        final RequestLogAvailability[] values = RequestLogAvailability.values();
        final int end = IntMath.pow(2, values.length);
        for (int i = 0; i < end; i++) {
            int flags = 0;
            for (RequestLogAvailability v : values) {
                if ((i & 1 << v.ordinal()) != 0) {
                    flags |= v.getterFlags();
                }
            }

            if (flags != 0) {
                assertThat(RequestLogAvailabilitySet.of(flags)).isNotEmpty();
            }
        }
    }

    @Test
    public void requestContentAfterRequestStart() {
        assertThat(RequestLogAvailabilitySet.of(REQUEST_START.setterFlags())).containsExactly(REQUEST_START);
        assertThat(RequestLogAvailabilitySet.of(REQUEST_START.setterFlags() | REQUEST_CONTENT.setterFlags()))
                .containsExactly(REQUEST_START, REQUEST_CONTENT);
    }

    @Test
    public void requestContentBeforeRequestStart() {
        // Should not return anything because the getter flag of REQUEST_CONTENT does not match.
        assertThat(RequestLogAvailabilitySet.of(REQUEST_CONTENT.setterFlags())).isEmpty();
    }
}
