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

package com.linecorp.armeria.internal.server.tomcat;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.UpgradeProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;

/**
 * A {@link ProtocolHandler} for Tomcat 8.5 and above.
 * Do not use; loaded and instantiated by Tomcat via reflection.
 */
public final class Tomcat90ProtocolHandler implements ProtocolHandler {

    private static final AtomicInteger nextId = new AtomicInteger();

    private final int id = nextId.getAndIncrement();
    @Nullable
    private Adapter adapter;

    @Override
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    @Nullable
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

    /**
     * Accessed by {@link Connector} via reflection.
     */
    public int getPort() {
       return 0;
    }

    @Nullable
    @Override
    public Executor getExecutor() {
        // Doesn't seem to be used.
        return null;
    }

    /**
     * Not available in Tomcat 8.5.
     */
    @SuppressWarnings("override")
    public void setExecutor(Executor executor) {}

    /**
     * Not available in Tomcat 8.5.
     */
    @Nullable
    @SuppressWarnings("override")
    public ScheduledExecutorService getUtilityExecutor() {
        // Doesn't seem to be used.
        return null;
    }

    /**
     * Not available in Tomcat 8.5.
     */
    @SuppressWarnings("override")
    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor) {}

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
    public void closeServerSocketGraceful() {}

    @Override
    public boolean isAprRequired() {
        return false;
    }

    @Override
    public boolean isSendfileSupported() {
        return false;
    }

    @Override
    public void addSslHostConfig(SSLHostConfig sslHostConfig) {}

    @Override
    @SuppressWarnings("ZeroLengthArrayAllocation")
    public SSLHostConfig[] findSslHostConfigs() {
        return new SSLHostConfig[0];
    }

    @Override
    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol) {}

    @Override
    @SuppressWarnings("ZeroLengthArrayAllocation")
    public UpgradeProtocol[] findUpgradeProtocols() {
        return new UpgradeProtocol[0];
    }
}
