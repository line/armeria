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

package com.linecorp.armeria.client;

/**
 * For checking the behavior of {@link DefaultClientFactory#closeOnJvmShutdown()}.
 * This app must output the following text:
 * <pre>{@code
 * - ClientFactory has been closed.
 * - After ClientFactory stopped
 * }</pre>
 */
@SuppressWarnings({ "checkstyle:HideUtilityClassConstructor", "checkstyle:UncommentedMain" })
public final class DefaultClientFactoryShutdownTest {

    public static void main(String[] args) {
        final DefaultClientFactory defaultClientFactory = (DefaultClientFactory) ClientFactory.ofDefault();
        defaultClientFactory.closeOnJvmShutdown().thenRun(() -> {
            System.out.println("After ClientFactory stopped");
        });
    }
}
