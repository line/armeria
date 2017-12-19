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

package com.linecorp.armeria.server.grpc.interop;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.util.concurrent.Futures;

import com.linecorp.armeria.server.Server;

import io.grpc.Status;
import io.grpc.internal.InternalServer;
import io.grpc.internal.LogId;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.TransportTracer.Stats;

/**
 * Wraps an armeria server so gRPC interop tests can operate it.
 */
public class ArmeriaGrpcServer implements InternalServer {

    private final Server armeriaServer;

    private ScheduledExecutorService scheduler;
    private CompletableFuture<Void> shutdownFuture;

    public ArmeriaGrpcServer(Server armeriaServer) {
        this.armeriaServer = armeriaServer;
    }

    @Override
    public void start(ServerListener listener) throws IOException {
        try {
            armeriaServer.start().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        listener.transportCreated(new ServerTransport() {
            @Override
            public void shutdown() {
                armeriaServer.stop();
            }

            @Override
            public void shutdownNow(Status reason) {
                armeriaServer.stop();
            }

            @Override
            public ScheduledExecutorService getScheduledExecutorService() {
                return scheduler;
            }

            @Override
            public Future<Stats> getTransportStats() {
                return Futures.immediateFuture(null);
            }

            @Override
            public LogId getLogId() {
                return null;
            }
        });
    }

    @Override
    public int getPort() {
        return armeriaServer.activePort().get().localAddress().getPort();
    }

    @Override
    public void shutdown() {
        armeriaServer.stop();
        scheduler.shutdown();
    }
}
