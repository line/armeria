/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.brave;

import static com.linecorp.armeria.common.HttpStatus.BAD_REQUEST;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import brave.Tracing;
import brave.propagation.StrictScopeDecorator;
import brave.test.http.ITHttpServer;

public class BraveServiceIntegrationTest extends ITHttpServer {

    @Nullable
    private Server server;

    @Before
    @Override
    public void setup() throws Exception {
        currentTraceContext =
            RequestContextCurrentTraceContext.builder()
                .addScopeDecorator(StrictScopeDecorator.create())
                .build();
        super.setup();
    }

    @Override
    protected void init() {
        final ServerBuilder sb = Server.builder();
        sb.service("/", (ctx, req) -> {
            if (req.method() == HttpMethod.OPTIONS) {
                return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "");
            }
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        });
        sb.service("/foo", (ctx, req) -> HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "bar"));
        sb.service("/extra",
                   (ctx, req) -> HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8,
                                                 String.valueOf(req.headers().get(EXTRA_KEY))));
        sb.service("/badrequest", (ctx, req) -> HttpResponse.of(BAD_REQUEST));
        sb.service("/child", (ctx, req) -> {
            Tracing.currentTracer().nextSpan().name("child").start().finish();
            return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "happy");
        });
        sb.service("/exception", (ctx, req) -> {
            throw new Exception("not ready");
        });
        sb.decorator(BraveService.newDecorator(httpTracing));

        server = sb.build();
        server.start().join();
    }

    @Override
    @Test
    public void notFound() {
        throw new AssumptionViolatedException("Armeria cannot decorate a non-existent path mapping.");
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Override
    protected String url(String path) {
        assertThat(server).isNotNull();
        return "http://127.0.0.1:" + server.activeLocalPort(SessionProtocol.HTTP) + path;
    }
}
