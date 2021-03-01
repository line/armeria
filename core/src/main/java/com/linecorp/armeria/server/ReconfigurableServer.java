package com.linecorp.armeria.server;

@FunctionalInterface
public interface ReconfigurableServer {
    void reconfigure(ServerBuilder sb);
}
