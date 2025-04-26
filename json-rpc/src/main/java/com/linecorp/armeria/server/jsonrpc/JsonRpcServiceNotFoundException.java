package com.linecorp.armeria.server.jsonrpc;

public class JsonRpcServiceNotFoundException extends Exception {

    private final String lookupPath;

    public JsonRpcServiceNotFoundException(String message,
                                       String lookupPath) {
        super(message);
        this.lookupPath = lookupPath;
    }

    public String getLookupPath() {
        return lookupPath;
    }
}