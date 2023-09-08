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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

import org.apache.thrift.TApplicationException;
import org.junit.Test;

import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Exceptions;

import testing.thrift.main.HelloService;

public class THttpClientBadSeqIdTest {

    @Test(timeout = 30000L)
    public void badSeqId() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setSoTimeout(5000);

            final THttpClient client = ThriftClients.newClient(
                    "ttext+h1c://127.0.0.1:" + ss.getLocalPort(), THttpClient.class);

            final RpcResponse res = client.execute("/", HelloService.Iface.class, "hello", "trustin");
            assertThat(res.isDone()).isFalse();

            try (Socket s = ss.accept()) {
                final InputStream sin = s.getInputStream();
                final OutputStream sout = s.getOutputStream();

                // Ensure the request is received before sending its response.
                assertThat(sin.read()).isGreaterThanOrEqualTo(0);

                // Send the TTEXT over HTTP/1 response with mismatching seqid.
                final byte[] thriftTextResponse = ('{' +
                                                   "  \"method\": \"hello\"," +
                                                   "  \"type\": \"CALL\"," +
                                                   "  \"seqid\": " + Integer.MIN_VALUE + ',' +
                                                   "  \"args\": { \"success\": \"Hello, trustin!\" }" +
                                                   '}').getBytes(StandardCharsets.US_ASCII);
                sout.write(("HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Content-Length: " + thriftTextResponse.length + "\r\n" +
                            "\r\n").getBytes(StandardCharsets.US_ASCII));
                sout.write(thriftTextResponse);

                // Wait until the client closes the connection thanks to 'connection: close'.
                while (sin.read() >= 0) {
                    continue;
                }
            } catch (SocketTimeoutException expected) {
                // A connection was not accepted; Wait for the response to raise the cause.
                res.join();
                // Should not reach here because .join() will fail, but for completeness:
                throw expected;
            }

            assertThatThrownBy(res::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TApplicationException.class)
                    .satisfies(cause -> assertThat(((TApplicationException) Exceptions.peel(cause)).getType())
                            .isEqualTo(TApplicationException.BAD_SEQUENCE_ID));
        }
    }
}
