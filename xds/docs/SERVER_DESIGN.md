# Server-Side xDS Integration

**Status**: Design
**Author**: @jrhee17

## Summary

Enable Armeria servers to consume xDS configuration from control planes (Istio, CentralDogma,
etc.) to dynamically manage TLS and cross-cutting policies (RBAC, authentication, telemetry)
on the user's existing services — without modifying their routing or service definitions.

## Background

Armeria's existing xDS support is client-side: `XdsBootstrap` fetches
Listener/Route/Cluster/Endpoint resources and feeds them into `XdsEndpointGroup` for service
discovery and load balancing.

Server-side xDS is a different use case. Instead of discovering upstream services, the Armeria
server itself becomes an xDS-managed workload. A control plane pushes configuration that governs
how the server accepts connections, negotiates TLS, and enforces policies like RBAC — while the
user's services and routing remain entirely under their control.

## Goals

- Dynamic TLS configuration via SDS (cert provisioning, rotation, client auth mode).
- Dynamic http_filters (RBAC, authn, telemetry) applied as server decorators.
- Per-connection policy via filter chain selection (mTLS vs plaintext, per-SNI policy).
- Compatibility with Istio's inbound listener model.
- User control over which xDS filters are honored.

## Non-Goals

- Dynamic port management. xDS listeners are matched to ports the user already configured.
  Ports are not dynamically added or removed.
- Server-side routing via xDS. The user's VirtualHosts/Routes/services are never generated,
  translated, or modified by xDS.
- Network filter support beyond HCM. Only `HttpConnectionManager` is supported as a network
  filter; raw TCP proxying and custom network filters are out of scope.

## Design

## Core Model

In Envoy, the "router" is the terminal http_filter that evaluates VirtualHost/Route matching
and dispatches to upstream clusters. In Armeria's server-side model, the "router" is the user's
entire server configuration — VirtualHosts, Routes, services, and service-level decorators. xDS
manages the layers above it: TLS and http_filters.

**Diagram 1: xDS Listener structure**

### Schema

```
Listener (name: "inbound_0.0.0.0_8080")
 └── FilterChain(s)
      ├── FilterChainMatch
      │    ├── transport_protocol      (e.g. "tls", "raw_buffer")
      │    ├── application_protocols   (e.g. ["istio-peer-exchange", "istio"])
      │    ├── server_names            (e.g. ["api.example.com"])
      │    ├── prefix_ranges           (source/dest IP matching)
      │    └── source_ports
      ├── transport_socket
      │    └── DownstreamTlsContext    (SDS certs, client auth mode)
      └── HttpConnectionManager
           ├── http_filters
           │    ├── envoy.filters.http.rbac
           │    ├── envoy.filters.http.jwt_authn
           │    └── envoy.filters.http.router
           └── route_config            (simple, e.g. catch-all "*")
```

###  Sample listener

```
xDS Listener (port 8080)
 ├── FilterChain: mTLS
 │    ├── match: transport_protocol="tls", alpn=["istio"]
 │    ├── TLS: SDS certs + REQUIRE_CLIENT_CERT
 │    └── http_filters: [RBAC, authn, router]
 │         ↓
 ├── FilterChain: plaintext
 │    ├── match: transport_protocol="raw_buffer"
 │    └── http_filters: [router]
 │         ↓
 └── [router] ← user's Armeria server
      ├── VirtualHost("api.example.com")
      │    ├── Route("/api/users")    →  userService
      │    ├── Route("/api/admin")    →  adminService
      │    └── decorator(LoggingService.newDecorator())
      ├── VirtualHost("grpc.example.com")
      │    └── Route("/grpc")         →  grpcService
      └── defaultVirtualHost()
           └── Route("/health")       →  healthCheckService
```
**Diagram 2: Armeria integration**

| Envoy concept       | Armeria equivalent                          |
|----------------------|---------------------------------------------|
| Listener             | `ServerPort`                                |
| FilterChainMatch     | `ConnectionAcceptor` / `ConnectionContext`   |
| transport_socket/SDS | `ServerTlsProvider` → `ServerTlsSpec`       |
| http_filters         | Per-filter-chain decorator (`HttpService`)  |
| Router filter        | User's entire Armeria server config         |

## Design Choices

### xDS route_config is not used for service dispatch

The user's own VirtualHost/Route/service configuration is the routing layer. The xDS
route_config pushed by the control plane is expected to be simple.

### No per-route filter config overrides

In Envoy, each http_filter's behavior can be customized per-route via
`typed_per_filter_config` on VirtualHost/Route entries in the xDS `RouteConfiguration`.
For example, an RBAC filter might have a strict policy on one route and a permissive
policy on another.

Since the router is user code (no xDS `RouteConfiguration`), there are no xDS routes to
attach `typed_per_filter_config` to. The http_filters config is therefore fixed for the
entire filter chain — every request on a connection that matched a given filter chain
sees the same filter config.

### `serviceAdded` Lifecycle

xDS decorators need `serviceAdded(ServiceConfig)` to access server-wide state (MeterRegistry,
Server reference, etc.). When a new xDS snapshot loads, `serviceAdded` is called on the
decorator with an arbitrary ServiceConfig.

This matches the existing `RouteDecoratingService` behavior — route-level decorators in
Armeria already receive `serviceAdded` with an arbitrary ServiceConfig (whichever is iterated
first), and decorators only use it to access server-wide state (`cfg.server()`,
`cfg.server().meterRegistry()`), not per-route fields.

The decorator is **not** created per-ServiceConfig or per-connection. One decorator instance
per filter chain, shared across all connections that match that chain.

### Snapshot Consistency

The xDS snapshot (TLS config + decorator) is captured onto the channel at connection time.
New connections pick up the latest snapshot; existing connections continue using their
pinned snapshot. No explicit drain timer is needed — Armeria's connection lifecycle handles it.

### Decorator Ordering

xDS decorators are outermost (run first), so that RBAC rejects before user decorators or
services see the request:

```
[xDS RBAC] → [xDS authn] → [user's service decorators] → service
```

### Connection-Time Binding

The matched filter chain (TLS config + decorator) is determined once at connection
establishment and does not change for the lifetime of that connection, even if the xDS
snapshot updates. This matches Envoy's behavior and ensures TLS and policy are consistent
— both are bound at the same point. New connections pick up the latest snapshot; existing
connections continue with the policy they were accepted with.

### Port Selection

The port passed to `XdsServerPlugin` is the definitive port to apply xDS policies to.
Only connections on that port are subject to filter chain matching; connections on other
server ports are unaffected. A single xDS listener is used regardless of the listener port.

This decouples the bound port from the listener's `address.port`. In Istio's iptables
reroute mode, traffic originally destined for an application port (e.g. 8080) is
redirected through a single inbound listener (e.g. `0.0.0.0_15006`) whose address port
does not match the application port. The plugin's port parameter determines which
Armeria `ServerPort` is xDS-managed, independent of the listener's address.

In future, we could support dynamically binding ports to listeners via wildcard listener
queries, matching standalone Envoy behavior. The API migration would be minimal —
`XdsServerPlugin.of(XdsBootstrap)` would be all that is needed.
