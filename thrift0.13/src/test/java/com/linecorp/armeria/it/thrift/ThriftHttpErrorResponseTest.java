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
package com.linecorp.armeria.it.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.base.Ascii;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

/**
 * Tests if Armeria decorators can alter the request/response timeout specified in Thrift call parameters.
 */
public class ThriftHttpErrorResponseTest {

    private enum TestParam {
        ASYNC_STATUS((HelloService.AsyncIface) (name, resultHandler) -> {
            resultHandler.onError(HttpStatusException.of(HttpStatus.CONFLICT));
        }),
        ASYNC_RESPONSE((HelloService.AsyncIface) (name, resultHandler) -> {
            resultHandler.onError(HttpResponseException.of(HttpStatus.CONFLICT));
        }),
        ASYNC_THROW((HelloService.AsyncIface) (name, resultHandler) -> {
            throw HttpStatusException.of(HttpStatus.CONFLICT);
        }),
        SYNC_STATUS((Iface) name -> {
            throw HttpStatusException.of(HttpStatus.CONFLICT);
        }),
        SYNC_RESPONSE((Iface) name -> {
            throw HttpResponseException.of(HttpStatus.CONFLICT);
        });

        final String path;
        final Object service;

        TestParam(Object service) {
            path = '/' + Ascii.toLowerCase(name());
            this.service = service;
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            for (TestParam param : TestParam.values()) {
                sb.service(param.path, THttpService.of(param.service));
            }
        }
    };

    @ParameterizedTest
    @EnumSource(TestParam.class)
    void test(TestParam param) throws Exception {
        final Iface client = Clients.newClient(server.httpUri(BINARY).resolve(param.path), Iface.class);
        assertThatThrownBy(() -> client.hello("foo"))
                .isInstanceOfSatisfying(InvalidResponseHeadersException.class, cause -> {
                    assertThat(cause.headers().status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
}
