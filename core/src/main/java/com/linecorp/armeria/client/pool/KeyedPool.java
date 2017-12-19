/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client.pool;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

interface KeyedPool<K, V> extends AutoCloseable {
    Future<V> acquire(K key);

    Future<V> acquire(K key, Promise<V> promise);

    Future<Void> release(K key, V value);

    Future<Void> release(K key, V value, Promise<Void> promise);

    @Override
    void close();
}
