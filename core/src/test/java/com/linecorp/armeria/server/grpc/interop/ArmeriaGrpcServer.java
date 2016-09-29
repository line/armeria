/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc.interop;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.util.concurrent.UncheckedExecutionException;

import com.linecorp.armeria.server.ServerListener;

import io.grpc.Server;

/**
 * Wraps an armeria server so GRPC interop tests can operate it.
 */
public class ArmeriaGrpcServer extends Server implements ServerListener {

    private final com.linecorp.armeria.server.Server armeriaServer;

    private boolean isShutdown;
    private boolean isTerminated;
    private CompletableFuture<Void> shutdownFuture;

    public ArmeriaGrpcServer(com.linecorp.armeria.server.Server armeriaServer) {
        this.armeriaServer = armeriaServer;
    }

    @Override
    public Server start() throws IOException {
        try {
            armeriaServer.start().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public int getPort() {
        return armeriaServer.activePort().get().localAddress().getPort();
    }

    @Override
    public Server shutdown() {
        armeriaServer.stop();
        return this;
    }

    @Override
    public Server shutdownNow() {
        shutdownFuture = armeriaServer.stop();
        return this;
    }

    @Override
    public boolean isShutdown() {
        return isShutdown;
    }

    @Override
    public boolean isTerminated() {
        return isTerminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            shutdownFuture.get(timeout, unit);
        } catch (ExecutionException e) {
            throw new UncheckedExecutionException(e);
        } catch (TimeoutException e) {
            // Ignore.
        }
        return isTerminated;
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        try {
            shutdownFuture.get();
        } catch (ExecutionException e) {
            throw new UncheckedExecutionException(e);
        }
    }

    @Override
    public void serverStarting(com.linecorp.armeria.server.Server server) throws Exception {

    }

    @Override
    public void serverStarted(com.linecorp.armeria.server.Server server) throws Exception {

    }

    @Override
    public void serverStopping(com.linecorp.armeria.server.Server server) throws Exception {
        isShutdown = true;
    }

    @Override
    public void serverStopped(com.linecorp.armeria.server.Server server) throws Exception {
        isTerminated = true;
    }
}
