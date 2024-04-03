/*
 * Copyright 2024 LINE Corporation
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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServiceAddedTest {

    private static final Queue<String> decoratingEvents = new ConcurrentLinkedQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(delegate -> new MyDecoratingService(delegate, "root"));
            sb.decorator("prefix:/path", delegate -> new MyDecoratingService(delegate, "path"));
            sb.service("/path", new MyService("service"));
            sb.service("/path/decorated", new MyService("service.decorated").decorate(delegate -> {
                return new MyDecoratingService(delegate, "decorated");
            }));
        }
    };

    @Test
    void shouldInvokeServiceAdded() {
        assertThat(decoratingEvents).containsExactlyInAnyOrder(
                "root", "path", "service", "decorated", "service.decorated");
        decoratingEvents.clear();
        server.server().reconfigure(sb -> {
            sb.decorator("prefix:/path", delegate -> new MyDecoratingService(delegate, "path"));
            sb.service("/path", new MyService("service"));
        });
        assertThat(decoratingEvents).containsExactlyInAnyOrder("path", "service");
        decoratingEvents.clear();
    }

    private static final class MyService implements HttpService {
        private final String name;

        private MyService(String name) {
            this.name = name;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(200);
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            decoratingEvents.add(name);
        }
    }

    private static final class MyDecoratingService extends SimpleDecoratingHttpService {
        private final String name;

        MyDecoratingService(HttpService delegate, String name) {
            super(delegate);
            this.name = name;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req);
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            super.serviceAdded(cfg);
            decoratingEvents.add(name);
        }
    }
}
