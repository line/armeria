package com.linecorp.armeria.server.jsonrpc;

public class JsonRpcServiceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 5996593317006754659L;
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