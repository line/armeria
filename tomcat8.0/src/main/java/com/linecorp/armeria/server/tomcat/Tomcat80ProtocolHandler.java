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

package com.linecorp.armeria.server.tomcat;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;

/**
 * A {@link ProtocolHandler} for Tomcat 8.0 and below.
 * Do not use; loaded and instantiated by Tomcat via reflection.
 */
public final class Tomcat80ProtocolHandler implements ProtocolHandler {

    private static final AtomicInteger nextId = new AtomicInteger();

    private final int id = nextId.getAndIncrement();
    private Adapter adapter;

    @Override
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    /**
     * Accessed by {@link Connector} via reflection.
     */
    public int getNameIndex() {
        return id;
    }

    @Override
    public Executor getExecutor() {
        // Doesn't seem to be used.
        return null;
    }

    @Override
    public void init() throws Exception {}

    @Override
    public void start() throws Exception {}

    @Override
    public void pause() throws Exception {}

    @Override
    public void resume() throws Exception {}

    @Override
    public void stop() throws Exception {}

    @Override
    public void destroy() {}

    @Override
    public boolean isAprRequired() {
        return false;
    }

    @Override
    public boolean isCometSupported() {
        return false;
    }

    @Override
    public boolean isCometTimeoutSupported() {
        return false;
    }

    @Override
    public boolean isSendfileSupported() {
        return false;
    }
}
