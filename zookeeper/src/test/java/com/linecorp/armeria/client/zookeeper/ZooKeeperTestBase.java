/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.zookeeper;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.Exceptions;

import junitextensions.OptionAssert;
import zookeeperjunit.CloseableZooKeeper;
import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;
import zookeeperjunit.ZooKeeperAssert;

public class ZooKeeperTestBase implements ZooKeeperAssert, OptionAssert {

    protected static final String zNode = "/testEndPoints";
    protected static final int sessionTimeoutMillis = 20000;
    protected static final Set<Endpoint> sampleEndpoints;

    static {
        final int[] ports = unusedPorts(3);
        sampleEndpoints = ImmutableSet.of(Endpoint.of("127.0.0.1", ports[0]).withWeight(2),
                                          Endpoint.of("127.0.0.1", ports[1]).withWeight(4),
                                          Endpoint.of("127.0.0.1", ports[2]).withWeight(2));
    }

    private static final Duration duration = Duration.ofSeconds(10);
    @ClassRule
    public static final TemporaryFolder ROOT_FOLDER = new TemporaryFolder();

    @Nullable
    private static ZKInstance zkInstance;

    @BeforeClass
    public static void start() throws Throwable {
        zkInstance = ZKFactory.apply().withRootDir(ROOT_FOLDER.newFolder("zookeeper")).create();
        zkInstance.start().result(duration);
    }

    @AfterClass
    public static void stop() throws Throwable {
        if (zkInstance != null) {
            zkInstance.stop().ready(duration);
            zkInstance = null;
        }
    }

    private static int[] unusedPorts(int numPorts) {
        final int[] ports = new int[numPorts];
        final Random random = ThreadLocalRandom.current();
        for (int i = 0; i < numPorts; i++) {
            for (;;) {
                final int candidatePort = random.nextInt(64512) + 1024;
                try (ServerSocket ss = new ServerSocket()) {
                    ss.bind(new InetSocketAddress("127.0.0.1", candidatePort));
                    ports[i] = candidatePort;
                    break;
                } catch (IOException e) {
                    // Port in use or unable to bind.
                    continue;
                }
            }
        }

        return ports;
    }

    @Override
    public ZKInstance instance() {
        checkState(zkInstance != null, "ZKInstance not ready");
        return zkInstance;
    }

    @Override
    public CloseableZooKeeper connection() {
        // Try up to three times to reduce flakiness.
        Throwable lastCause = null;
        for (int i = 0; i < 3; i++) {
            try {
                return ZooKeeperAssert.super.connection();
            } catch (Throwable t) {
                lastCause = t;
            }
        }

        return Exceptions.throwUnsafely(lastCause);
    }
}
