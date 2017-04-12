/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.common.lib.to;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import org.apache.syncope.common.lib.AbstractBaseBean;
import org.apache.syncope.common.lib.types.SAML2BindingType;

@XmlRootElement(name = "saml2ReceivedResponse")
@XmlType
public class SAML2ReceivedResponseTO extends AbstractBaseBean {

    private static final long serialVersionUID = 6102419133516694822L;

    private String samlResponse;

    private String relayState;

    private SAML2BindingType bindingType;

    public String getSamlResponse() {
        return samlResponse;
    }

    public void setSamlResponse(final String samlResponse) {
        this.samlResponse = samlResponse;
    }

    public String getRelayState() {
        return relayState;
    }

    public void setRelayState(final String relayState) {
        this.relayState = relayState;
    }

    public SAML2BindingType getBindingType() {
        return bindingType;
    }

    public void setBindingType(final SAML2BindingType bindingType) {
        this.bindingType = bindingType;
    }

}