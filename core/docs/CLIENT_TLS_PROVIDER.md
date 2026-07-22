# `ClientTlsProvider` — Unified TLS Resolution for Client Connections

## Problem

### 1. `sessionProtocol` and `clientTlsSpec` can be inconsistent

`ClientRequestContext` exposes two independently mutable fields that must agree:

- `sessionProtocol` — whether the connection is TLS (e.g. `HTTPS`, `H2`) or cleartext (`HTTP`, `H2C`)
- `clientTlsSpec` — the TLS configuration (certificates, ALPN, verification) for the connection

Nothing prevents setting `sessionProtocol = HTTP` with a non-null `clientTlsSpec`, or
`sessionProtocol = HTTPS` with a null spec. TLS resolution is scattered across three
independent sites, each setting one side without checking the other:

| Site | What it sets | When |
|------|-------------|------|
| `RequestOptions.clientTlsSpec()` | `clientTlsSpec` only | Context construction |
| Decorator / preprocessor | `clientTlsSpec` only | Decorator/preprocessor execution |
| `HttpClientDelegate.determineTlsSpec()` | `clientTlsSpec` only | Connection time |

For example, a decorator that sets `clientTlsSpec` on a cleartext request:

```java
WebClient client =
    WebClient.builder("http://example.com")
             .decorator((delegate, ctx, req) -> {
                 // A decorator that enables mTLS for certain endpoints
                 ctx.setClientTlsSpec(mtlsSpec);
                 // ctx.clientTlsSpec() is now non-null, but sessionProtocol is still HTTP
                 // HttpClientDelegate sees HTTP and skips TLS — the spec is silently ignored
                 return delegate.execute(ctx, req);
             })
             .build();
```

### 2. Proxy TLS reused the backend's spec

`ConnectProxyConfig` only had a `boolean useTls` flag. When the proxy connection needed
TLS, it used the same `ClientTlsSpec` resolved for the backend endpoint. This is incorrect —
the proxy is a different host with potentially different certificates, ALPN requirements,
and verification policies.

```java
// The only way to enable proxy TLS:
ProxyConfig.connect(proxyAddr, /* useTls */ true);
// No way to provide proxy-specific certificates or verification
// The proxy TLS handshake reuses whatever was resolved for the backend
```

### 3. SNI was implicit and not overridable

The SNI hostname was derived implicitly from the endpoint host or authority inside
`HttpClientDelegate.acquireConnectionAndExecute0()`, with no way for users or preprocessors
to override it. The derivation logic (authority parsing, IP address exclusion, trailing dot
stripping) was interleaved with connection acquisition code, making it hard to reason about
what hostname would be used for TLS handshakes.

```java
// No API to control SNI. The only way to influence it was to change the endpoint:
ctx.setEndpoint(Endpoint.of("desired-sni-host.com", 443));
// But this also changes the connection target — you can't set SNI independently
```

## Design

### Core invariant

After TLS resolution runs, the context satisfies:

> **`clientTlsSpec != null` if and only if `sessionProtocol.isTls()`**

This invariant is enforced at two levels:

1. **Mutators** — `setClientTlsSpec()` also switches `sessionProtocol` to TLS;
   `clearClientTlsSpec()` also switches to cleartext.

   ```java
   // Setting a TLS spec on a cleartext request also upgrades the protocol:
   WebClient client =
       WebClient.builder("http://example.com")
                .decorator((delegate, ctx, req) -> {
                    ctx.setClientTlsSpec(mtlsSpec);
                    // sessionProtocol is also switched to HTTPS
                    assert ctx.sessionProtocol() == SessionProtocol.HTTPS;
                    return delegate.execute(ctx, req);
                })
                .build();

   // Clearing the TLS spec on a TLS request also downgrades the protocol:
   WebClient client =
       WebClient.builder("https://example.com")
                .decorator((delegate, ctx, req) -> {
                    ctx.clearClientTlsSpec();
                    // sessionProtocol is also switched to HTTP
                    assert ctx.sessionProtocol() == SessionProtocol.HTTP;
                    return delegate.execute(ctx, req);
                })
                .build();
   ```

2. **Resolution** — `ClientUtil.resolveClientTlsSpec()` runs after endpoint selection
   and before decorator execution. It reconciles any inconsistency: clears a stale
   `clientTlsSpec` if the protocol is plaintext, or resolves one via `ClientTlsProvider`
   if the protocol is TLS but no spec exists yet.

### `ClientTlsProvider`

A `@FunctionalInterface` that resolves TLS configuration from the request context:

```java
@UnstableApi
@FunctionalInterface
public interface ClientTlsProvider {
    ClientTlsSpec clientTlsSpec(ClientRequestContext ctx);
}
```

The provider reads everything it needs — session protocol, SNI hostname, endpoint — from
the context. It is set as a `ClientFactoryOption` and called by `ClientUtil.resolveClientTlsSpec()`.

**Implementations:**

| Class | When used |
|-------|-----------|
| `BootstrapClientTlsProvider` | Default — resolves from pre-built `BootstrapSslContexts` by session protocol |
| `TlsProviderAdapter` | When user sets `ClientFactoryBuilder.tlsProvider()` — wraps `TlsProvider` and looks up `keyPair`/`trustedCertificates` by `ctx.sniHostname()` |

### `sniHostname` as a first-class field

SNI hostname is now a two-level field on `DefaultClientRequestContext`:

- **`defaultSniHostname`** — auto-computed whenever `updateEndpoint()` is called.
  For IP-only endpoints, derived from the authority. For domain endpoints, uses
  `endpoint.host()`. Trailing dots are stripped.
- **`sniHostname`** (user override) — set explicitly via `setSniHostname()`.

`ctx.sniHostname()` returns the user override if set, otherwise the default.
This value is used by:
- `TlsProviderAdapter` for `TlsProvider.keyPair(hostname)` / `trustedCertificates(hostname)` lookups
- `HttpClientDelegate` for `endpoint.withHost(sniHostname)` rewrite (sets the Netty
  `remoteAddress` hostname, which Netty uses for SNI in the TLS handshake)

```java
// Override SNI to use a different hostname than the endpoint:
WebClient client =
    WebClient.builder("https://10.0.0.1:8443")
             .decorator((delegate, ctx, req) -> {
                 ctx.setSniHostname("internal-service.example.com");
                 return delegate.execute(ctx, req);
             })
             .build();
```

### Proxy TLS decoupling

`ConnectProxyConfig` now accepts a `@Nullable ClientTlsSpec` instead of `boolean useTls`.
The old `boolean` API is preserved for backward compatibility (internally converts to
a default `ClientTlsSpec` with HTTP/1.1 ALPN). New factory methods on `ProxyConfig`
accept `ClientTlsSpec` directly, allowing users to provide custom certificates and
verification policies for the proxy hop.

```java
// Provide proxy-specific TLS configuration, independent of the backend:
ClientTlsSpec proxyTlsSpec =
    ClientTlsSpec.builder()
                 .trustedCertificates(proxyCaCert)
                 .tlsKeyPair(proxyClientKeyPair)
                 .build();
// The hostname of proxyAddr is used for SNI in the proxy TLS handshake.
ProxyConfig.connect(proxyAddr, proxyTlsSpec);
```

## Before / After

### TLS resolution flow

**Before:**
```
RequestOptions.clientTlsSpec  →  stored on ctx (no protocol check)
      ↓
Preprocessor may call setSessionProtocol()  (no TLS spec check)
      ↓
Decorators may set clientTlsSpec  (no protocol check)
      ↓
HttpClientDelegate.determineTlsSpec()
  - checks ctx.clientTlsSpec() (request-level)
  - falls back to TlsProvider hostname lookup
  - falls back to BootstrapSslContexts
  → returns ClientTlsSpec (protocol not updated)
```

**After:**
```
RequestOptions.clientTlsSpec  →  stored on ctx
      ↓
Preprocessor may call setSessionProtocol() (before init only)
                    or setClientTlsSpec()  (also updates protocol)
                    or clearClientTlsSpec() (also updates protocol)
      ↓
ClientUtil.resolveClientTlsSpec()  ← single enforcement point
  - if protocol is cleartext and spec exists → clear it
  - if protocol is TLS and spec is missing  → resolve via ClientTlsProvider
  - fills empty ALPN from session protocol
  → ctx.clientTlsSpec and ctx.sessionProtocol are consistent
      ↓
Decorators run (spec already resolved)
      ↓
HttpClientDelegate reads ctx.clientTlsSpec() directly (no resolution logic)
```

### SNI hostname

**Before:**
```
HttpClientDelegate.acquireConnectionAndExecute0():
  if (protocol.isTls() && endpoint.isIpAddrOnly()) {
      serverName = authorityToServerName(ctx.authority());  // buried in connection code
      if (serverName != null) endpoint = endpoint.withHost(serverName);
  }
  endpoint = endpoint.withoutTrailingDot();
```

**After:**
```
DefaultClientRequestContext.updateEndpoint():
  defaultSniHostname = computeDefaultSniHostname(endpoint);  // precomputed

ctx.sniHostname():
  return sniHostname != null ? sniHostname : defaultSniHostname;  // overridable

HttpClientDelegate.acquireConnectionAndExecute0():
  sniHostname = ctx.sniHostname();            // just read the precomputed value
  if (sniHostname != null) endpoint = endpoint.withHost(sniHostname);
  endpoint = endpoint.withoutTrailingDot();
```

### Proxy TLS

**Before:**
```java
ProxyConfig.connect(addr, useTls);  // boolean only
// Proxy connection uses backend's ClientTlsSpec
```

**After:**
```java
ProxyConfig.connect(addr, useTls);                 // preserved for compatibility
ProxyConfig.connect(addr, clientTlsSpec);           // new: custom proxy TLS
ProxyConfig.connect(addr, user, pass, clientTlsSpec); // new: with credentials
// Proxy connection uses its own ClientTlsSpec, independent of backend
```

## Breaking changes

### `setClientTlsSpec()` now also switches `sessionProtocol`

Previously, `setClientTlsSpec()` only set the TLS spec without touching the session
protocol. Now it also switches the protocol to the TLS variant (e.g. `HTTP` → `HTTPS`).
Code that called `setClientTlsSpec()` and then separately called `setSessionProtocol()`
to switch to TLS will still work — the protocol is already TLS after `setClientTlsSpec()`.

### SNI hostname determined at context initialization

Previously, the SNI hostname was derived from the endpoint and authority at connection
time in `HttpClientDelegate`, meaning host headers added by decorators were reflected
in the SNI automatically. Now, SNI is precomputed at context initialization (when the
endpoint is selected). Host headers or authority changes made in decorators are no longer
automatically reflected as the SNI. Users who need a custom SNI must call
`ctx.setSniHostname()` explicitly.

### Event loop selection may not reflect protocol changes by decorators

The event loop is selected during context initialization based on the `sessionProtocol`
at that time. If a decorator later calls `setClientTlsSpec()` or `clearClientTlsSpec()`
which switches the protocol (e.g. `HTTP` → `HTTPS`), the event loop has already been
chosen and will not be re-selected. Previously, the protocol was not modified by these
methods, so this situation did not arise.

### Proxy TLS uses default spec instead of request TLS spec

Previously, a CONNECT proxy with TLS reused the `ClientTlsSpec` resolved for the
backend endpoint. Now, the proxy connection uses a default TLS spec (HTTP/1.1 ALPN)
unless a proxy-specific `ClientTlsSpec` is provided via `ProxyConfig.connect(addr, clientTlsSpec)`.

## New APIs

### `ClientRequestContext`

- `clearClientTlsSpec()` — clears the TLS spec and switches the protocol to cleartext
- `sniHostname()` — returns the SNI hostname (user override or auto-computed default)
- `setSniHostname(String)` — overrides the SNI hostname

### `ClientTlsProvider`

- `ClientTlsSpec clientTlsSpec(ClientRequestContext ctx)` — resolves TLS configuration for the connection

### `ClientFactoryBuilder`

- `clientTlsProvider(ClientTlsProvider)` — sets a custom TLS provider for the factory

### `ProxyConfig`

- `connect(InetSocketAddress, ClientTlsSpec)` — CONNECT proxy with custom proxy TLS
- `connect(InetSocketAddress, String, String, ClientTlsSpec)` — with credentials
