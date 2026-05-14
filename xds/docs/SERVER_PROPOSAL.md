# Server-Side xDS: Public API Changes

This document summarizes the public API additions and modifications in `core` and `xds`
required for server-side xDS support. All new APIs are annotated `@UnstableApi`.

---

## 1. How It Fits Together

```
Server.builder()
    .addPlugin(new XdsServerPlugin(bootstrap, "listener", 8080))  -- [ServerPlugin, XdsServerPlugin]
    .service("/api", myService)
    .build();                                                 -- plugin.install(sb) called here
```

```
Connection arrives
       |
       v
ConnectionAcceptor.accept(ctx)          -- [ConnectionAcceptor, ConnectionContext]
       |                                   match filter chain, store on ConnectionContext
       v
ServerTlsProvider.serverTlsSpec(ctx)    -- [ServerTlsProvider, ServerTlsSpec]
       |                                   read matched chain's ServerTlsSpec
       v
TLS handshake
       |
       v
XdsRootDecorator.serve(ctx, req)        -- [ServiceRequestContext.connectionContext()]
       |                                   read matched chain's decorator, delegate
       v
User service
```

### Step 1: Plugin registration

`ServerBuilder.addPlugin(ServerPlugin)` registers the plugin. During `Server` construction,
`plugin.install(sb)` is called, allowing the plugin to register ports, TLS providers,
decorators, and connection acceptors in a single call. Plugins are re-installed on
`Server.reconfigure()` and closed on server stop.

### Step 2: Connection acceptance

When a connection arrives, `ConnectionAcceptor.accept(ConnectionContext)` runs **before**
TLS negotiation. The `ConnectionContext` exposes ClientHello-derived properties (SNI hostname,
ALPN protocols) plus local/remote addresses and attribute storage. The xDS acceptor matches
the connection to a filter chain using `destination_port`, `transport_protocol`,
`server_names`, and `application_protocols`, then stores the matched chain as a
`ConnectionContext` attribute.

### Step 3: TLS selection

`ServerTlsProvider.serverTlsSpec(ConnectionContext)` resolves TLS configuration from the
connection context. Unlike `TlsProvider` (which resolves by hostname alone), it has access
to the full connection context including attributes set by the acceptor. The xDS provider
reads the matched filter chain's `ServerTlsSpec` from the attribute.

### Step 4: Request decoration

At request time, `ServiceRequestContext.connectionContext()` gives the decorator access to
the connection-level attributes. The xDS root decorator reads the matched filter chain and
delegates to its per-filter-chain decorator before reaching the user service.

---

## 2. New Core APIs

### 2.1 `ServerPlugin`

```java
package com.linecorp.armeria.server;

public interface ServerPlugin extends SafeCloseable {
    void install(ServerBuilder sb);
}
```

- `install()` is called during `Server` construction and during `Server.reconfigure()`.
- `close()` is called when the `Server` stops.
- Plugins registered at construction time are automatically re-installed on reconfigure.

### 2.2 `ConnectionAcceptor`

```java
package com.linecorp.armeria.server;

@FunctionalInterface
public interface ConnectionAcceptor {
    boolean accept(ConnectionContext ctx);
}
```

### 2.3 `ConnectionContext`

```java
package com.linecorp.armeria.server;

public final class ConnectionContext {
    public SessionProtocol sessionProtocol();
    public String sniHostname();                     // empty string if no SNI
    @Nullable public List<String> alpnProtocols();   // null if no ALPN
    public InetSocketAddress localAddress();
    public InetSocketAddress remoteAddress();
    @Nullable public <T> T attr(AttributeKey<T> key);
    public <T> void setAttr(AttributeKey<T> key, @Nullable T value);
}
```

### 2.4 `ServerTlsProvider`

```java
package com.linecorp.armeria.server;

@FunctionalInterface
public interface ServerTlsProvider {
    @Nullable ServerTlsSpec serverTlsSpec(ConnectionContext ctx);
    default int order() { return 0; }
}
```

- Multiple providers form a chain; first non-null `ServerTlsSpec` wins.
- `order()` controls evaluation order (lower = first). Equal-order providers retain
  registration order.
- If all providers return `null`, falls back to `TlsProvider` or static VirtualHost TLS.
- `ServerTlsSpec.builder()` and `ServerTlsSpecBuilder` are now public (were package-private).

---

## 3. Modified Core APIs

### 3.1 `ServerBuilder`

New methods:

| Method | Description |
|--------|-------------|
| `addPlugin(ServerPlugin)` | Registers a plugin installed at build time and on reconfigure |
| `connectionAcceptor(ConnectionAcceptor)` | Sets the per-connection acceptor (before TLS) |
| `tlsProvider(ServerTlsProvider)` | Adds a `ServerTlsProvider` to the chain (new overload) |

Existing `tlsProvider(TlsProvider)` and `tlsProvider(TlsProvider, ServerTlsConfig)` remain
unchanged. The new `ServerTlsProvider` overload can coexist -- `ServerTlsProvider`s are
evaluated first, then the `TlsProvider`/static TLS acts as fallback.

### 3.2 `ServiceRequestContext`

New method:
- `connectionContext()` -- returns the `ConnectionContext` for the connection handling
  this request, giving request-time access to connection-level attributes.

### 3.3 `Server`

- `build()` now calls `plugin.install(sb)` for each registered plugin before building config.
- `reconfigure()` re-installs all plugins registered at construction time.
- Server stop calls `plugin.close()` for each plugin.

---

## 4. New xDS APIs

### 4.1 `XdsServerPlugin`

```java
package com.linecorp.armeria.xds;

public final class XdsServerPlugin implements ServerPlugin {
    public XdsServerPlugin(XdsBootstrap bootstrap, String listenerName);
    public XdsServerPlugin(XdsBootstrap bootstrap, String listenerName, int port);
    public XdsServerPlugin(XdsBootstrap bootstrap, String listenerName, ServerPort serverPort);
}
```

The primary entry point for server-side xDS. `install()` blocks until the first xDS snapshot
is resolved, then registers:
1. **Port** -- the server port (HTTP + HTTPS)
2. **ConnectionAcceptor** -- matches connections to xDS filter chains
3. **ServerTlsProvider** (order=-1) -- resolves `ServerTlsSpec` from the matched chain
4. **Root decorator** -- delegates to per-filter-chain decorators at request time

### 4.2 `ServerSnapshotWatcher` (package-private)

A package-private `SnapshotWatcher<ListenerSnapshot>` created internally by
`XdsServerPlugin` -- not part of the public API.

Key responsibilities:
- Eagerly builds `HttpService` decorator instances when a new snapshot arrives
- Performs filter chain matching via `match(ConnectionContext)`
- Tracks the current `ServiceConfig` and invokes `serviceAdded` on decorators
  (both for existing decorators when `ServiceConfig` arrives, and for new decorators
  when a snapshot update arrives)
