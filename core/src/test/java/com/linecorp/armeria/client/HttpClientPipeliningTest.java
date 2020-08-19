/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit4.common.EventLoopRule;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.netty.channel.EventLoopGroup;

public class HttpClientPipeliningTest {

    // Server-side configuration
    private static final Semaphore semaphore = new Semaphore(0);
    private static final Lock lock = new ReentrantLock();
    private static final Condition condition = lock.newCondition();
    private static volatile boolean connectionReturnedToPool;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Bind a service that returns the remote address of the connection to determine
            // if the same connection was used to handle more than one request.
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    // Consume the request completely so that the connection can be returned to the pool.
                    return HttpResponse.from(req.aggregate().handle((unused1, unused2) -> {
                        // Signal the main thread that the connection has been returned to the pool.
                        // Note that this is true only when pipelining is enabled. The connection is returned
                        // after response is fully sent if pipelining is disabled.
                        lock.lock();
                        try {
                            connectionReturnedToPool = true;
                            condition.signal();
                        } finally {
                            lock.unlock();
                        }

                        semaphore.acquireUninterruptibly();
                        try {
                            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                   String.valueOf(ctx.remoteAddress()));
                        } finally {
                            semaphore.release();
                        }
                    }));
                }
            });
        }
    };

    // Client-side configuration
    @ClassRule
    public static final EventLoopRule eventLoopGroup = new EventLoopRule();
    private static ClientFactory factoryWithPipelining;
    private static ClientFactory factoryWithoutPipelining;

    private final EventLoopGroup aggregateExecutors = EventLoopGroups.newEventLoopGroup(2);

    @BeforeClass
    public static void initClientFactory() {
        // Ensure only a single event loop is used so that there's only one connection pool.
        // Note: Each event loop has its own connection pool.
        factoryWithPipelining = ClientFactory.builder()
                                             .workerGroup(eventLoopGroup.get(), false)
                                             .useHttp1Pipelining(true)
                                             .build();

        factoryWithoutPipelining = ClientFactory.builder()
                                                .workerGroup(eventLoopGroup.get(), false)
                                                .useHttp1Pipelining(false)
                                                .build();
    }

    @AfterClass
    public static void destroyClientFactory() {
        factoryWithPipelining.closeAsync();
        factoryWithoutPipelining.closeAsync();
    }

    @Before
    public void resetState() {
        semaphore.drainPermits();
        connectionReturnedToPool = false;
    }

    @Test
    public void withoutPipelining() throws Exception {
        final WebClient client = WebClient.builder("h1c://127.0.0.1:" + server.httpPort())
                                          .factory(factoryWithoutPipelining)
                                          .build();

        final HttpResponse res1 = client.get("/");
        final HttpResponse res2 = client.get("/");

        // At this point, the two requests have acquired two different connections from the pool
        // because pipelining is disabled and thus the connection of the first request will not
        // be returned to the pool until its response is fully received.

        // Give two permits to the Semaphore so that the two requests get their responses.
        semaphore.release(2);

        // Two requests should go through two different connections.
        final String remoteAddress1 = res1.aggregate(aggregateExecutors.next()).get().contentUtf8();
        final String remoteAddress2 = res2.aggregate(aggregateExecutors.next()).get().contentUtf8();
        assertThat(remoteAddress1).isNotEqualTo(remoteAddress2);
    }

    @Test
    public void withPipelining() throws Exception {
        final WebClient client = WebClient.builder("h1c://127.0.0.1:" + server.httpPort())
                                          .factory(factoryWithPipelining)
                                          .build();

        final HttpResponse res1;
        lock.lock();
        try {
            res1 = client.get("/");
            // Wait until the connection used by res1 is returned to the pool,
            // so that the next request reuses the connection.
            while (!connectionReturnedToPool) {
                condition.await();
            }
        } finally {
            lock.unlock();
        }

        // At this point, we are sure the connection of the first request has been returned to the pool,
        // because pipelining is enabled and thus the connection will be returned to the pool once
        // the request has been fully sent.

        // Now, send the second request so that the client reuses the connection.
        final HttpResponse res2 = client.get("/");

        // Give two permits to the Semaphore so that the two requests get their responses.
        semaphore.release(2);

        // Two requests should go through one same connection.
        final String remoteAddress1 = res1.aggregate(aggregateExecutors.next()).get().contentUtf8();
        final String remoteAddress2 = res2.aggregate(aggregateExecutors.next()).get().contentUtf8();
        assertThat(remoteAddress1).isEqualTo(remoteAddress2);
    }
}
