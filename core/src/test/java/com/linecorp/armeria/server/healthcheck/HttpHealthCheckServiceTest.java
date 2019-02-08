/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.NetUtil;

public class HttpHealthCheckServiceTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private HealthChecker health1;

    @Mock
    private HealthChecker health2;

    @Mock
    private HealthChecker health3;

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

        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext context = ServiceRequestContext.of(req);
        final AggregatedHttpMessage res = service.serve(context, req).aggregate().get();

        assertEquals(HttpStatus.OK, res.status());
        assertEquals("ok", res.contentUtf8());
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

    private void assertNotOk() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext context = ServiceRequestContext.of(req);
        final AggregatedHttpMessage res = service.serve(context, req).aggregate().get();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.status());
        assertEquals("not ok", res.contentUtf8());
    }

    @Test
    public void testGet() throws Exception {
        final ServerBuilder builder = new ServerBuilder();
        builder.service("/l7check", new HttpHealthCheckService());
        final Server server = builder.build();
        try {
            server.start().join();

            final int port = server.activePort().get().localAddress().getPort();
            try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
                s.setSoTimeout(10000);
                final InputStream in = s.getInputStream();
                final OutputStream out = s.getOutputStream();
                out.write("GET /l7check HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                // Should not be chunked.
                assertThat(new String(ByteStreams.toByteArray(in))).isEqualTo(
                        "HTTP/1.1 200 OK\r\n" +
                        "content-type: text/plain; charset=utf-8\r\n" +
                        "content-length: 2\r\n\r\n" +
                        "ok");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    public void testHead() throws Exception {
        final ServerBuilder builder = new ServerBuilder();
        builder.service("/l7check", new HttpHealthCheckService());
        final Server server = builder.build();
        try {
            server.start().join();

            final int port = server.activePort().get().localAddress().getPort();
            try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
                s.setSoTimeout(10000);
                final InputStream in = s.getInputStream();
                final OutputStream out = s.getOutputStream();
                out.write("HEAD /l7check HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                // Should neither be chunked nor have content.
                assertThat(new String(ByteStreams.toByteArray(in))).isEqualTo(
                        "HTTP/1.1 200 OK\r\n" +
                        "content-type: text/plain; charset=utf-8\r\n" +
                        "content-length: 2\r\n\r\n");
            }
        } finally {
            server.stop();
        }
    }
}
