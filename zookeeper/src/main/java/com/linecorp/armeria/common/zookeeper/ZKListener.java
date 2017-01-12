package com.linecorp.armeria.common.zookeeper;

import java.util.Map;

public interface ZKListener {
    void nodeChildChange(Map<String, String> newChildrenValue);

    void nodeValueChange(String newValue);

    void connected();
}
