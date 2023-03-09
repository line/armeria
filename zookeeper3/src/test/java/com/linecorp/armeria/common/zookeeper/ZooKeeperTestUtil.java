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

package com.linecorp.armeria.common.zookeeper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.server.Server;

public final class ZooKeeperTestUtil {

    private static final Random random = new Random();

    public static List<Endpoint> sampleEndpoints(int count) {
        final int[] ports = unusedTcpPorts(count);
        final ImmutableList.Builder<Endpoint> builder = ImmutableList.builder();
        for (int i = 0; i < count; i++) {
            builder.add(Endpoint.of("127.0.0.1", ports[i]).withWeight(random.nextInt(10000) + 1));
        }
        return builder.build();
    }

    static int[] unusedTcpPorts(int numPorts) {
        final int[] ports = new int[numPorts];
        for (int i = 0; i < numPorts; i++) {
            int mayUnusedTcpPort;
            for (;;) {
                mayUnusedTcpPort = PortUtil.unusedTcpPort();
                if (i == 0) {
                    // The first acquired port is always unique.
                    break;
                }
                boolean isAcquiredPort = false;
                for (int j = 0; j < i; j++) {
                    isAcquiredPort = ports[j] == mayUnusedTcpPort;
                    if (isAcquiredPort) {
                        break;
                    }
                }

                if (isAcquiredPort) {
                    // Duplicate port. Look up an unused port again.
                    continue;
                } else {
                    // A newly acquired unique port.
                    break;
                }
            }
            ports[i] = mayUnusedTcpPort;
        }
        return ports;
    }

    public static void startServerWithRetries(Server server) {
        // Work around sporadic 'address already in use' errors.
        await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(
                () -> assertThatCode(() -> server.start().join()).doesNotThrowAnyException());
    }

    private ZooKeeperTestUtil() {}
}
