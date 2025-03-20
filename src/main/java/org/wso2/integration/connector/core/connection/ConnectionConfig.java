/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.integration.connector.core.connection;

import java.util.HashMap;
import java.util.Map;

/**
 * Connection Config
 */
public abstract class ConnectionConfig {

    private Map<String, Object> parameters = new HashMap<>();

    public void addParameter(String key, Object value) {
        parameters.put(key, value);
    }

    public Object lookupParameter(String key) {
        return parameters.get(key);
    }

    public Object removeParameter(String key) {
        return parameters.remove(key);
    }
}
