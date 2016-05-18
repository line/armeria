/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

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
    private ServiceRequestContext context;

    private HttpHealthCheckService service;

    @Before
    public void setUp() {
        service = new HttpHealthCheckService(health1, health2, health3);
        service.serverHealth.setHealthy(true);
    }

    @Test
    public void healthy() throws Exception {
        when(health1.isHealthy()).thenReturn(true);
        when(health2.isHealthy()).thenReturn(true);
        when(health3.isHealthy()).thenReturn(true);

        final DefaultHttpRequest req = new DefaultHttpRequest(HttpHeaders.of(HttpMethod.GET, "/"));
        req.close();
        final AggregatedHttpMessage res = service.serve(context, req).aggregate().get();

        assertEquals(HttpStatus.OK, res.status());
        assertEquals("ok", res.content().toStringAscii());
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
        final DefaultHttpRequest req = new DefaultHttpRequest(HttpHeaders.of(HttpMethod.GET, "/"));
        req.close();
        final AggregatedHttpMessage res = service.serve(context, req).aggregate().get();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.status());
        assertEquals("not ok", res.content().toStringAscii());
    }
}
