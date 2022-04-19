/*
 *  Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MappedPathTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @SuppressWarnings("OverlyStrongTypeCast")
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/exact",
                            (SimpleHttpServiceWithRoute) () -> Route.builder()
                                                                    .path("/users/0000")
                                                                    .build());

            sb.serviceUnder("/regex",
                            (SimpleHttpServiceWithRoute) () -> Route.builder()
                                                                    .regex("^/users/(?<userId>[0-9]+)$")
                                                                    .build());
            sb.serviceUnder("/glob",
                            (SimpleHttpServiceWithRoute) () -> Route.builder()
                                                                    .glob("/users/**/profile")
                                                                    .build());

            sb.serviceUnder("/parameterized",
                            (SimpleHttpServiceWithRoute) () -> Route.builder()
                                                                    .path("/users/{userId}")
                                                                    .build());

            sb.serviceUnder("/prefix1",
                            (SimpleHttpServiceWithRoute) () -> Route.builder()
                                                                    .pathPrefix("/prefix2")
                                                                    .build());

            sb.serviceUnder("/catch-all",
                            (SimpleHttpServiceWithRoute) Route::ofCatchAll);
        }
    };

    @CsvSource({
            "/exact/users/0000,         /users/0000",
            "/regex/users/0001,         /users/0001",
            "/glob/users/0002/profile,  /users/0002/profile",
            "/parameterized/users/0003, /users/0003",
            "/prefix1/prefix2/0004,     /0004",
            "/catch-all/a/n/y,          /a/n/y",
    })
    @ParameterizedTest
    void mappedPath(String path, String mappedPath) {
        final BlockingWebClient client = server.blockingWebClient();
        assertThat(client.get(path).contentUtf8()).isEqualTo(mappedPath);
    }

    @FunctionalInterface
    private interface SimpleHttpServiceWithRoute extends HttpServiceWithRoutes {

        Route route();

        @Override
        default Set<Route> routes() {
            return ImmutableSet.of(route());
        }

        @Override
        default HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(ctx.mappedPath());
        }
    }
}
