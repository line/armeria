package com.linecorp.armeria.client.endpoint.zookeeper;

import java.util.ArrayList;
import java.util.List;

import com.linecorp.armeria.client.Endpoint;

/**
 * A default zNode value to endpoint list converter ,assuming node value is a CSV string
 * like <code>"localhost:8001:5,localhost:8002,192.168.1.2:80:3"</code>,each segment consist of
 * <code>"host:portNumber:weight"</code>
 *
 * <p><b>Note:</b>
 * <ul>
 *   <li> <b>do not</b> include schema name</li>
 *   <li> you can omit port number and weight , so the default value will be 0 for port number
 *   and 1000 for weight </li>
 *   <li> must provide the port number if you specified a weight</li>
 *  </ul>
 */
public class DefaultNodeValueConverter implements NodeValueConverter {
    private String endPointGroupStr;

    @Override
    public List<Endpoint> convert(byte[] zNodeValue) throws IllegalArgumentException {
        this.endPointGroupStr = new String(zNodeValue);
        List<Endpoint> ret = new ArrayList<>();
        String[] segmentAry = this.endPointGroupStr.split(",");
        for (String seg : segmentAry) {
            String[] token = seg.split(":");
            switch (token.length) {
                case 1: //host
                case 2: //host and port
                    ret.add(Endpoint.of(seg));
                    break;
                case 3: //host,port,weight
                    int weight = 0;
                    int port = 0;
                    try {
                        port = Integer.parseInt(token[1]);
                        weight = Integer.parseInt(token[2]);
                    } catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException(endPointGroupStr);
                    }
                    ret.add(Endpoint.of(token[0], port, weight));
            }
        }
        if (ret.size() == 0) {
            throw new IllegalArgumentException("no endpoint could be parsed .");
        }
        return ret;
    }
}
