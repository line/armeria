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

package com.linecorp.armeria.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.service.test.thrift.main.SleepService.AsyncIface;

public class GracefulShutdownIntegrationTest extends AbstractServerTest {

    @Override
    protected void configureServer(ServerBuilder sb) {
        sb.gracefulShutdownTimeout(1000L, 2000L);
        sb.defaultRequestTimeoutMillis(0); // Disable RequestTimeoutException.

        sb.serviceAt("/sleep", THttpService.of(
                (AsyncIface) (milliseconds, resultHandler) ->
                        RequestContext.current().eventLoop().schedule(
                                () -> resultHandler.onComplete(milliseconds),
                                milliseconds, TimeUnit.MILLISECONDS)).decorate(LoggingService::new));
    }

    @Test(timeout = 10000L)
    public void waitsForRequestToComplete() throws Exception {
        startServer();

        SleepService.Iface client = newClient();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            try {
                latch.countDown();
                client.sleep(1000L);
                completed.set(true);
            } catch (Throwable t) {
                fail("Shouldn't happen: " + t);
            }
        });

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch.await();

        stopServer();
        assertTrue(completed.get());
    }

    @Test(timeout = 10000L)
    public void interruptsSlowRequests() throws Exception {
        startServer();

        SleepService.Iface client = newClient();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            try {
                latch1.countDown();
                client.sleep(30000L);
                completed.set(true);
            } catch (ClosedSessionException expected) {
                latch2.countDown();
            } catch (Throwable t) {
                fail("Shouldn't happen: " + t);
            }
        });

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch1.await();

        stopServer();
        assertFalse(completed.get());

        // 'client.sleep()' must fail immediately when the server closes the connection.
        latch2.await();
    }

    private static SleepService.Iface newClient() throws Exception {
        String uri = "tbinary+" + uri("/sleep");
        return Clients.newClient(uri, SleepService.Iface.class);
    }
}
