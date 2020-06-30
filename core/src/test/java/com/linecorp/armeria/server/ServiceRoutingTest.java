/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServiceRoutingTest {
    @RegisterExtension
    static ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/api/v0/", new Object() {
                @Get("regex:/projects/(?<projectName>[^/]+)")
                public String project(@Param("projectName") String projectName) {
                    return "project";
                }
            });
            sb.annotatedService("/api/v0/", new Object() {
                @Get("/users/me")
                public String me() {
                    return "me";
                }
            });
            sb.serviceUnder("/", (ctx, req) -> HttpResponse.of("fallback"));
        }
    };

    @RegisterExtension
    static ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/api/v0/", new Object() {
                @Get("/users/me")
                public String me() {
                    return "me";
                }
            });
            sb.annotatedService("/api/v0/", new Object() {
                @Get("regex:/projects/(?<projectName>[^/]+)")
                public String project(@Param("projectName") String projectName) {
                    return "project";
                }
            });
            sb.serviceUnder("/", (ctx, req) -> HttpResponse.of("fallback"));
        }
    };

    @RegisterExtension
    static ServerExtension server3 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/", (ctx, req) -> HttpResponse.of("fallback"));
            sb.annotatedService("/api/v0/", new Object() {
                @Get("/users/me")
                public String me() {
                    return "me";
                }
            });
            sb.annotatedService("/api/v0/", new Object() {
                @Get("regex:/projects/(?<projectName>[^/]+)")
                public String project(@Param("projectName") String projectName) {
                    return "project";
                }
            });
        }
    };

    @Test
    void checkServiceRoutingPriority() {
        final WebClient client1 = WebClient.of(server1.httpUri());
        assertThat(client1.get("/api/v0/users/me").aggregate().join().contentUtf8()).isEqualTo("me");
        assertThat(client1.get("/api/v0/projects/foo").aggregate().join().contentUtf8()).isEqualTo("project");
        assertThat(client1.get("/fallback").aggregate().join().contentUtf8()).isEqualTo("fallback");

        final WebClient client2 = WebClient.of(server2.httpUri());
        assertThat(client2.get("/api/v0/users/me").aggregate().join().contentUtf8()).isEqualTo("me");
        assertThat(client2.get("/api/v0/projects/foo").aggregate().join().contentUtf8()).isEqualTo("project");
        assertThat(client2.get("/fallback").aggregate().join().contentUtf8()).isEqualTo("fallback");

        final WebClient client3 = WebClient.of(server3.httpUri());
        assertThat(client3.get("/api/v0/users/me").aggregate().join().contentUtf8()).isEqualTo("me");
        assertThat(client3.get("/fallback").aggregate().join().contentUtf8()).isEqualTo("fallback");
        // end of trie route, fallback has higher priority than regex
        assertThat(client3.get("/api/v0/projects/foo").aggregate().join().contentUtf8()).isEqualTo("fallback");
    }
}
