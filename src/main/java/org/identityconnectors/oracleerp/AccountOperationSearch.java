﻿/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2008-2009 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://IdentityConnectors.dev.java.net/legal/license.txt
 * See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at identityconnectors/legal/license.txt.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package org.identityconnectors.oracleerp;

import static org.identityconnectors.oracleerp.OracleERPUtil.*;

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.dbcommon.DatabaseQueryBuilder;
import org.identityconnectors.dbcommon.FilterWhereBuilder;
import org.identityconnectors.dbcommon.SQLParam;
import org.identityconnectors.dbcommon.SQLUtil;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.operations.SearchOp;

/**
 * The Account CreateOp implementation of the SPI Select attributes from fnd_user table, add person details from
 * PER_PEOPLE_F table add responsibility names add auditor data add securing attributes all filtered according
 * attributes to get
 *
 * @author Petr Jung
 * @version $Revision 1.0$
 * @since 1.0
 */
final class AccountOperationSearch extends Operation implements SearchOp<FilterWhereBuilder> {

    /** Setup logging. */
    static final Log log = Log.getLog(AccountOperationSearch.class);

    /** Name translation */
    private AccountNameResolver nr = new AccountNameResolver();


    /** ResOps */
    private ResponsibilitiesOperations respOps;

    /** Audit Operations */
    private AuditorOperations auditOps;

    /** Securing Attributes Operations */
    private SecuringAttributesOperations secAttrOps;

    /**
     * @param conn
     * @param cfg
     */
    protected AccountOperationSearch(OracleERPConnection conn, OracleERPConfiguration cfg) {
        super(conn, cfg);
        respOps = new ResponsibilitiesOperations(conn, cfg);
        auditOps = new AuditorOperations(conn, cfg);
        secAttrOps = new SecuringAttributesOperations(conn, cfg);
    }

    /* (non-Javadoc)
     * @see org.identityconnectors.framework.spi.operations.SearchOp#createFilterTranslator(org.identityconnectors.framework.common.objects.ObjectClass, org.identityconnectors.framework.common.objects.OperationOptions)
     */
    public FilterTranslator<FilterWhereBuilder> createFilterTranslator(ObjectClass oclass, OperationOptions options) {
        return new OracleERPFilterTranslator(oclass, options, AccountOperations.FND_USER_COLS, nr);
    }

    /* (non-Javadoc)
     * @see org.identityconnectors.framework.spi.operations.SearchOp#executeQuery(org.identityconnectors.framework.common.objects.ObjectClass, java.lang.Object, org.identityconnectors.framework.common.objects.ResultsHandler, org.identityconnectors.framework.common.objects.OperationOptions)
     */
    public void executeQuery(ObjectClass oclass, FilterWhereBuilder where, ResultsHandler handler,
            OperationOptions options) {
        final String method = "executeQuery";
        log.info(method);

        final String tblname = cfg.app() + "fnd_user";
        final Set<AttributeInfo> ais = getAttributeInfos(cfg.getSchema(), ObjectClass.ACCOUNT_NAME);
        final Set<String> attributesToGet = getAttributesToGet(options, ais);
        final Set<String> readable = getReadableAttributes(getAttributeInfos(cfg.getSchema(), ObjectClass.ACCOUNT_NAME));

        final Set<String> fndUserColumnNames = getColumnNamesToGet(attributesToGet);
        //We always wont to have user id and user name
        fndUserColumnNames.add(USER_NAME); //User id
        fndUserColumnNames.add(START_DATE); //Enable Date
        fndUserColumnNames.add(END_DATE); // Disable date
        fndUserColumnNames.add(PWD_ACCESSES_LEFT); // Disable date
        fndUserColumnNames.add(PWD_DATE); // Last logon date
        fndUserColumnNames.add(PWD_LIFESPAN_DAYS); // Alloved days from last logon

        final Set<String> perPeopleColumnNames = CollectionUtil.newSet(fndUserColumnNames);
        final String filterId = getFilterId(where);

        fndUserColumnNames.retainAll(AccountOperations.FND_USER_COLS);
        perPeopleColumnNames.retainAll(AccountOperations.PER_PEOPLE_COLS);

        // For all user query there is no need to replace or quote anything
        final DatabaseQueryBuilder query = new DatabaseQueryBuilder(tblname, fndUserColumnNames);
        query.setWhere(where);
        String sqlSelect = query.getSQL();

        // Add active accounts and the accounts included filter
        if (StringUtil.isNotBlank(cfg.getAccountsIncluded())) {
            sqlSelect = whereAnd(sqlSelect, cfg.getAccountsIncluded());
        } else if (cfg.isActiveAccountsOnly()) {
            sqlSelect = whereAnd(sqlSelect, ACTIVE_ACCOUNTS_ONLY_WHERE_CLAUSE);
        }

        ResultSet resultSet = null;
        PreparedStatement statement = null;
        try {
            statement = conn.prepareStatement(sqlSelect, query.getParams());
            resultSet = statement.executeQuery();
            while (resultSet.next()) {
                AttributeMergeBuilder amb = new AttributeMergeBuilder(attributesToGet);
                final Map<String, SQLParam> columnValues = getColumnValues(resultSet);
                final SQLParam userNameParm = columnValues.get(USER_NAME);
                final String userName = (String) userNameParm.getValue();
                // get users account attributes
                buildAccountAttributes(amb, columnValues, readable);

                // if person_id not null and employee_number in schema, return employee_number
                buildPersonDetails(amb, columnValues, perPeopleColumnNames, readable);

                // get users responsibilities only if if resp || direct_resp in account attribute
                buildResponsibilities(amb, userName);

                // get user's securing attributes
                buildSecuringAttributes(amb, userName);

                //Auditor data for get user only
                buildAuditorDataObject(amb, userName, filterId);

                //build special attributes
                buildSpecialAttributes(amb, columnValues);

                ConnectorObjectBuilder bld = new ConnectorObjectBuilder();

                // add all special attributes to account
                bld.setObjectClass(ObjectClass.ACCOUNT);
                bld.addAttributes(amb.build());
                bld.setName(userName);
                bld.setUid(userName);



                if (!handler.handle(bld.build())) {
                    break;
                }
            }
        } catch (Exception e) {
            final String msg = cfg.getMessage(MSG_ACCOUNT_NOT_READ, filterId == null ? "" : filterId );
            log.error(e, msg);
            SQLUtil.rollbackQuietly(conn);
            throw new ConnectorException(msg, e);
        } finally {
            SQLUtil.closeQuietly(resultSet);
            SQLUtil.closeQuietly(statement);
        }
        conn.commit();
        log.info(method + " ok");
    }

    /**
     * @param amb
     * @return
     */
    private boolean isAuditorDataRequired(final AttributeMergeBuilder amb) {
        for (String knownAttribute : ResponsibilitiesOperations.AUDITOR_ATTRIBUTE_NAMES) {
            if ( amb.isInAttributesToGet(knownAttribute) ) {
                return true;
            }
        }
        log.ok("isAuditorDataRequired: no auditor attributes are in attribute to get");
        return  false;
    }


    /**
     * Transform the columns to special attributes
     * @param amb
     * @param columnValues
     */
    public void buildSpecialAttributes(final AttributeMergeBuilder amb, final Map<String, SQLParam> columnValues) {
        // create the connector object..
        final Date dateNow = new Date(System.currentTimeMillis());
        final Date end_date = OracleERPUtil.extractDate(END_DATE, columnValues);
        //disable date
        if( end_date != null ) {
            amb.addAttribute(AttributeBuilder.buildDisableDate(end_date));
        }

        //enable date
        final Date start_date = OracleERPUtil.extractDate(START_DATE, columnValues);
        if ( start_date != null) {
            amb.addAttribute(AttributeBuilder.buildEnableDate(start_date));
        }

        //enable
        if (end_date != null && start_date != null) {
            boolean enable = dateNow.compareTo(end_date) <= 0 && dateNow.compareTo(start_date) > 0;
            amb.addAttribute(AttributeBuilder.buildEnabled(enable));
        } else if (end_date != null) {
            boolean enable = dateNow.compareTo(end_date) <= 0;
            amb.addAttribute(AttributeBuilder.buildEnabled(enable));
        } else if (start_date != null) {
            boolean enable = dateNow.compareTo(start_date) > 0;
            amb.addAttribute(AttributeBuilder.buildEnabled(enable));
        } else {
            //bld.addAttribute(AttributeBuilder.buildEnabled(false));
        }

        final Date lastLogonDate = OracleERPUtil.extractDate(LAST_LOGON_DATE, columnValues);
        if ( lastLogonDate != null ) {
            amb.addAttribute(AttributeBuilder.buildLastLoginDate(lastLogonDate));
        }

        // password expired
        final Date pwdDate = OracleERPUtil.extractDate(PWD_DATE, columnValues);
        if( pwdDate != null ) {
            amb.addAttribute(AttributeBuilder.buildLastPasswordChangeDate(pwdDate));
        }

        final Long access_left = OracleERPUtil.extractLong(PWD_ACCESSES_LEFT, columnValues);

        final Long lifespan_days = OracleERPUtil.extractLong(PWD_LIFESPAN_DAYS, columnValues);

        if (access_left != null && access_left.intValue() <= 0) {
            amb.addAttribute(AttributeBuilder.buildPasswordExpired(true));
        } else if (lifespan_days != null && pwdDate != null) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(pwdDate);
            cal.add(Calendar.DAY_OF_MONTH, lifespan_days.intValue());
            boolean expired = cal.after(dateNow);
            amb.addAttribute(AttributeBuilder.buildPasswordExpired(expired));
        } else if ( pwdDate == null){
            amb.addAttribute(AttributeBuilder.buildPasswordExpired(true));
        } else {
            amb.addAttribute(AttributeBuilder.buildPasswordExpired(false));
        }
    }

    /**
     * @param attributesToGet
     *            from application
     * @return the set of the column names
     */
    private Set<String> getColumnNamesToGet(Set<String> attributesToGet) {
        Set<String> columnNamesToGet = CollectionUtil.newCaseInsensitiveSet();

        // Replace attributes to quoted columnNames
        for (String attributeName : attributesToGet) {
            final String columnName = nr.getColumnName(attributeName);
            if (columnName != null) {
                columnNamesToGet.add(columnName);
            }
        }

        log.ok("columnNamesToGet done");
        return columnNamesToGet;
    }

    /**
     * @param bld
     * @param columnValues
     * @param columnNames
     */
    private void buildPersonDetails(final AttributeMergeBuilder bld, final Map<String, SQLParam> columnValues,
            final Set<String> personColumns, final Set<String> readable) {
        final String method = "buildPersonDetails";
        log.ok(method);

        if (personColumns.isEmpty()) {
            // No persons column required
            log.info("No persons AttributesToGet, skip");
            return;
        }

        final Long personId = extractLong(EMP_ID, columnValues);
        if (personId == null) {
            log.info("buildPersonDetails: Null personId(employId)");
            return;
        }
        log.ok("buildPersonDetails for personId: {0}", personId);

        //Names to get filter
        final String tblname = cfg.app() + "PER_PEOPLE_F";
        // For all account query there is no need to replace or quote anything
        final DatabaseQueryBuilder query = new DatabaseQueryBuilder(tblname, personColumns);
        final FilterWhereBuilder where = new FilterWhereBuilder();
        where.addBind(new SQLParam(PERSON_ID, personId, Types.NUMERIC), "=");
        query.setWhere(where);

        ResultSet result = null; // SQL query on person_id
        PreparedStatement statement = null; // statement that generates the query
        try {
            statement = conn.prepareStatement(query);
            result = statement.executeQuery();
            if (result != null) {
                if (result.next()) {
                    final Map<String, SQLParam> personValues = getColumnValues(result);
                    // get users account attributes
                    this.buildAccountAttributes(bld, personValues, readable);
                    log.ok("Person values {0} from result set ", personValues);
                }
            }

        } catch (Exception e) {
            final String msg = cfg.getMessage(MSG_ACCOUNT_NOT_READ, personId);
            log.error(e, msg);
            SQLUtil.rollbackQuietly(conn);
            throw new ConnectorException(msg, e);
        } finally {
            SQLUtil.closeQuietly(result);
            result = null;
            SQLUtil.closeQuietly(statement);
            statement = null;
        }
    }


    /**
     * Build account attributes . Translate column name to attribute name and convert the value
     * Add only readable attributes
     * @param amb the attribute merger
     * @param columnValues the column value map
     * @throws SQLException
     */
    private void buildAccountAttributes(final AttributeMergeBuilder amb, final Map<String, SQLParam> columnValues, final Set<String> readable) throws SQLException {
        for (Map.Entry<String, SQLParam> val : columnValues.entrySet()) {
            final SQLParam param = val.getValue();
            if(param == null ) {
                continue;
            }
            buildAttribute(amb, param, readable);
        }
    }

    /**
     * @param amb
     *            AttributeMergeBuilder
     * @param param
     *            SQLParam
     * @throws SQLException
     *             if something wrong
     */
    private void buildAttribute(final AttributeMergeBuilder amb, final SQLParam param, final Set<String> readable) throws SQLException {
        final String columnName = param.getName();
        //Convert the data type and create attribute from it.
        final String attributeName = nr.getAttributeName(columnName);
        //  Add only readable attributes
        if (readable.contains(attributeName)) {
            final Object origValue = param.getValue();
            Object value = null;
            if (origValue instanceof BigInteger) {
                value =  origValue.toString();
            } else if (origValue instanceof Long) {
                value =  origValue.toString();
            } else {
                value = SQLUtil.jdbc2AttributeValue(origValue);
            }
            amb.addAttribute(attributeName, value);
        }
    }

    /**
     * @param amb builder
     * @param userName of the user
     */
    private void buildResponsibilities(AttributeMergeBuilder amb, final String userName) {

        if (!cfg.isNewResponsibilityViews() && amb.isInAttributesToGet(RESPS)) {
            log.info("buildResponsibilities from "+RESPS_TABLE);
            //add responsibilities
            final List<String> responsibilities = respOps.getResponsibilities(userName, RESPS_TABLE, false);
            amb.addAttribute(RESPS, responsibilities);

            //add resps list
            final List<String> resps = respOps.getResps(responsibilities, RESP_FMT_KEYS);
            amb.addAttribute(RESPKEYS, resps);
        } else if (amb.isInAttributesToGet(DIRECT_RESPS)) {
            log.info("buildResponsibilities from "+RESPS_DIRECT_VIEW);
            final List<String> responsibilities = respOps.getResponsibilities(userName, RESPS_DIRECT_VIEW, false);
            amb.addAttribute(DIRECT_RESPS, responsibilities);

            //add resps list
            final List<String> resps = respOps.getResps(responsibilities, RESP_FMT_KEYS);
            amb.addAttribute(RESPKEYS, resps);
        }

        if (amb.isInAttributesToGet(INDIRECT_RESPS)) {
            log.info("buildResponsibilities from "+RESPS_INDIRECT_VIEW);
            //add responsibilities
            final List<String> responsibilities = respOps.getResponsibilities(userName, RESPS_INDIRECT_VIEW, false);
            amb.addAttribute(INDIRECT_RESPS, responsibilities);
        }
    }


    /**
     * @param amb
     * @param userName
     */
    private void buildSecuringAttributes(AttributeMergeBuilder amb, final String userName) {
        if (!cfg.isManageSecuringAttrs()) {
            return;
        }

        if ( amb.isInAttributesToGet(SEC_ATTRS) ) {
            List<String> secAttrs = secAttrOps.getSecuringAttrs(userName);
            if (secAttrs != null) {
                amb.addAttribute(SEC_ATTRS, secAttrs);
            }
        }
    }

    /**
     * @param amb
     *            builder
     * @param userName
     *            id of the responsibility
     * @param filterId
     */
    public void buildAuditorDataObject(AttributeMergeBuilder amb, String userName, String filterId) {
        if (filterId == null) {
            return;
        }
        final boolean auditorDataRequired = isAuditorDataRequired(amb);
        if (auditorDataRequired) {
            log.info("buildAuditorDataObject  for uid: {0}", auditorDataRequired);
            List<String> activeRespList = respOps.getResponsibilities(userName, respOps.getRespLocation(), false);
            for (String activeRespName : activeRespList) {
                auditOps.updateAuditorData(amb, activeRespName);
            }
        }
    }
}