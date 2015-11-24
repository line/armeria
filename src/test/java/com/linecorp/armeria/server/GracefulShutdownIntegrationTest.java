/*
 * Copyright 2015 LINE Corporation
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.thrift.TException;
import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.ThriftService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.service.test.thrift.main.SleepService.AsyncIface;

public class GracefulShutdownIntegrationTest extends AbstractServerTest {

    @Override
    protected void configureServer(ServerBuilder sb) {
        sb.gracefulShutdownTimeout(1000L, 2000L);
        final VirtualHostBuilder defaultVirtualHost = new VirtualHostBuilder();

        defaultVirtualHost.serviceAt("/sleep", new ThriftService(
                (AsyncIface) (milliseconds, resultHandler) ->
                        ServiceInvocationContext.current().eventLoop().schedule(
                                () -> resultHandler.onComplete(milliseconds),
                                milliseconds, TimeUnit.MILLISECONDS)).decorate(LoggingService::new));

        sb.defaultVirtualHost(defaultVirtualHost.build());
    }

    @Test
    public void waitsForRequestToComplete() throws Exception {
        startServer();

        SleepService.Iface client = newClient();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            try {
                latch.countDown();
                client.sleep(1000L);
                completed.set(true);
            } catch (TException e) {
                fail("Shouldn't happen");
            }
        }).start();

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch.await();

        stopServer(true);
        assertTrue(completed.get());
    }

    @Test
    public void interruptsSlowRequests() throws Exception {
        startServer();

        SleepService.Iface client = newClient();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            try {
                latch.countDown();
                client.sleep(10000L);
                completed.set(true);
            } catch (TException e) {
                fail("Shouldn't happen");
            }
        }).start();

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch.await();

        stopServer(true);
        assertFalse(completed.get());
    }

    private static SleepService.Iface newClient() throws Exception {
        String uri = "tbinary+" + uri("/sleep");
        return Clients.newClient(uri, SleepService.Iface.class);
    }
}
