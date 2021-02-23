package com.linecorp.armeria.server;

@FunctionalInterface
public interface ReconfigurableServer {
    ServerBuilder reconfigure(ServerBuilder sb);
}
