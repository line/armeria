/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.zookeeper;

import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * Zookeeper related constant values.
 */
public final class Constants {
    public static final int DEFAULT_CONNECT_TIMEOUT = 1000;
    public static final int DEFAULT_SESSION_TIMEOUT = 10000;

    public static final ExponentialBackoffRetry
            DEFAULT_RETRY_POLICY = new ExponentialBackoffRetry(DEFAULT_CONNECT_TIMEOUT, 3);

    private Constants() {}
}
