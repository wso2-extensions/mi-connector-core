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
package org.wso2.integration.connector.core.util;

/**
 * Contains constant values
 */
public class Constants {

    // Connection Pool Parameters
    public static final String MAX_ACTIVE_CONNECTIONS = "maxActiveConnections";
    public static final String MAX_IDLE_CONNECTIONS = "maxIdleConnections";
    public static final String MAX_WAIT_TIME = "maxWaitTime";
    public static final String MAX_EVICTION_TIME = "minEvictionTime";
    public static final String EVICTION_CHECK_INTERVAL = "evictionCheckInterval";
    public static final String EXHAUSTED_ACTION = "exhaustedAction";
    public static final String INIT_CONFIG_KEY = "INIT_CONFIG_KEY";

    public static final String CONTENT_TYPE_TEXT = "text/plain";
    public static final String CONTENT_TYPE_JSON = "application/json";

    public static final String RESPONSE_VARIABLE = "responseVariable";
    public static final String OVERWRITE_BODY = "overwriteBody";
    public static final String CONNECTION_NAME = "name";
    public static final String BASE = "base";
    public static final String REFRESH_TOKEN = "refreshToken";
    public static final String CLIENT_ID = "clientId";
    public static final String CLIENT_SECRET = "clientSecret";
    public static final String TOKEN_ENDPOINT = "tokenEndpoint";
    public static final String DEVELOPER_TOKEN = "developerToken";
    public static final String PROPERTY_BASE = "uri.var.base";
    public static final String PROPERTY_ACCESS_TOKEN = "_ACTIVE_ACCESS_TOKEN_";
    public static final String PROPERTY_ERROR_CODE = "ERROR_CODE";
    public static final String PROPERTY_ERROR_MESSAGE = "ERROR_MESSAGE";
    public static final String GENERAL_ERROR_MSG = "Connector encountered an error: ";

    public static class ErrorCodes {
        public static final String GENERAL_ERROR = "701001";
        public static final String INVALID_CONFIG = "701002";
        public static final String TOKEN_ERROR = "701003";
    }

    public static class OAuth2 {

        public static final String GRANT_TYPE = "grant_type";
        public static final String CLIENT_ID = "client_id";
        public static final String CLIENT_SECRET = "client_secret";
        public static final String REFRESH_TOKEN = "refresh_token";
        public static final String ACCESS_TOKEN = "access_token";
        public static final String EXPIRES_IN = "expires_in";
    }
}

