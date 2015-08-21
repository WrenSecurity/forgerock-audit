/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.audit.handlers.jdbc;

import static org.assertj.core.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.forgerock.audit.events.AuditEvent;
import org.forgerock.audit.events.AuditEventBuilder;
import org.forgerock.audit.handlers.jdbc.JDBCAuditEventHandlerConfiguration.ConnectionPool;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.test.assertj.AssertJJsonValueAssert;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.query.QueryFilter;
import org.forgerock.util.test.assertj.AssertJPromiseAssert;
import org.h2.tools.RunScript;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@SuppressWarnings("javadoc")
public class JDBCAuditEventHandlerTest {

    public static final String H2_DRIVER = "org.h2.Driver";
    public static final String H2_JDBC_URL = "jdbc:h2:mem:audit";
    public static final String H2_JDBC_USERNAME = "";
    public static final String H2_JDBC_PASSWORD = "";

    public static final String EVENTS_JSON = "/events.json";

    public static final String AUDIT_SQL_SCRIPT = "/audit.sql";
    public static final String TEST_AUDIT_EVENT_TOPIC = "test";
    public static final String AUDIT_TEST_TABLE_NAME = "audittest";
    public static final String AUTHENTICATION_ID_POINTER = "authentication/id";
    public static final String ID_TABLE_COLUMN = "objectid";
    public static final String EVENTNAME_TABLE_COLUMN = "eventname";
    public static final String TIMESTAMP_TABLE_COLUMN = "activitydate";
    public static final String AUTHENTICATION_ID_TABLE_COLUMN = "userid";
    public static final String TRANSACTIONID_TABLE_COLUMN = "transactionid";

    public static final String SHUTDOWN = "SHUTDOWN";

    public static final String EVENT_NAME_FIELD = "eventName";
    public static final String TRANSACTION_ID_FIELD = "transactionId";
    public static final String ID_FIELD = "_id";
    public static final String TIMESTAMP_FIELD = "timestamp";
    public static final String AUTHENTICATION_FIELD = "authentication";
    public static final String AUTHENTICATION_ID_FIELD = "id";

    public static final String ID_VALUE = "UUID";
    public static final String EVENT_NAME_VALUE = "eventName";
    public static final String AUTHENTICATION_ID_VALUE = "test@forgerock.com";
    public static final String TRANSACTION_ID_VALUE = "transactionId";

    private Connection connection;

    @BeforeMethod
    private void setUpDataBase()  throws SQLException, ClassNotFoundException {
        Class.forName(H2_DRIVER);
        connection = DriverManager.getConnection(H2_JDBC_URL);
        InputStream sqlScript = getClass().getResourceAsStream(AUDIT_SQL_SCRIPT);
        RunScript.execute(connection, new InputStreamReader(sqlScript));
    }

    @AfterMethod
    private void tearDownDataBase() {
        try {
            connection.createStatement().execute(SHUTDOWN);
        } catch (SQLException e) {
            // do nothing
        }
        connection = null;
    }

    @Test
    public void testPublish() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        JsonValue event = makeEvent();

        // when
        final Promise<ResourceResponse, ResourceException> promise =
            handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        // then
        AssertJPromiseAssert.assertThat(promise).succeeded();
        AssertJJsonValueAssert.assertThat(promise.get().getContent()).isEqualTo(event);
    }

    @Test
    public void testCreateWithEmptyDB() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        JsonValue event = makeEvent();
        connection.createStatement().execute(SHUTDOWN);

        // when
        final Promise<ResourceResponse, ResourceException> promise =
            handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        // then
        AssertJPromiseAssert.assertThat(promise).failedWithException().isInstanceOf(InternalServerErrorException.class);
    }

    @Test
    public void testCreateWithNoTableMapping() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        configuration.setTableMappings(new LinkedList<TableMapping>());
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        JsonValue event = makeEvent();

        // when
        final Promise<ResourceResponse, ResourceException> promise =
            handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        // then
        AssertJPromiseAssert.assertThat(promise).failedWithException().isInstanceOf(InternalServerErrorException.class);
    }

    @Test
    public void testRead() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        JsonValue event = makeEvent();

        // create entry
        Promise<ResourceResponse, ResourceException> promise =
            handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        // when
        promise = handler.readEvent(TEST_AUDIT_EVENT_TOPIC, promise.get().getId());

        // then
        AssertJPromiseAssert.assertThat(promise).succeeded();

        assertThat(promise.get().getContent().asMap())
                .containsEntry(ID_FIELD, ID_VALUE)
                .containsEntry(EVENT_NAME_FIELD, EVENT_NAME_VALUE)
                .containsKeys(TIMESTAMP_FIELD)
                .containsEntry(TRANSACTION_ID_FIELD, TRANSACTION_ID_VALUE)
                .containsEntry(AUTHENTICATION_FIELD,
                        new LinkedHashMap<String, Object>() {{
                            put(AUTHENTICATION_ID_FIELD, AUTHENTICATION_ID_VALUE);
                        }});
    }

    @Test
    public void testReadWithNoEntry() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);

        // when
        final Promise<ResourceResponse, ResourceException> promise =
            handler.readEvent(TEST_AUDIT_EVENT_TOPIC, ID_VALUE);

        // then
        AssertJPromiseAssert.assertThat(promise).failedWithException().isInstanceOf(NotFoundException.class);
    }

    @Test
    public void testReadWithEmptyDB() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        JsonValue event = makeEvent();

        // create entry
        Promise<ResourceResponse, ResourceException> promise = handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);
        connection.createStatement().execute(SHUTDOWN);

        // when
        promise = handler.readEvent(TEST_AUDIT_EVENT_TOPIC, promise.get().getId());

        // then
        AssertJPromiseAssert.assertThat(promise).failedWithException().isInstanceOf(InternalServerErrorException.class);
    }

    @Test
    public void testReadWithNoTableMapping() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        JsonValue event = makeEvent();

        // create entry
        Promise<ResourceResponse, ResourceException> promise = handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        configuration.setTableMappings(new LinkedList<TableMapping>());
        handler = createJDBCAuditEventHandler(configuration);

        // when
        promise = handler.readEvent(TEST_AUDIT_EVENT_TOPIC, promise.get().getId());

        // then
        AssertJPromiseAssert.assertThat(promise).failedWithException().isInstanceOf(InternalServerErrorException.class);
    }

    @Test
    public void testQuery() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        final JsonValue event = makeEvent();

        // create entry
        Promise<ResourceResponse, ResourceException> promise = handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        final QueryRequest queryRequest = Requests.newQueryRequest(TEST_AUDIT_EVENT_TOPIC)
                .setQueryFilter(QueryFilter.equalTo(new JsonPointer(ID_FIELD), promise.get().getId()));

        final List<ResourceResponse> resourceResponses = new LinkedList<>();

        // when
        final Promise<QueryResponse, ResourceException> queryPromise =
                handler.queryEvents(TEST_AUDIT_EVENT_TOPIC, queryRequest, new QueryResourceHandler() {
                    @Override
                    public boolean handleResource(ResourceResponse resourceResponse) {
                        resourceResponses.add(resourceResponse);
                        return true;
                    }
                });

        assertThat(resourceResponses.size()).isEqualTo(1);
        assertThat(resourceResponses.get(0).getContent().asMap())
                .containsEntry(ID_FIELD, ID_VALUE)
                .containsEntry(EVENT_NAME_FIELD, EVENT_NAME_VALUE)
                .containsKeys(TIMESTAMP_FIELD)
                .containsEntry(TRANSACTION_ID_FIELD, TRANSACTION_ID_VALUE)
                .containsEntry(AUTHENTICATION_FIELD,
                        new LinkedHashMap<String, Object>() {{
                            put(AUTHENTICATION_ID_FIELD, AUTHENTICATION_ID_VALUE);
                        }});
    }

    @Test
    public void testQueryWithEmptyDB() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        final JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        final JsonValue event = makeEvent();

        // create entry
        Promise<ResourceResponse, ResourceException> promise = handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        final QueryRequest queryRequest = Requests.newQueryRequest(TEST_AUDIT_EVENT_TOPIC)
                .setQueryFilter(QueryFilter.equalTo(new JsonPointer(ID_FIELD), promise.get().getId()));

        final List<ResourceResponse> resourceResponses = new LinkedList<>();

        connection.createStatement().execute(SHUTDOWN);

        // when
        final Promise<QueryResponse, ResourceException> queryPromise =
                handler.queryEvents(TEST_AUDIT_EVENT_TOPIC, queryRequest, new QueryResourceHandler() {
                    @Override
                    public boolean handleResource(ResourceResponse resourceResponse) {
                        resourceResponses.add(resourceResponse);
                        return true;
                    }
                });

        // then
        AssertJPromiseAssert.assertThat(queryPromise).failedWithException().isInstanceOf(InternalServerErrorException.class);
    }

    @Test
    public void testQueryWithNoTableMapping() throws Exception {
        // given
        final JDBCAuditEventHandlerConfiguration configuration = createConfiguration();
        JDBCAuditEventHandler handler = createJDBCAuditEventHandler(configuration);
        final JsonValue event = makeEvent();

        // create entry
        Promise<ResourceResponse, ResourceException> promise = handler.publishEvent(TEST_AUDIT_EVENT_TOPIC, event);

        final QueryRequest queryRequest = Requests.newQueryRequest(TEST_AUDIT_EVENT_TOPIC)
                .setQueryFilter(QueryFilter.equalTo(new JsonPointer(ID_FIELD), promise.get().getId()));

        final List<ResourceResponse> resourceResponses = new LinkedList<>();

        configuration.setTableMappings(new LinkedList<TableMapping>());
        handler = createJDBCAuditEventHandler(configuration);

        // when
        final Promise<QueryResponse, ResourceException> queryPromise =
                handler.queryEvents(TEST_AUDIT_EVENT_TOPIC, queryRequest, new QueryResourceHandler() {
                    @Override
                    public boolean handleResource(ResourceResponse resourceResponse) {
                        resourceResponses.add(resourceResponse);
                        return true;
                    }
                });

        // then
        AssertJPromiseAssert.assertThat(queryPromise).failedWithException().isInstanceOf(InternalServerErrorException.class);
    }

    private JDBCAuditEventHandler createJDBCAuditEventHandler(final JDBCAuditEventHandlerConfiguration configuration)
            throws Exception {
        final JDBCAuditEventHandler handler = new JDBCAuditEventHandler();
        handler.configure(configuration);
        addEventsMetaData(handler);
        return handler;
    }

    private JDBCAuditEventHandlerConfiguration createConfiguration() {
        final JDBCAuditEventHandlerConfiguration configuration = new JDBCAuditEventHandlerConfiguration();
        final List<TableMapping> tableMappings = new LinkedList<>();
        final TableMapping tableMapping = new TableMapping();
        final Map<String, String> fieldToColumn = new LinkedHashMap<>();
        fieldToColumn.put(ID_FIELD, ID_TABLE_COLUMN);
        fieldToColumn.put(EVENT_NAME_FIELD, EVENTNAME_TABLE_COLUMN);
        fieldToColumn.put(TIMESTAMP_FIELD, TIMESTAMP_TABLE_COLUMN);
        fieldToColumn.put(AUTHENTICATION_ID_POINTER, AUTHENTICATION_ID_TABLE_COLUMN);
        fieldToColumn.put(TRANSACTION_ID_FIELD, TRANSACTIONID_TABLE_COLUMN);
        tableMapping.setEvent(TEST_AUDIT_EVENT_TOPIC);
        tableMapping.setTable(AUDIT_TEST_TABLE_NAME);
        tableMapping.setFieldToColumn(fieldToColumn);
        tableMappings.add(tableMapping);
        configuration.setTableMappings(tableMappings);
        ConnectionPool connectionPool = new ConnectionPool();
        connectionPool.setUsername(H2_JDBC_USERNAME);
        connectionPool.setPassword(H2_JDBC_PASSWORD);
        connectionPool.setJdbcUrl(H2_JDBC_URL);
        configuration.setConnectionPool(connectionPool);
        return configuration;
    }

    private JsonValue makeEvent()
    {
      final AuditEvent testAuditEvent = TestAuditEventBuilder.testAuditEventBuilder()
              .eventName(EVENT_NAME_VALUE)
              .authentication(AUTHENTICATION_ID_VALUE)
              .timestamp(System.currentTimeMillis())
              .transactionId(TRANSACTION_ID_VALUE).toEvent();
      testAuditEvent.getValue().put(ID_FIELD, ID_VALUE);
      return testAuditEvent.getValue();
    }

    private void addEventsMetaData(JDBCAuditEventHandler handler) throws Exception {
        Map<String, JsonValue> events = new LinkedHashMap<>();
        try (final InputStream configStream = getClass().getResourceAsStream(EVENTS_JSON)) {
            final JsonValue predefinedEventTypes = new JsonValue(new ObjectMapper().readValue(configStream, Map.class));
            for (String eventTypeName : predefinedEventTypes.keys()) {
                events.put(eventTypeName, predefinedEventTypes.get(eventTypeName));
            }
        }
        handler.setAuditEventsMetaData(events);
    }

    static class TestAuditEventBuilder<T extends TestAuditEventBuilder<T>>
            extends AuditEventBuilder<T> {

        @SuppressWarnings("rawtypes")
        public static TestAuditEventBuilder<?> testAuditEventBuilder() {
            return new TestAuditEventBuilder();
        }
    }
}
