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
package org.wso2.integration.connector.core;

import org.apache.synapse.MessageContext;
import org.wso2.integration.connector.core.util.Constants;

/**
 * AbstractConnectorOperation is an abstract class that extends the AbstractConnector class.
 * It provides a method to execute connector operations with a message context, response variable,
 * and an overwrite body flag.
 * While AbstractConnector provides the basic functionality for connectors,
 * AbstractConnectorOperation adds the ability to handle the response model where the response of the operation can be
 * stored in a variable or overwrite the body of the message context.
 */
public abstract class AbstractConnectorOperation extends AbstractConnector {

    /**
     * This method is an abstract method that must be implemented by subclasses.
     * It defines the contract for executing a connector operation with a message context,
     * response variable, and an overwrite body flag.
     *
     * @param messageContext  The message context to be used for the operation.
     * @param responseVariable The variable name to store the response.
     * @param overwriteBody   A flag indicating whether to overwrite the body of the message context.
     * @throws ConnectException If an error occurs during the connection process.
     */
    abstract public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException;

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String responseVariable = getMediatorParameter(
                messageContext, Constants.RESPONSE_VARIABLE, String.class, false
                                                      );
        Boolean overwriteBody = getMediatorParameter(
                messageContext, Constants.OVERWRITE_BODY, Boolean.class, false);
        execute(messageContext, responseVariable, overwriteBody);
    }
}
