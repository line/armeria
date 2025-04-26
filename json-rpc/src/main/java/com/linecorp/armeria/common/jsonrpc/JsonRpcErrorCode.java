package com.linecorp.armeria.common.jsonrpc;

public enum JsonRpcErrorCode {


    INVALID_REQUEST(-32600, "Invalid Request"),

    METHOD_NOT_FOUND(-32601, "Method not found"),

    INVALID_PARAMS(-32602, "Invalid params"),

    INTERNAL_ERROR(-32603, "Internal error"),

    PARSE_ERROR(-32700, "Parse error");


    private final int code;

    private final String message;

    JsonRpcErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
