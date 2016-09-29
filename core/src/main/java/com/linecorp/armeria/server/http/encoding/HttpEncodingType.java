package com.linecorp.armeria.server.http.encoding;

/**
 * A type of HTTP encoding, which is usually included in accept-encoding and content-encoding headers.
 */
enum HttpEncodingType {
    GZIP,
    DEFLATE;
}
