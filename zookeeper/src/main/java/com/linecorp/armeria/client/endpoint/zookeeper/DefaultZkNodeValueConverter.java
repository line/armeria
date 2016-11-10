/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.endpoint.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;

import com.linecorp.armeria.client.Endpoint;

/**
 * A default zNode value to endpoint list converter , assuming zNode value is a CSV string
 * like {@code "localhost:8001:5 , localhost:8002 , 192.168.1.2:80:3"} , whose each segment consists of
 * {@code "host:portNumber:weight"}
 * <h3>Note:</h3>
 * <ul>
 *   <li><b>do not</b> include schema name</li>
 *   <li>you can omit port number and weight , so the default value will be 0 for port number
 *   and 1000 for weight </li>
 *   <li>must provide the port number if you specified a weight</li>
 *  </ul>
 */
public class DefaultZkNodeValueConverter implements ZkNodeValueConverter {

    private final Pattern segmentsPattern = Pattern.compile("[^,\\s][^\\,]*[^,\\s]*");

    @Override
    public List<Endpoint> convert(byte[] data) {
        List<Endpoint> endpointsList = new ArrayList<>();
        String valueString = new String(data, Charsets.UTF_8);
        for (String seg : filter(valueString)) {
            String[] token = seg.split(":");
            switch (token.length) {
                case 1: //host
                case 2: //host and port
                    endpointsList.add(Endpoint.of(seg));
                    break;
                case 3: //host , port , weight
                    int weight;
                    int port;
                    try {
                        port = Integer.parseInt(token[1]);
                        weight = Integer.parseInt(token[2]);
                    } catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException(
                                "can not parse to int " + nfe.getMessage() + ". " +
                                "Invalid endpoint group string:" + valueString);
                    }
                    endpointsList.add(Endpoint.of(token[0], port, weight));
                    break;
                default: //unknown
                    throw new IllegalArgumentException(
                            "Invalid endpoint group string:  " + seg + '/' + valueString);

            }
        }
        if (endpointsList.isEmpty()) {
            throw new IllegalArgumentException("ZNode dose not contain any endpoints.");
        }
        return endpointsList;
    }

    /**
     * Filters out valid segments.
     * @param testString zNode string value
     * @return valid segments list
     */
    private List<String> filter(String testString) {
        Matcher matcher = segmentsPattern.matcher(testString);
        ArrayList<String> segments = new ArrayList<>();
        while (matcher.find()) {
            segments.add(matcher.group());
        }
        return segments;
    }

}
