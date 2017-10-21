/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.throttling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.armeria.common.Request;

public class RateLimitingThrottlingStrategyTest {
    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private RateLimiter rateLimiter;

    @Test
    public void rateLimit() throws Exception {
        RateLimitingThrottlingStrategy<Request> strategy = new RateLimitingThrottlingStrategy<>(rateLimiter);
        when(rateLimiter.tryAcquire()).thenReturn(true)
                                      .thenReturn(false)
                                      .thenReturn(true);
        assertThat(strategy.accept(null, null).get()).isEqualTo(true);
        assertThat(strategy.accept(null, null).get()).isEqualTo(false);
        assertThat(strategy.accept(null, null).get()).isEqualTo(true);
    }
}
