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
package org.forgerock.audit;

import static java.util.Collections.unmodifiableSet;
import static java.util.Collections.unmodifiableMap;
import static org.forgerock.audit.events.AuditEventBuilder.TIMESTAMP;
import static org.forgerock.audit.events.AuditEventBuilder.TRANSACTION_ID;
import static org.forgerock.audit.util.ResourceExceptionsUtil.adapt;
import static org.forgerock.audit.util.ResourceExceptionsUtil.notSupported;
import static org.forgerock.json.resource.Responses.newResourceResponse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.forgerock.audit.events.handlers.AuditEventHandler;
import org.forgerock.audit.events.handlers.EventHandlerConfiguration;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourcePath;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.ServiceUnavailableException;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.services.context.Context;
import org.forgerock.util.generator.IdGenerator;
import org.forgerock.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default implementation of {@link AuditService}.
 * <p/>
 * Instances receive their configuration when constructed and cannot be reconfigured. Where "hot-swappable"
 * reconfiguration is required, an instance of {@link AuditServiceProxy} should be used as a proxy. The old
 * AuditService should fully shutdown before the new instance is started. Care must be taken to ensure that
 * no other threads can interact with this object while {@link #startup()} and {@link #shutdown()} methods
 * are running.
 * <p/>
 * After construction, the AuditService will be in the 'STARTING' state until {@link #startup()} is called.
 * When in the 'STARTING' state, a call to any method other than {@link #startup()} will lead to
 * {@link ServiceUnavailableException}.
 * <p/>
 * After {@link #startup()} is called, assuming startup succeeds, the AuditService will then be in the
 * 'RUNNING' state and further calls to {@link #startup()} will be ignored.
 * <p/>
 * Calling {@link #shutdown()} will put the AuditService into the 'SHUTDOWN' state; once shutdown, the
 * AuditService will remain in this state and cannot be restarted. Further calls to {@link #shutdown()}
 * will be ignored. When in the 'SHUTDOWN' state, a call to any method other than {@link #shutdown()} will
 * lead to {@link ServiceUnavailableException}.
 * <p/>
 * When instances are no longer needed, {@link #shutdown()} should be called to ensure that any buffered
 * audit events are flushed and that all open file handles or connections are closed.
 */
final class AuditServiceImpl implements AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditServiceImpl.class);

    /**
     * User-facing configuration.
     */
    private final AuditServiceConfiguration config;
    /**
     * Map of all configured AuditEventHandlers indexed by their instance name.
     */
    private final Map<String, AuditEventHandler<?>> auditEventHandlersByName;
    /**
     * Lists of AuditEventHandlers that should receive events for each topic.
     */
    private final Map<String, Set<AuditEventHandler<?>>> auditEventHandlersByTopic;
    /**
     * All the audit event types configured.
     */
    private final Map<String, JsonValue> auditEventTopicSchemas;
    /**
     * The AuditEventHandler to use for queries.
     */
    private final AuditEventHandler<?> queryHandler;
    /**
     * Indicates the current lifecycle state of this AuditService.
     */
    private volatile LifecycleState lifecycleState = LifecycleState.STARTING;

    /**
     * Constructs a new instance.
     *
     * @param configuration
     *          User-facing configuration.
     * @param auditEventTopicSchemas
     *          Meta-data describing the types of events this AuditService can receive.
     *          Passing the map to this constructor effectively transfers ownership to this object and neither
     *          it nor its contents should not be updated further by code outside of this class thereafter.
     * @param auditEventHandlersByName
     *          Map of all configured AuditEventHandlers indexed by their instance name.
     *          Passing the map to this constructor effectively transfers ownership to this object and neither
     *          it nor its contents should not be updated further by code outside of this class thereafter.
     * @param auditEventHandlersByTopic
     *          Lists of AuditEventHandlers that should receive events for each topic.
     *          Passing the list to this constructor effectively transfers ownership to this object and neither
     *          it nor its contents should not be updated further by code outside of this class thereafter.
     */
    public AuditServiceImpl(
            final AuditServiceConfiguration configuration,
            final Map<String, JsonValue> auditEventTopicSchemas,
            final Map<String, AuditEventHandler<?>> auditEventHandlersByName,
            final Map<String, Set<AuditEventHandler<?>>> auditEventHandlersByTopic) {

        this.auditEventHandlersByTopic = unmodifiableMapSet(auditEventHandlersByTopic);
        this.auditEventHandlersByName = unmodifiableMap(auditEventHandlersByName);

        this.config = new AuditServiceConfiguration(configuration);
        this.auditEventTopicSchemas = unmodifiableMap(auditEventTopicSchemas);

        String queryHandlerName = configuration.getHandlerForQueries();
        if (queryHandlerName != null && this.auditEventHandlersByName.containsKey(queryHandlerName)) {
            queryHandler = this.auditEventHandlersByName.get(queryHandlerName);
        } else {
            queryHandler = new NullQueryHandler(config.getHandlerForQueries());
        }
    }

    private <K, V> Map<K, Set<V>> unmodifiableMapSet(Map<K, Set<V>> map) {
        Map<K, Set<V>> copy = new HashMap<>(map.size());
        for (Map.Entry<K, Set<V>> entry : map.entrySet()) {
            copy.put(entry.getKey(), unmodifiableSet(entry.getValue()));
        }
        return unmodifiableMap(copy);
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleRead(final Context context, final ReadRequest request) {
        try {
            logger.debug("Audit read called for {}", request.getResourcePath());
            checkLifecycleStateIsRunning();
            final String id = request.getResourcePathObject().size() > 1
                    ? request.getResourcePathObject().tail(1).toString()
                    : null;
            final String topic = establishAuditEventTopic(request.getResourcePathObject());
            return queryHandler.readEvent(context, topic, id);
        } catch (Exception e) {
            return adapt(e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleCreate(
            final Context context, final CreateRequest request) {
        try {
            logger.debug("Audit create called for {} with {}", request.getResourcePath(), request.getContent().asMap());
            checkLifecycleStateIsRunning();
            if (context.containsContext(AuditingContext.class)) {
                // Don't audit the audit log
                return newResourceResponse(null, null, request.getContent().copy()).asPromise();
            }

            final String topic = establishAuditEventTopic(request.getResourcePathObject());
            rejectIfMissingTransactionIdOrTimestamp(request);
            final String auditEventId = establishAuditEventId(request);

            Collection<AuditEventHandler<?>> auditEventHandlersForEvent = getAuditEventHandlersForEvent(topic);
            if (auditEventHandlersForEvent.isEmpty()) {
                // if the event is known but not registered with a handler, it's ok to ignore it
                logger.debug("No handler found for the event of topic {}", topic);
                return newResourceResponse(auditEventId, null, request.getContent().copy()).asPromise();
            } else {
                // Otherwise, let the event handlers set the response
                logger.debug("Cascading the event of topic {} to the handlers : {}", topic, auditEventHandlersForEvent);
                Promise<ResourceResponse, ResourceException> promise = null;
                for (AuditEventHandler<?> auditEventHandler : auditEventHandlersForEvent) {
                    promise = auditEventHandler.publishEvent(context, topic, request.getContent());
                }
                // TODO CAUD-24 last one wins!
                return promise;
            }
        } catch (Exception e) {
            logger.warn(e.getMessage());
            return adapt(e).asPromise();
        }
    }

    private void rejectIfMissingTransactionIdOrTimestamp(CreateRequest request) throws BadRequestException {
        if (!request.getContent().isDefined(TRANSACTION_ID) || !request.getContent().isDefined(TIMESTAMP)) {
            throw new BadRequestException("The request requires a transactionId and a timestamp");
        }
    }

    private String establishAuditEventId(CreateRequest request) {
        String newResourceId = request.getNewResourceId();
        String auditEventId = newResourceId == null || newResourceId.isEmpty()
                ? IdGenerator.DEFAULT.generate()
                : newResourceId;
        request.getContent().put(ResourceResponse.FIELD_CONTENT_ID, auditEventId);
        logger.debug("Audit create id {}", auditEventId);
        return auditEventId;
    }

    private String establishAuditEventTopic(final ResourcePath path) throws ResourceException {
        String topic = path.head(1).toString();
        if (topic == null) {
            throw new BadRequestException("Audit service called without specifying event topic in the identifier");
        }
        if (!auditEventTopicSchemas.containsKey(topic)) {
            throw new NotSupportedException("Audit service called with unknown event topic " + topic);
        }
        return topic;
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleUpdate(
            final Context context, final UpdateRequest request) {
        return notSupported(request).asPromise();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleDelete(final Context context, DeleteRequest request) {
        return notSupported(request).asPromise();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handlePatch(final Context context, final PatchRequest request) {
        return notSupported(request).asPromise();
    }

    @Override
    public Promise<QueryResponse, ResourceException> handleQuery(
            final Context context, final QueryRequest request, final QueryResourceHandler handler) {
        try {
            logger.debug("Audit query called for {}", request.getResourcePath());
            checkLifecycleStateIsRunning();
            final String topic = establishAuditEventTopic(request.getResourcePathObject());
            return queryHandler.queryEvents(context, topic, request, handler);
        } catch (Exception e) {
            return adapt(e).asPromise();
        }
    }

    @Override
    public Promise<ActionResponse, ResourceException> handleAction(final Context context, final ActionRequest request) {
        final String error = String.format("Unable to handle action: %s", request.getAction());
        logger.error(error);
        return new BadRequestException(error).asPromise();
    }

    private Collection<AuditEventHandler<?>> getAuditEventHandlersForEvent(final String auditEvent) {
        if (auditEventHandlersByTopic.containsKey(auditEvent)) {
            return auditEventHandlersByTopic.get(auditEvent);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public AuditServiceConfiguration getConfig() throws ServiceUnavailableException {
        checkLifecycleStateIsRunning();
        return new AuditServiceConfiguration(config);
    }

    @Override
    public AuditEventHandler<?> getRegisteredHandler(String handlerName) throws ServiceUnavailableException {
        checkLifecycleStateIsRunning();
        return auditEventHandlersByName.get(handlerName);
    }

    @Override
    public boolean isAuditing(String topic) throws ServiceUnavailableException {
        checkLifecycleStateIsRunning();
        return !getAuditEventHandlersForEvent(topic).isEmpty();
    }

    @Override
    public Set<String> getKnownTopics() throws ServiceUnavailableException {
        checkLifecycleStateIsRunning();
        return auditEventTopicSchemas.keySet();
    }

    @Override
    public void startup() throws ServiceUnavailableException {
        switch (lifecycleState) {
            case STARTING:
                for (Map.Entry<String, AuditEventHandler<?>> entry : auditEventHandlersByName.entrySet()) {
                    String handlerName = entry.getKey();
                    AuditEventHandler<?> handler = entry.getValue();
                    try {
                        handler.startup();
                    } catch (ResourceException e) {
                        logger.warn("Unable to startup handler " + handlerName,  e);
                    }
                }
                lifecycleState = LifecycleState.RUNNING;
                break;
            case RUNNING:
                // nothing to do
                break;
            case SHUTDOWN:
                throw new ServiceUnavailableException("AuditService cannot be restarted after shutdown");
            default:
                throw new IllegalStateException("AuditService is in an unknown state");
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * NB. This method is not synchronized with respect to {@link #handleCreate} so there is the possibility for
     * an event to be passed to a handler after that handler has been shutdown if external synchronization is not
     * applied. {@link AuditServiceProxy} is expected to be used and applies exactly this kind of synchronization.
     */
    @Override
    public void shutdown() {
        switch (lifecycleState) {
            case STARTING:
                lifecycleState = LifecycleState.SHUTDOWN;
                break;
            case RUNNING:
                for (Map.Entry<String, AuditEventHandler<?>> entry : auditEventHandlersByName.entrySet()) {
                    String handlerName = entry.getKey();
                    AuditEventHandler<?> handler = entry.getValue();
                    try {
                        handler.shutdown();
                    } catch (ResourceException e) {
                        logger.warn("Unable to shutdown handler " + handlerName,  e);
                    }
                }
                lifecycleState = LifecycleState.SHUTDOWN;
                break;
            case SHUTDOWN:
                // nothing to do
                break;
            default:
                throw new IllegalStateException("AuditService is in an unknown state");
        }
    }

    private void checkLifecycleStateIsRunning() throws ServiceUnavailableException {
        if (lifecycleState != LifecycleState.RUNNING) {
            throw new ServiceUnavailableException("AuditService not running");
        }
    }


    /**
     * Indicates the current lifecycle state of this AuditService.
     */
    private enum LifecycleState {
        STARTING, RUNNING, SHUTDOWN
    }

    /**
     * Substitute {@link AuditEventHandler} to use when no query handler is available.
     */
    private class NullQueryHandler implements AuditEventHandler<EventHandlerConfiguration> {

        private final String errorMessage;

        private NullQueryHandler(String handlerForQueries) {
            if (handlerForQueries == null || handlerForQueries.trim().isEmpty()) {
                this.errorMessage = "No handler defined for queries.";
            } else {
                this.errorMessage = "The handler defined for queries, '" + handlerForQueries +
                        "', has not been registered to the audit service.";
            }
        }

        @Override
        public void configure(EventHandlerConfiguration config) throws ResourceException {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public void startup() throws ResourceException {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public void shutdown() throws ResourceException {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public void setAuditEventsMetaData(Map<String, JsonValue> auditEvents) {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public void setDependencyProvider(DependencyProvider dependencyProvider) {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public Class<EventHandlerConfiguration> getConfigurationClass() {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public Promise<ResourceResponse, ResourceException> publishEvent(
                Context context, String topic, JsonValue event) {
            throw new UnsupportedOperationException("Unsupported.");
        }

        @Override
        public Promise<ResourceResponse, ResourceException> readEvent(
                Context context, String topic, String resourceId) {
            return adapt(new AuditException(errorMessage)).asPromise();
        }

        @Override
        public Promise<QueryResponse, ResourceException> queryEvents(
                Context context, String topic, QueryRequest query, QueryResourceHandler handler) {
            return adapt(new AuditException(errorMessage)).asPromise();
        }
    }
}
