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

import com.google.gson.JsonObject;
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.data.connector.DefaultConnectorResponse;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.integration.connector.core.util.Constants;

import java.util.Map;
import java.util.Objects;

public abstract class AbstractConnector extends AbstractMediator implements Connector {

	public abstract void connect(MessageContext messageContext) throws ConnectException;

	public boolean mediate(MessageContext messageContext) {

		try {
			connect(messageContext);
		} catch (ConnectException e) {
			throw new SynapseException("Error occurred when connecting conenctor. Details :", e);
		}
		return true;
	}

	protected Object getParameter(MessageContext messageContext ,String paramName) {
		return ConnectorUtils.lookupTemplateParamater(messageContext, paramName);
	}

	protected <T> T getMediatorParameter(
			MessageContext messageContext, String parameterName, Class<T> type, boolean isOptional) {

		Object parameter = getParameter(messageContext, parameterName);
		if (!isOptional && (parameter == null || parameter.toString().isEmpty())) {
			handleException(String.format("Parameter %s is not provided", parameterName), messageContext);
		} else if (parameter == null || parameter.toString().isEmpty()) {
			return null;
		}

		try {
			return parse(Objects.requireNonNull(parameter).toString(), type);
		} catch (IllegalArgumentException e) {
			handleException(String.format(
					"Parameter %s is not of type %s", parameterName, type.getName()
										 ), messageContext);
		}

		return null;
	}

	protected <T> T getProperty(
			MessageContext messageContext, String propertyName, Class<T> type, boolean isOptional) {

		Object property = messageContext.getProperty(propertyName);
		if (!isOptional && (property == null || property.toString().isEmpty())) {
			handleException(String.format("Property %s is not set", propertyName), messageContext);
		} else if (property == null || property.toString().isEmpty()) {
			return null;
		}

		try {
			return parse(Objects.requireNonNull(property).toString(), type);
		} catch (IllegalArgumentException e) {
			handleException(String.format(
					"Property %s is not of type %s", propertyName, type.getName()
										 ), messageContext);
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	public static <T> T parse(String value, Class<T> type) throws IllegalArgumentException {
		if (type == Integer.class) {
			return (T) Integer.valueOf(value);
		} else if (type == Double.class) {
			return (T) Double.valueOf(value);
		} else if (type == Boolean.class) {
			return (T) Boolean.valueOf(value);
		} else if (type == String.class) {
			return (T) value;
		} else {
			throw new IllegalArgumentException("Unsupported type: " + type);
		}
	}

	protected void handleConnectorResponse(MessageContext messageContext, String responseVariable,
										   Boolean overwriteBody, JsonObject payload,
										   Map<String, Object> headers, Map<String, Object> attributes) {

		ConnectorResponse response = new DefaultConnectorResponse();
		if (overwriteBody != null && overwriteBody) {
			org.apache.axis2.context.MessageContext axisMsgCtx = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
			String jsonString = payload.toString();
			try {
				JsonUtil.getNewJsonPayload(axisMsgCtx, jsonString, true, true);
			} catch (AxisFault e) {
				handleException("Error setting response payload", e, messageContext);
			}
			axisMsgCtx.setProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE, Constants.CONTENT_TYPE_JSON);
			axisMsgCtx.setProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE, Constants.CONTENT_TYPE_JSON);
		} else {
			String output = payload.toString();
			response.setPayload(output);
		}
		response.setHeaders(headers);
		response.setAttributes(attributes);
		messageContext.setVariable(responseVariable, response);
	}
}
