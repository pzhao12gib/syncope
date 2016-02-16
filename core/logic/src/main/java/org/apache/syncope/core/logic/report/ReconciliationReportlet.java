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
package org.apache.syncope.core.logic.report;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections4.Closure;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.report.ReconciliationReportletConf;
import org.apache.syncope.common.lib.report.ReconciliationReportletConf.Feature;
import org.apache.syncope.common.lib.report.ReportletConf;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.core.misc.search.SearchCondConverter;
import org.apache.syncope.core.misc.utils.FormatUtils;
import org.apache.syncope.core.misc.utils.MappingUtils;
import org.apache.syncope.core.persistence.api.dao.AnySearchDAO;
import org.apache.syncope.core.persistence.api.dao.AnyTypeDAO;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.ReportletConfClass;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.dao.VirSchemaDAO;
import org.apache.syncope.core.persistence.api.dao.search.AnyTypeCond;
import org.apache.syncope.core.persistence.api.dao.search.OrderByClause;
import org.apache.syncope.core.persistence.api.dao.search.SearchCond;
import org.apache.syncope.core.persistence.api.entity.Any;
import org.apache.syncope.core.persistence.api.entity.AnyType;
import org.apache.syncope.core.persistence.api.entity.AnyUtils;
import org.apache.syncope.core.persistence.api.entity.AnyUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.VirSchema;
import org.apache.syncope.core.persistence.api.entity.anyobject.AnyObject;
import org.apache.syncope.core.persistence.api.entity.group.Group;
import org.apache.syncope.core.persistence.api.entity.resource.ExternalResource;
import org.apache.syncope.core.persistence.api.entity.resource.MappingItem;
import org.apache.syncope.core.persistence.api.entity.resource.Provision;
import org.apache.syncope.core.persistence.api.entity.user.User;
import org.apache.syncope.core.provisioning.api.Connector;
import org.apache.syncope.core.provisioning.api.ConnectorFactory;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;
import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Reportlet for extracting information for the current reconciliation status, e.g. the coherence between Syncope
 * information and mapped entities on external resources.
 */
@ReportletConfClass(ReconciliationReportletConf.class)
public class ReconciliationReportlet extends AbstractReportlet {

    private static final int PAGE_SIZE = 10;

    @Autowired
    private UserDAO userDAO;

    @Autowired
    private GroupDAO groupDAO;

    @Autowired
    private AnyTypeDAO anyTypeDAO;

    @Autowired
    private AnySearchDAO searchDAO;

    @Autowired
    private VirSchemaDAO virSchemaDAO;

    @Autowired
    private MappingUtils mappingUtils;

    @Autowired
    private ConnectorFactory connFactory;

    @Autowired
    private AnyUtilsFactory anyUtilsFactory;

    private ReconciliationReportletConf conf;

    private String getAnyElementName(final AnyTypeKind anyTypeKind) {
        String elementName;

        switch (anyTypeKind) {
            case USER:
                elementName = "user";
                break;

            case GROUP:
                elementName = "group";
                break;

            case ANY_OBJECT:
            default:
                elementName = "anyObject";
        }

        return elementName;
    }

    private void doExtract(
            final ContentHandler handler,
            final Any<?> any,
            final Set<Missing> missing,
            final Set<Misaligned> misaligned)
            throws SAXException {

        AttributesImpl atts = new AttributesImpl();

        for (Feature feature : conf.getFeatures()) {
            String type = null;
            String value = null;
            switch (feature) {
                case key:
                    type = ReportXMLConst.XSD_LONG;
                    value = String.valueOf(any.getKey());
                    break;

                case username:
                    if (any instanceof User) {
                        type = ReportXMLConst.XSD_STRING;
                        value = User.class.cast(any).getUsername();
                    }
                    break;

                case groupName:
                    if (any instanceof Group) {
                        type = ReportXMLConst.XSD_STRING;
                        value = Group.class.cast(any).getName();
                    }
                    break;

                case workflowId:
                    type = ReportXMLConst.XSD_LONG;
                    value = String.valueOf(any.getWorkflowId());
                    break;

                case status:
                    type = ReportXMLConst.XSD_STRING;
                    value = any.getStatus();
                    break;

                case creationDate:
                    type = ReportXMLConst.XSD_DATETIME;
                    value = any.getCreationDate() == null
                            ? StringUtils.EMPTY
                            : FormatUtils.format(any.getCreationDate());
                    break;

                case lastLoginDate:
                    if (any instanceof User) {
                        type = ReportXMLConst.XSD_DATETIME;
                        value = User.class.cast(any).getLastLoginDate() == null
                                ? StringUtils.EMPTY
                                : FormatUtils.format(User.class.cast(any).getLastLoginDate());
                    }
                    break;

                case changePwdDate:
                    if (any instanceof User) {
                        type = ReportXMLConst.XSD_DATETIME;
                        value = User.class.cast(any).getChangePwdDate() == null
                                ? StringUtils.EMPTY
                                : FormatUtils.format(User.class.cast(any).getChangePwdDate());
                    }
                    break;

                case passwordHistorySize:
                    if (any instanceof User) {
                        type = ReportXMLConst.XSD_INT;
                        value = String.valueOf(User.class.cast(any).getPasswordHistory().size());
                    }
                    break;

                case failedLoginCount:
                    if (any instanceof User) {
                        type = ReportXMLConst.XSD_INT;
                        value = String.valueOf(User.class.cast(any).getFailedLogins());
                    }
                    break;

                default:
            }

            if (type != null && value != null) {
                atts.addAttribute("", "", feature.name(), type, value);
            }
        }

        handler.startElement("", "", getAnyElementName(any.getType().getKind()), atts);

        for (Missing item : missing) {
            atts.clear();
            atts.addAttribute("", "", "resource", ReportXMLConst.XSD_STRING, item.getResource());
            atts.addAttribute("", "", "connObjectKeyValue", ReportXMLConst.XSD_STRING, item.getConnObjectKeyValue());

            handler.startElement("", "", "missing", atts);
            handler.endElement("", "", "missing");
        }
        for (Misaligned item : misaligned) {
            atts.clear();
            atts.addAttribute("", "", "resource", ReportXMLConst.XSD_STRING, item.getResource());
            atts.addAttribute("", "", "connObjectKeyValue", ReportXMLConst.XSD_STRING, item.getConnObjectKeyValue());
            atts.addAttribute("", "", ReportXMLConst.ATTR_NAME, ReportXMLConst.XSD_STRING, item.getName());

            handler.startElement("", "", "misaligned", atts);

            handler.startElement("", "", "onSyncope", null);
            for (Object value : item.getOnSyncope()) {
                char[] asChars = value.toString().toCharArray();

                handler.startElement("", "", "value", null);
                handler.characters(asChars, 0, asChars.length);
                handler.endElement("", "", "value");
            }
            handler.endElement("", "", "onSyncope");

            handler.startElement("", "", "onResource", null);
            for (Object value : item.getOnResource()) {
                char[] asChars = value.toString().toCharArray();

                handler.startElement("", "", "value", null);
                handler.characters(asChars, 0, asChars.length);
                handler.endElement("", "", "value");
            }
            handler.endElement("", "", "onResource");

            handler.endElement("", "", "misaligned");
        }

        handler.endElement("", "", getAnyElementName(any.getType().getKind()));
    }

    private void doExtract(final ContentHandler handler, final List<? extends Any<?>> anys)
            throws SAXException, ReportException {

        final Set<Missing> missing = new HashSet<>();
        final Set<Misaligned> misaligned = new HashSet<>();

        for (Any<?> any : anys) {
            missing.clear();
            misaligned.clear();

            AnyUtils anyUtils = anyUtilsFactory.getInstance(any);
            for (final ExternalResource resource : anyUtils.getAllResources(any)) {
                Provision provision = resource.getProvision(any.getType());
                MappingItem connObjectKeyItem = MappingUtils.getConnObjectKeyItem(provision);
                if (provision != null && connObjectKeyItem != null) {
                    // 1. build connObjectKeyValue
                    final String connObjectKeyValue = mappingUtils.getConnObjectKeyValue(any, provision);

                    // 2. determine attributes to query
                    Set<MappingItem> linkinMappingItems = new HashSet<>();
                    for (VirSchema virSchema : virSchemaDAO.findByProvision(provision)) {
                        linkinMappingItems.add(virSchema.asLinkingMappingItem());
                    }
                    Iterator<MappingItem> mapItems = IteratorUtils.chainedIterator(
                            provision.getMapping().getItems().iterator(),
                            linkinMappingItems.iterator());

                    // 3. read from the underlying connector
                    Connector connector = connFactory.getConnector(resource);
                    ConnectorObject connectorObject = connector.getObject(
                            provision.getObjectClass(),
                            new Uid(connObjectKeyValue),
                            MappingUtils.buildOperationOptions(mapItems));

                    if (connectorObject == null) {
                        // 4. not found on resource?
                        LOG.error("Object {} with class {} not found on resource {}",
                                connObjectKeyValue, provision.getObjectClass(), resource);

                        missing.add(new Missing(resource.getKey(), connObjectKeyValue));
                    } else {
                        // 5. found but misaligned?

                        final Map<String, Set<Object>> syncopeAttrs = new HashMap<>();
                        for (Attribute attr : mappingUtils.prepareAttrs(any, null, false, null, provision).getRight()) {
                            syncopeAttrs.put(attr.getName(), new HashSet<>(attr.getValue()));
                        }

                        final Map<String, Set<Object>> resourceAttrs = new HashMap<>();
                        for (Attribute attr : connectorObject.getAttributes()) {
                            if (!OperationalAttributes.PASSWORD_NAME.equals(attr.getName())
                                    && !OperationalAttributes.ENABLE_NAME.equals(attr.getName())) {

                                resourceAttrs.put(attr.getName(), new HashSet<>(attr.getValue()));
                            }
                        }

                        IterableUtils.forEach(CollectionUtils.subtract(syncopeAttrs.keySet(), resourceAttrs.keySet()),
                                new Closure<String>() {

                            @Override
                            public void execute(final String name) {
                                misaligned.add(new Misaligned(
                                        resource.getKey(),
                                        connObjectKeyValue,
                                        name,
                                        syncopeAttrs.get(name),
                                        Collections.emptySet()));
                            }
                        });

                        for (Map.Entry<String, Set<Object>> entry : resourceAttrs.entrySet()) {
                            if (syncopeAttrs.containsKey(entry.getKey())) {
                                if (!syncopeAttrs.get(entry.getKey()).equals(entry.getValue())) {
                                    misaligned.add(new Misaligned(
                                            resource.getKey(),
                                            connObjectKeyValue,
                                            entry.getKey(),
                                            syncopeAttrs.get(entry.getKey()),
                                            entry.getValue()));
                                }
                            } else {
                                misaligned.add(new Misaligned(
                                        resource.getKey(),
                                        connObjectKeyValue,
                                        entry.getKey(),
                                        Collections.emptySet(),
                                        entry.getValue()));
                            }
                        }
                    }
                }
            }

            if (!missing.isEmpty() || !misaligned.isEmpty()) {
                doExtract(handler, any, missing, misaligned);
            }
        }
    }

    private void doExtract(final ContentHandler handler, final SearchCond cond, final AnyTypeKind anyTypeKind)
            throws SAXException {

        int count = searchDAO.count(
                SyncopeConstants.FULL_ADMIN_REALMS,
                cond,
                anyTypeKind);

        for (int page = 1; page <= (count / PAGE_SIZE) + 1; page++) {
            List<AnyObject> anys = searchDAO.search(
                    SyncopeConstants.FULL_ADMIN_REALMS,
                    cond,
                    page,
                    PAGE_SIZE,
                    Collections.<OrderByClause>emptyList(),
                    anyTypeKind);

            doExtract(handler, anys);
        }
    }

    @Override
    protected void doExtract(final ReportletConf conf, final ContentHandler handler) throws SAXException {
        if (conf instanceof ReconciliationReportletConf) {
            this.conf = ReconciliationReportletConf.class.cast(conf);
        } else {
            throw new ReportException(new IllegalArgumentException("Invalid configuration provided"));
        }

        handler.startElement("", "", getAnyElementName(AnyTypeKind.USER) + "s", null);
        if (StringUtils.isBlank(this.conf.getUserMatchingCond())) {
            doExtract(handler, userDAO.findAll());
        } else {
            SearchCond cond = SearchCondConverter.convert(this.conf.getUserMatchingCond());
            doExtract(handler, cond, AnyTypeKind.USER);
        }
        handler.endElement("", "", getAnyElementName(AnyTypeKind.USER) + "s");

        handler.startElement("", "", getAnyElementName(AnyTypeKind.GROUP) + "s", null);
        if (StringUtils.isBlank(this.conf.getGroupMatchingCond())) {
            doExtract(handler, groupDAO.findAll());
        } else {
            SearchCond cond = SearchCondConverter.convert(this.conf.getUserMatchingCond());
            doExtract(handler, cond, AnyTypeKind.GROUP);
        }
        handler.endElement("", "", getAnyElementName(AnyTypeKind.GROUP) + "s");

        AttributesImpl atts = new AttributesImpl();

        for (AnyType anyType : anyTypeDAO.findAll()) {
            if (!anyType.equals(anyTypeDAO.findUser()) && !anyType.equals(anyTypeDAO.findGroup())) {
                atts.clear();
                atts.addAttribute("", "", "type", ReportXMLConst.XSD_STRING, anyType.getKey());
                handler.startElement("", "", getAnyElementName(AnyTypeKind.ANY_OBJECT) + "s", atts);

                AnyTypeCond anyTypeCond = new AnyTypeCond();
                anyTypeCond.setAnyTypeName(anyType.getKey());
                SearchCond cond = StringUtils.isBlank(this.conf.getAnyObjectMatchingCond())
                        ? SearchCond.getLeafCond(anyTypeCond)
                        : SearchCond.getAndCond(
                                SearchCond.getLeafCond(anyTypeCond),
                                SearchCondConverter.convert(this.conf.getAnyObjectMatchingCond()));

                doExtract(handler, cond, AnyTypeKind.ANY_OBJECT);

                handler.endElement("", "", getAnyElementName(AnyTypeKind.ANY_OBJECT) + "s");
            }
        }
    }

    private static class Missing {

        private final String resource;

        private final String connObjectKeyValue;

        Missing(final String resource, final String connObjectKeyValue) {
            this.resource = resource;
            this.connObjectKeyValue = connObjectKeyValue;
        }

        public String getResource() {
            return resource;
        }

        public String getConnObjectKeyValue() {
            return connObjectKeyValue;
        }

    }

    private static class Misaligned extends Missing {

        private final String name;

        private final Set<Object> onSyncope;

        private final Set<Object> onResource;

        Misaligned(
                final String resource,
                final String connObjectKeyValue,
                final String name,
                final Set<Object> onSyncope,
                final Set<Object> onResource) {

            super(resource, connObjectKeyValue);

            this.name = name;
            this.onSyncope = onSyncope;
            this.onResource = onResource;
        }

        public String getName() {
            return name;
        }

        public Set<Object> getOnSyncope() {
            return onSyncope;
        }

        public Set<Object> getOnResource() {
            return onResource;
        }

    }
}