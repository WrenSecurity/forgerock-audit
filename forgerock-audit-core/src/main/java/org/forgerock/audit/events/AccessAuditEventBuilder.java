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
package org.forgerock.audit.events;

import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.fieldIfNotNull;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.util.Reject.checkNotNull;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.forgerock.http.header.CookieHeader;
import org.forgerock.http.protocol.Cookie;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.Request;
import org.forgerock.services.context.Context;
import org.forgerock.util.Reject;
import org.forgerock.util.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder for audit access events.
 * <p>
 * This builder should not be used directly but be specialized for each product to allow to define
 * new specific fields, e.g
 * <pre>
 * <code>
 * class OpenProductAccessAuditEventBuilder{@code <T extends OpenProductAccessAuditEventBuilder<T>>}
 extends AccessAuditEventBuilder{@code <T>} {
 *
 *    protected OpenProductAccessAuditEventBuilder(DnsUtils dnsUtils) {
 *        super(dnsUtils);
 *    }
 *
 *    public static {@code <T>} OpenProductAccessAuditEventBuilder{@code <?>} productAccessEvent() {
 *       return new OpenProductAccessAuditEventBuilder(new DnsUtils());
 *    }
 *
 *    public T someField(String v) {
 *      jsonValue.put("someField", v);
 *      return self();
 *    }
 *
 *    ...
 * }
 * </code>
 * </pre>
 *
 * @param <T> the type of the builder
 */
public class AccessAuditEventBuilder<T extends AccessAuditEventBuilder<T>> extends AuditEventBuilder<T> {

    public static final String SERVER = "server";
    public static final String CLIENT = "client";
    public static final String HOST = "host";
    public static final String IP = "ip";
    public static final String PORT = "port";
    public static final String REQUEST = "request";
    public static final String PROTOCOL = "protocol";
    public static final String OPERATION = "operation";
    public static final String SECURE = "secure";
    public static final String METHOD = "method";
    public static final String DETAIL = "detail";
    public static final String PATH = "path";
    public static final String QUERY_PARAMETERS = "queryParameters";
    public static final String HEADERS = "headers";
    public static final String HTTP = "http";
    public static final String STATUS = "status";
    public static final String STATUS_CODE = "statusCode";
    public static final String ELAPSED_TIME = "elapsedTime";
    public static final String ELAPSED_TIME_UNITS = "elapsedTimeUnits";
    public static final String RESPONSE = "response";
    public static final String COOKIES = "cookies";

    public static final String CREST_PROTOCOL = "CREST";

    private static final String HOST_HEADER = "Host";
    private static final String COOKIE_HEADER = "Cookie";
    private static final String HTTP_CONTEXT_NAME = "http";
    private static final String CLIENT_CONTEXT_NAME = "client";
    private static final String HTTP_CONTEXT_REMOTE_ADDRESS = "remoteAddress";

    private static final Logger logger = LoggerFactory.getLogger(AccessAuditEventBuilder.class);

    private boolean performReverseDnsLookup = false;

    /**
     * Starts to build an audit access event.
     * <p>
     * Note: it is preferable to use a specialized builder that allow to add
     * fields specific to a product.
     *
     * @return an audit access event builder
     */
    @SuppressWarnings("rawtypes")
    public static AccessAuditEventBuilder<?> accessEvent() {
        return new AccessAuditEventBuilder();
    }

    /**
     * Instructs the builder to lookup client.host from client.ip when populating client details.
     *
     * @return this builder
     */
    public final T withReverseDnsLookup() {
        performReverseDnsLookup = true;
        return self();
    }

    /**
     * @return True if client.host should be looked up from client.ip.
     */
    protected boolean isReverseDnsLookupEnabled() {
        return performReverseDnsLookup;
    }

    /**
     * Sets the provided server values for the event.
     *
     * @param ip the ip of the server.
     * @param port the port of the server.
     * @return this builder
     */
    public final T server(String ip, int port) {
        return server(ip, port, null);
    }

    /**
     * Sets the provided server hostname, ip and port for the event.
     * @param ip the ip of the server.
     * @param port the port of the server.
     * @param host the hostname of the server.
     *
     * @return this builder
     */
    public final T server(String ip, int port, String host) {
        JsonValue object = json(object(
                field(HOST, host),
                field(IP, ip),
                field(PORT, port)));
        jsonValue.put(SERVER, object);
        return self();
    }

    /**
     * Sets the server fields for the event, if the provided
     * <code>Context</code> contains a <code>HttpContext</code>..
     *
     * @param context the CREST context
     * @return this builder
     */
    public final T serverFromHttpContext(Context context) {
        if (context.containsContext(HTTP_CONTEXT_NAME)) {
            JsonValue httpContext = context.getContext(HTTP_CONTEXT_NAME).toJsonValue();
            final String hostHeader = httpContext.get(HEADERS).get(HOST_HEADER).get(0).asString();
            final String[] hostHeaderParts = hostHeader.split(":");
            if (hostHeaderParts.length == 2) {
                server(null, Integer.parseInt(hostHeaderParts[1]), hostHeaderParts[0]);
            }
        }
        return self();
    }

    /**
     * Sets the provided client ip and port for the event.
     *
     * @param ip the ip of the client.
     * @param port the port of the client.
     * @return this builder
     */
    public final T client(String ip, int port) {
        return client(ip, port, null);
    }

    /**
     * Sets the provided client hostname, ip and port for the event.
     *
     * @param ip the ip of the client.
     * @param port the port of the client.
     * @param host the hostname of the client.
     *
     * @return this builder
     */
    public final T client(String ip, int port, String host) {
        JsonValue object = json(object(
                field(HOST, host),
                field(IP, ip),
                field(PORT, port)));
        jsonValue.put(CLIENT, object);
        return self();
    }

    /**
     * Sets the provided client ip for the event.
     *
     * @param ip the ip of the client.
     * @return this builder
     */
    public final T client(String ip) {
        return client(ip, null);
    }

    /**
     * Sets the provided client hostname and ip for the event.
     *
     * @param ip the ip of the client.
     * @param host the hostname of the client.
     *
     * @return this builder
     */
    public final T client(String ip, String host) {
        JsonValue object = json(object(
                field(HOST, host),
                field(IP, ip)));
        jsonValue.put(CLIENT, object);
        return self();
    }

    /**
     * Sets the provided request details for the event.
     *
     * @param protocol the type of request.
     * @param operation the type of operation (e.g. CREATE, READ, UPDATE, DELETE, PATCH, ACTION, or QUERY).
     * @return this builder
     */
    public final T request(String protocol, String operation) {
        JsonValue object = json(object(
                field(PROTOCOL, protocol),
                field(OPERATION, operation)));
        jsonValue.put(REQUEST, object);
        return self();
    }

    /**
     * Sets the provided request details for the event.
     *
     * @param protocol the type of request.
     * @param operation the type of operation (e.g. CREATE, READ, UPDATE, DELETE, PATCH, ACTION, or QUERY).
     * @param detail additional details relating to the request (e.g. the ACTION name or summary of the payload).
     * @return this builder
     */
    public final T request(String protocol, String operation, JsonValue detail) {
        Reject.ifNull(detail);
        JsonValue object = json(object(
                field(PROTOCOL, protocol),
                field(OPERATION, operation),
                field(DETAIL, detail.getObject())));
        jsonValue.put(REQUEST, object);
        return self();
    }

    /**
     * Sets the provided HTTP request fields for the event.
     *
     * @param secure Was the request secure ?
     * @param method the HTTP method.
     * @param path the path of HTTP request.
     * @param queryParameters the query parameters of HTTP request.
     * @param headers the list of headers of HTTP request. The headers are optional.
     * @return this builder
     */
    public final T httpRequest(boolean secure, String method, String path, Map<String, List<String>> queryParameters,
            Map<String, List<String>> headers) {
        final List<String> cookiesHeader = headers.remove(COOKIE_HEADER);
        final List<Cookie> listOfCookies = new LinkedList<>();
        if (cookiesHeader != null && !cookiesHeader.isEmpty()) {
            listOfCookies.addAll(CookieHeader.valueOf(cookiesHeader.get(0)).getCookies());
        }

        final Map<String, String> cookies = new LinkedHashMap<>();
        for (final Cookie cookie : listOfCookies) {
            cookies.put(cookie.getName(), cookie.toString());
        }
        httpRequest(secure, method, path, queryParameters, headers, cookies);
        return self();
    }

    /**
     * Sets the provided HTTP request fields for the event.
     *
     * @param secure Was the request secure ?
     * @param method the HTTP method.
     * @param path the path of HTTP request.
     * @param queryParameters the query parameters of HTTP request.
     * @param headers the list of headers of HTTP request. The headers are optional.
     * @param cookies the list of cookies of HTTP request. The cookies are optional.
     * @return this builder
     */
    public final T httpRequest(boolean secure, String method, String path,  Map<String, List<String>> queryParameters,
            Map<String, List<String>> headers, Map<String, String> cookies) {
        JsonValue object = json(object(
                field(SECURE, secure),
                field(METHOD, method),
                field(PATH, path),
                field(QUERY_PARAMETERS, queryParameters),
                field(HEADERS, headers),
                field(COOKIES, cookies)));
        getOrCreateHttp().put(REQUEST, object.getObject());

        return self();
    }

    /**
     * Sets the provided HTTP fields for the event.
     *
     * @param headers the list of headers of HTTP response. The headers are optional.
     * @return this builder
     */
    public final T httpResponse(Map<String, List<String>> headers) {
        JsonValue object = json(object(
                field(HEADERS, headers)));
        getOrCreateHttp().put(RESPONSE, object);

        return self();
    }

    @VisibleForTesting
    JsonValue getOrCreateHttp() {
        if (jsonValue.get(HTTP).isNull()) {
            jsonValue.put(HTTP, object());
        }
        return jsonValue.get(HTTP);
    }

    @VisibleForTesting
    JsonValue getOrCreateHttpResponse() {
        if (getOrCreateHttp().get(RESPONSE).isNull()) {
            getOrCreateHttp().put(RESPONSE, object());
        }
        return getOrCreateHttp().get(RESPONSE);
    }

    @VisibleForTesting
    JsonValue getOrCreateHttpResponseCookies() {
        final JsonValue httpResponse = getOrCreateHttpResponse();
        JsonValue cookies = httpResponse.get(COOKIES);
        if (cookies.isNull()) {
            httpResponse.put(COOKIES, new ArrayList());
        }
        cookies = httpResponse.get(COOKIES);
        return cookies;
    }

    /**
     * Sets the provided response for the event.
     *
     * @param status the status of the operation.
     * @param statusCode the status code of the operation.
     * @param elapsedTime the execution time of the action.
     * @param elapsedTimeUnits the unit of measure for the execution time value.
     * @return this builder
     */
    public final T response(ResponseStatus status, String statusCode, long elapsedTime, TimeUnit elapsedTimeUnits) {
        JsonValue object = json(object(
                field(STATUS, status == null ? null : status.toString()),
                field(STATUS_CODE, statusCode),
                field(ELAPSED_TIME, elapsedTime),
                field(ELAPSED_TIME_UNITS, elapsedTimeUnits == null ? null : elapsedTimeUnits.name())));
        jsonValue.put(RESPONSE, object);
        return self();
    }

    /**
     * Sets the provided response for the event, with an additional detail.
     *
     * @param status the status of the operation.
     * @param statusCode the status code of the operation.
     * @param elapsedTime the execution time of the action.
     * @param elapsedTimeUnits the unit of measure for the execution time value.
     * @param detail additional details relating to the response (e.g. failure description or summary of the payload).
     * @return this builder
     */
    public final T responseWithDetail(ResponseStatus status, String statusCode,
            long elapsedTime, TimeUnit elapsedTimeUnits, JsonValue detail) {
        Reject.ifNull(detail);
        JsonValue object = json(object(
                field(STATUS, status == null ? null : status.toString()),
                field(STATUS_CODE, statusCode),
                field(ELAPSED_TIME, elapsedTime),
                field(ELAPSED_TIME_UNITS, elapsedTimeUnits == null ? null : elapsedTimeUnits.name()),
                field(DETAIL, detail.getObject())));
        jsonValue.put(RESPONSE, object);
        return self();
    }

    /**
     * Sets client ip, port and host from <code>HttpContext</code>, if the provided
     * <code>Context</code> contains a <code>HttpContext</code>.
     *
     * @param context The CREST context.
     * @return this builder
     */
    public final T clientFromHttpContext(Context context) {
        if (context.containsContext(HTTP_CONTEXT_NAME)) {
            JsonValue httpContext = context.getContext(HTTP_CONTEXT_NAME).toJsonValue();
            String ipAddress = httpContext.get(HTTP_CONTEXT_REMOTE_ADDRESS).asString();
            String hostName = null;
            if (performReverseDnsLookup) {
                try {
                    InetAddress ipAddr = InetAddress.getByName(ipAddress);
                    hostName = ipAddr.getHostName();
                } catch (UnknownHostException e) {
                    logger.debug("Unable to lookup client host name for {}.", ipAddress);
                }
            }
            client(ipAddress, hostName);
        }
        return self();
    }

    /**
     * Sets HTTP method, path, queryString and headers from <code>HttpContext</code>, if the provided
     * <code>Context</code> contains a <code>HttpContext</code>.
     *
     * @param context The CREST context.
     * @return this builder
     */
    public final T httpFromHttpContext(Context context) {
        if (context.containsContext(HTTP_CONTEXT_NAME)) {
            final JsonValue httpContext = context.getContext(HTTP_CONTEXT_NAME).toJsonValue();
            final JsonValue clientContext = context.getContext(CLIENT_CONTEXT_NAME).toJsonValue();
            httpRequest(clientContext.get("isSecure").asBoolean(),
                    httpContext.get("method").asString(),
                    httpContext.get("path").asString(),
                    httpContext.get("parameters").asMapOfList(String.class),
                    httpContext.get("headers").asMapOfList(String.class));
        }
        return self();
    }

    /**
     * Sets request detail from {@link Request}.
     *
     * @param request The CREST request.
     * @return this builder
     */
    public final T requestFromCrestRequest(Request request) {
        final String operation = request.getRequestType().name();
        if (request instanceof ActionRequest) {
            final String action = ((ActionRequest) request).getAction();
            final JsonValue detail = json(object(field("action", action)));
            request(CREST_PROTOCOL, operation, detail);
        } else {
            request(CREST_PROTOCOL, operation);
        }
        return self();
    }

    /**
     * Sets common fields from CREST contexts and request.
     *
     * @param context The CREST context.
     * @param request The CREST request.
     *
     * @see #transactionIdFromRootContext(Context)
     * @see #clientFromHttpContext(Context)
     * @see #httpFromHttpContext(Context)
     * @see #requestFromCrestRequest(Request)
     *
     * @return this builder
     */
    public final T forHttpCrestRequest(Context context, Request request) {
        transactionIdFromRootContext(context);
        clientFromHttpContext(context);
        httpFromHttpContext(context);
        requestFromCrestRequest(request);
        return self();
    }

    private String urlEncode(String string) {
        try {
            return URLEncoder.encode(string, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return string;
        }
    }

    /**
     * The status of the access request.
     */
    public enum ResponseStatus {
        /** The access request was successfully completed. */
        SUCCESSFUL,
        /** The access request was not successfully completed. */
        FAILED
    }
}
