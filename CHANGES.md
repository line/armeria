# Changes

## Issue
- Issue: #4997
- Title: Default AuthFailureHandler for basic access authentication

## Problem
The default `AuthFailureHandler` in `AuthServiceBuilder` returned a `401 Unauthorized` response without the `WWW-Authenticate` header. This meant browsers would not prompt users for login credentials when basic access authentication was configured.

## Changes Made
- Added a `basicAuthFailureHandler` that includes `WWW-Authenticate: Basic realm="Accessing to the ..."` header.
- Added `basicAuthAdded` flag to track if `addBasicAuth()` was called.
- Added `failureHandlerWasSet` flag to track if `onFailure()` was called.
- `addBasicAuth()` sets `basicAuthAdded = true`.
- `onFailure()` sets `failureHandlerWasSet = true`.
- `build()` selects `basicAuthFailureHandler` only when `basicAuthAdded == true` AND `failureHandlerWasSet == false`.
- Preserves the original `failureHandler` for: OAuth1a, OAuth2, custom authorizers, and explicit `onFailure()` calls.

## Implementation Details
File changed: `core/src/main/java/com/linecorp/armeria/server/auth/AuthServiceBuilder.java`.

Test added: `core/src/test/java/com/linecorp/armeria/server/auth/AuthServiceTest.java`
- Added endpoint `/basic-on-failure` with `addBasicAuth` + explicit `onFailure`.
- Added `explicitOnFailureNotReplacedByBasicAuth()` regression test.

## Testing
- Existing `testBasicAuth` verifies `401` responses for failed auth.
- `explicitOnFailureNotReplacedByBasicAuth` verifies custom handler is preserved when `addBasicAuth` is used with `onFailure()`.

## Result
- When basic access authentication is configured via `addBasicAuth()` without an explicit `onFailure()`, failed authorization responses now include `WWW-Authenticate: Basic realm="Accessing to the ..."` header, allowing browsers to prompt users for credentials.
- Other authentication schemes (OAuth1a, OAuth2, custom) retain the original behavior with no `WWW-Authenticate` header.
- Explicit `onFailure()` handlers are never replaced.
