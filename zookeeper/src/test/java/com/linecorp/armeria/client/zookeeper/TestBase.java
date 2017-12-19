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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;

import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;

public class TestBase {
    protected static final Logger logger = LoggerFactory.getLogger(EndpointGroupTest.class);
    protected static final String zNode = "/testEndPoints";
    protected static final int sessionTimeout = 20000;
    protected static final Set<Endpoint> sampleEndpoints;

    static {
        final int[] ports = unusedPorts(3);
        sampleEndpoints = ImmutableSet.of(Endpoint.of("127.0.0.1", ports[0], 2),
                                          Endpoint.of("127.0.0.1", ports[1], 4),
                                          Endpoint.of("127.0.0.1", ports[2], 2));
    }

    private static final Duration duration = Duration.ofSeconds(10);
    @ClassRule
    public static final TemporaryFolder ROOT_FOLDER = new TemporaryFolder();
    private static ZKInstance zkInstance;

    @BeforeClass
    public static void start() throws Throwable {
        zkInstance = ZKFactory.apply().withRootDir(ROOT_FOLDER.newFolder("zookeeper")).create();
        zkInstance.start().result(duration);
    }

    @AfterClass
    public static void stop() throws Throwable {
        zkInstance.stop().ready(duration);
    }

    protected static List<KeeperState> takeAllStates(BlockingQueue<KeeperState> queue) throws Exception {
        final List<KeeperState> actualStates = new ArrayList<>();
        for (;;) {
            final KeeperState state = queue.poll(3, TimeUnit.SECONDS);
            if (state == null) {
                break;
            }
            actualStates.add(state);
        }
        return actualStates;
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

    public ZKInstance instance() {
        return zkInstance;
    }
}
