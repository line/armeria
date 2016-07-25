/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.http.healthcheck;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.ServiceInvocationContext;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

public class HttpHealthCheckServiceTest {

    private static final EventExecutor executor = ImmediateEventExecutor.INSTANCE;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private HealthChecker health1;

    @Mock
    private HealthChecker health2;

    @Mock
    private HealthChecker health3;

    @Mock
    private ServiceInvocationContext context;

    private HttpHealthCheckService service;

    @Before
    public void setUp() {
        service = new HttpHealthCheckService(health1, health2, health3);
        service.serverHealth.setHealthy(true);

        doAnswer(invocation -> {
            final Object[] args = invocation.getArguments();

            @SuppressWarnings("unchecked")
            final Promise<Object> promise = (Promise<Object>) args[0];
            final FullHttpResponse response = (FullHttpResponse) args[1];

            promise.setSuccess(response);
            return null;
        }).when(context).resolvePromise(any(), any());
    }

    @Test
    public void healthy() throws Exception {
        when(health1.isHealthy()).thenReturn(true);
        when(health2.isHealthy()).thenReturn(true);
        when(health3.isHealthy()).thenReturn(true);

        Promise<Object> promise = executor.newPromise();
        service.handler().invoke(context, executor, promise);
        FullHttpResponse response = (FullHttpResponse) promise.get();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("ok", response.content().toString(StandardCharsets.US_ASCII));
    }

    @Test
    public void notHealthy() throws Exception {
        when(health1.isHealthy()).thenReturn(true);
        when(health2.isHealthy()).thenReturn(false);
        when(health3.isHealthy()).thenReturn(true);

        assertNotOk();
    }

    @Test
    public void notHealthyWhenServerIsStopping() throws Exception {
        when(health1.isHealthy()).thenReturn(true);
        when(health2.isHealthy()).thenReturn(true);
        when(health3.isHealthy()).thenReturn(true);
        service.serverHealth.setHealthy(false);

        assertNotOk();
    }

    private void assertNotOk() throws Exception {Promise<Object> promise = executor.newPromise();
        service.handler().invoke(context, executor, promise);
        FullHttpResponse response = (FullHttpResponse) promise.get();
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        assertEquals("not ok", response.content().toString(StandardCharsets.US_ASCII));
    }
}
