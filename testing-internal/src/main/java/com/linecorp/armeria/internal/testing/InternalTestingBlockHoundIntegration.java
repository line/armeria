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

package com.linecorp.armeria.internal.testing;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

import reactor.blockhound.BlockHound.Builder;
import reactor.blockhound.BlockingMethod;
import reactor.blockhound.integration.BlockHoundIntegration;

public final class InternalTestingBlockHoundIntegration implements BlockHoundIntegration {

    private static final OutputStream NULL = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
        }
    };

    static final PrintStream ps;

    static {
        final String path = System.getProperties().getProperty("com.linecorp.armeria.blockhound.reportFile");
        if (path == null) {
            ps = new PrintStream(NULL);
        } else {
            final File file = new File(path);
            try {
                ps = new PrintStream(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(ps::close));
    }

    @Override
    public void applyTo(Builder builder) {

        // tests are allowed to block event loops
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "sleep");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "join");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "acquireUninterruptibly");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "await");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.testing.BlockingUtils",
                                         "blockingRun");
        builder.allowBlockingCallsInside("org.assertj.core.api.Assertions", "assertThat");
        builder.allowBlockingCallsInside("net.javacrumbs.jsonunit.fluent.JsonFluentAssert",
                                         "assertThatJson");
        builder.allowBlockingCallsInside("com.linecorp.armeria.testing.server.ServiceRequestContextCaptor$2",
                                         "serve");
        builder.allowBlockingCallsInside("org.slf4j.impl.SimpleLogger", "write");
        builder.allowBlockingCallsInside(
                "com.linecorp.armeria.internal.testing.InternalTestingBlockHoundIntegration",
                "writeBlockingMethod");
        builder.allowBlockingCallsInside("com.linecorp.armeria.client.ClientFactory", "ofDefault");
        builder.allowBlockingCallsInside("io.envoyproxy.controlplane.cache.SimpleCache", "createWatch");

        // prints the exception which makes it easier to debug issues
        builder.blockingMethodCallback(this::writeBlockingMethod);
    }

    void writeBlockingMethod(BlockingMethod m) {
        ps.println(Thread.currentThread());
        new Exception(m.toString()).printStackTrace(ps);
        ps.flush();
    }
}
