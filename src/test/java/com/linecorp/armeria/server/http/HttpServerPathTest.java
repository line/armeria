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

package com.linecorp.armeria.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.NetUtil;

public class HttpServerPathTest extends AbstractServerTest {

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.port(0, SessionProtocol.HTTP);
        sb.serviceAt("/service/foo", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                    throws Exception {
                res.respond(HttpStatus.OK);
            }
        });
    }

    @Test(timeout = 10000)
    public void testDoubleSlashPath() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, httpPort())) {
            s.setSoTimeout(10000);
            s.getOutputStream().write("GET /service//foo HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(ByteStreams.toByteArray(s.getInputStream()), StandardCharsets.US_ASCII))
                    .startsWith("HTTP/1.1 200 OK\r\n");
        }
    }
}
