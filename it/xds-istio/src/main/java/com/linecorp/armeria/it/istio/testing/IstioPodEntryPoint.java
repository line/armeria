/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.it.istio.testing;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;

/**
 * Entry point for the shaded workload jar. Runs a single JUnit test method by class and method name,
 * then exits with code 0 on success or 1 on failure.
 *
 * <p>Usage: {@code java -jar workload.jar --class <className> --method <methodName>}
 */
public final class IstioPodEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(IstioPodEntryPoint.class);

    public static void main(String[] args) throws Exception {
        String className = null;
        String methodName = null;
        String serverFactory = null;
        int serverPort = -1;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--class".equals(args[i])) {
                className = args[i + 1];
            } else if ("--method".equals(args[i])) {
                methodName = args[i + 1];
            } else if ("--server-factory".equals(args[i])) {
                serverFactory = args[i + 1];
            } else if ("--port".equals(args[i])) {
                serverPort = Integer.parseInt(args[i + 1]);
            }
        }

        if (serverFactory != null) {
            if (serverPort < 0) {
                logger.error("--port is required with --server-factory");
                System.exit(2);
            }
            final ServerConfigurator configurator =
                    (ServerConfigurator) Class.forName(serverFactory)
                                              .getDeclaredConstructor()
                                              .newInstance();
            final ServerBuilder sb = Server.builder().http(serverPort);
            configurator.reconfigure(sb);
            sb.build().start().join();
            Thread.currentThread().join();
            return;
        }

        if (className == null || methodName == null) {
            logger.error("Usage: IstioPodEntryPoint --class <className> --method <methodName>");
            System.exit(2);
        }

        final LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectMethod(className, methodName))
                .build();
        final SummaryGeneratingListener listener = new SummaryGeneratingListener();
        final Launcher launcher = LauncherFactory.create();
        launcher.discover(request);
        launcher.execute(request, listener);

        final long failures = listener.getSummary().getFailures().size();
        if (failures > 0) {
            listener.getSummary().getFailures().forEach(f ->
                    logger.error("Test failed", f.getException()));
            System.exit(1);
        }
        System.exit(0);
    }

    private IstioPodEntryPoint() {}
}
