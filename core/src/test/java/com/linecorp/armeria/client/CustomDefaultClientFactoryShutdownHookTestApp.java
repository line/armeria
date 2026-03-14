/*
 * Copyright 2026 LINE Corporation
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

import com.linecorp.armeria.common.ShutdownHookFlagsProvider;

/**
 * For checking the behavior of the JVM shutdown hook for a custom default {@link ClientFactory}.
 * This app must output the following text:
 * <pre>{@code
 * - defaultClientFactory: com.linecorp.armeria.common.ShutdownHookTestClientFactory
 * - ShutdownHookTestClientFactory closed
 * }</pre>
 */
@SuppressWarnings({ "checkstyle:HideUtilityClassConstructor", "checkstyle:UncommentedMain" })
public final class CustomDefaultClientFactoryShutdownHookTestApp {

    public static void main(String[] args) {
        System.setProperty(ShutdownHookFlagsProvider.ENABLE_PROPERTY, "true");
        final ClientFactory clientFactory = ClientFactory.ofDefault();
        System.out.println("defaultClientFactory: " + clientFactory.getClass().getName());
        System.exit(0);
    }
}
