package com.linecorp.armeria.client.endpoint.zookeeper;

import java.util.List;

import com.linecorp.armeria.client.Endpoint;

/**
 * Created by wangjunfei on 11/3/16.
 */
@FunctionalInterface
public interface NodeValueConverter {
    List<Endpoint> convert(byte[] zNodeValue) throws java.lang.IllegalArgumentException;
}
