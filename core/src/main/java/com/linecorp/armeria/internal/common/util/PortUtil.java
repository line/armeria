/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class PortUtil {

    private static final int PORT_MIN = 1024;
    private static final int PORT_MAX = 65535;
    private static final int PORT_RANGE = PORT_MAX - PORT_MIN + 1;

    public static int unusedTcpPort() {
        final Random random = ThreadLocalRandom.current();
        for (int i = 0; i < PORT_RANGE; i++) {
            final int candidatePort = random.nextInt(PORT_RANGE) + PORT_MIN;
            try (ServerSocket ignored = new ServerSocket(candidatePort, 1,
                                                         InetAddress.getByName("127.0.0.1"))) {
                return candidatePort;
            } catch (IOException e) {
                // Port in use or unable to bind.
                continue;
            }
        }

        throw new IllegalStateException(
                "Failed to find an unused TCP port in the range [" + PORT_MIN + ", " + PORT_MAX + "] after + " +
                PORT_RANGE + " attempts");
    }

    private PortUtil() {}
}
