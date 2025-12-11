/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class IdempotentRequestLogListenerTest {

    @Test
    void shouldNotifyExactlyOnce() {
        final List<RequestLogProperty> events = new ArrayList<>();
        final RequestLogListener listener = (property, log) -> {
            events.add(property);
        };
        final IdempotentRequestLogListener idempotentListener = new IdempotentRequestLogListener(listener);
        idempotentListener.onEvent(RequestLogProperty.REQUEST_START_TIME, null);
        idempotentListener.onEvent(RequestLogProperty.REQUEST_START_TIME, null);
        idempotentListener.onEvent(RequestLogProperty.REQUEST_END_TIME, null);
        idempotentListener.onEvent(RequestLogProperty.REQUEST_END_TIME, null);
        assertThat(events).containsExactly(RequestLogProperty.REQUEST_START_TIME,
                                           RequestLogProperty.REQUEST_END_TIME);
    }
}
