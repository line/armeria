/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;

class ServerPluginTest {

    @Test
    void installCalledDuringBuild() {
        final AtomicInteger installCount = new AtomicInteger();
        final ServerPlugin plugin = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                installCount.incrementAndGet();
            }
        };

        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(plugin)
                                    .build();
        assertThat(installCount.get()).isEqualTo(1);
        server.close();
    }

    @Test
    void installCalledDuringReconfigure() {
        final AtomicInteger installCount = new AtomicInteger();
        final ServerPlugin plugin = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                installCount.incrementAndGet();
            }
        };

        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(plugin)
                                    .build();
        assertThat(installCount.get()).isEqualTo(1);

        server.reconfigure(sb -> sb.service("/new", (ctx, req) -> HttpResponse.of(200)));
        assertThat(installCount.get()).isEqualTo(2);
        server.close();
    }

    @Test
    void closeCalledDuringStop() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean();
        final ServerPlugin plugin = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {}

            @Override
            public void close() {
                closed.set(true);
            }
        };

        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(plugin)
                                    .build();
        server.start().join();
        assertThat(closed.get()).isFalse();

        server.stop().join();
        assertThat(closed.get()).isTrue();
    }

    @Test
    void multiplePluginsInstalledInOrder() {
        final List<String> order = new ArrayList<>();
        final ServerPlugin pluginA = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("A");
            }
        };
        final ServerPlugin pluginB = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("B");
            }
        };
        final ServerPlugin pluginC = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("C");
            }
        };

        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(pluginA)
                                    .plugin(pluginB)
                                    .plugin(pluginC)
                                    .build();
        assertThat(order).containsExactly("A", "B", "C");
        server.close();
    }

    @Test
    void pluginsSortedByOrder() {
        final List<String> order = new ArrayList<>();
        final ServerPlugin pluginA = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("A");
            }

            @Override
            public int order() {
                return 10;
            }
        };
        final ServerPlugin pluginB = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("B");
            }

            @Override
            public int order() {
                return -1;
            }
        };
        final ServerPlugin pluginC = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("C");
            }

            @Override
            public int order() {
                return 5;
            }
        };

        // Registered in A, B, C order but B (-1) < C (5) < A (10)
        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(pluginA)
                                    .plugin(pluginB)
                                    .plugin(pluginC)
                                    .build();
        assertThat(order).containsExactly("B", "C", "A");
        server.close();
    }

    @Test
    void pluginsWithSameOrderPreserveInsertionOrder() {
        final List<String> order = new ArrayList<>();
        final ServerPlugin pluginA = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("A");
            }
        };
        final ServerPlugin pluginB = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("B");
            }
        };
        final ServerPlugin pluginC = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                order.add("C");
            }
        };

        // All have default order (0), so insertion order is preserved
        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(pluginC)
                                    .plugin(pluginA)
                                    .plugin(pluginB)
                                    .build();
        assertThat(order).containsExactly("C", "A", "B");
        server.close();
    }

    @Test
    void pluginCanModifyServerBuilder() {
        final ServerPlugin plugin = new ServerPlugin() {
            @Override
            public void install(ServerBuilder sb) {
                sb.serverListener(new ServerListenerAdapter() {});
            }
        };

        final Server server = Server.builder()
                                    .http(0)
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .plugin(plugin)
                                    .build();
        // Just verify it builds successfully without error
        server.close();
    }
}
