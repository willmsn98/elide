/*
 * Copyright 2016, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.core;

import com.yahoo.elide.annotation.OnCommit;
import com.yahoo.elide.audit.AuditLogger;
import com.yahoo.elide.core.filter.Predicate;
import com.yahoo.elide.core.pagination.Pagination;
import com.yahoo.elide.core.sort.Sorting;
import com.yahoo.elide.jsonapi.JsonApiMapper;
import com.yahoo.elide.jsonapi.models.JsonApiDocument;
import com.yahoo.elide.security.PermissionExecutor;
import com.yahoo.elide.security.SecurityMode;
import com.yahoo.elide.security.User;
import com.yahoo.elide.security.checks.Check;
import com.yahoo.elide.security.executors.ActivePermissionExecutor;
import lombok.Getter;

import javax.ws.rs.core.MultivaluedMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Request scope object for relaying request-related data to various subsystems.
 */
public class RequestScope implements com.yahoo.elide.security.RequestScope {
    @Getter private final JsonApiDocument jsonApiDocument;
    @Getter private final DataStoreTransaction transaction;
    @Getter private final User user;
    @Getter private final EntityDictionary dictionary;
    @Getter private final JsonApiMapper mapper;
    @Getter private final AuditLogger auditLogger;
    @Getter private final Optional<MultivaluedMap<String, String>> queryParams;
    @Getter private final Map<String, Set<String>> sparseFields;
    @Getter private final Map<String, Set<Predicate>> predicates;
    @Getter private final Pagination pagination;
    @Getter private final Sorting sorting;
    @Getter private final SecurityMode securityMode;
    @Getter private final PermissionExecutor permissionExecutor;
    @Getter private final ObjectEntityCache objectEntityCache;
    @Getter private final Set<PersistentResource> newPersistentResources;
    @Getter private final List<Supplier<String>> failedAuthorizations;
    @Getter private final LinkedHashSet<PersistentResource> dirtyResources;
    final private transient LinkedHashSet<Runnable> commitTriggers;

    /**
     * Create a new RequestScope.
     * @param jsonApiDocument the document for this request
     * @param transaction the transaction for this request
     * @param user the user making this request
     * @param dictionary the entity dictionary
     * @param mapper converts JsonApiDocuments to raw JSON
     * @param auditLogger logger for this request
     * @param queryParams the query parameters
     * @param securityMode the current security mode
     * @param permissionExecutorGenerator the user-provided function that will generate a permissionExecutor
     */
    public RequestScope(JsonApiDocument jsonApiDocument,
                        DataStoreTransaction transaction,
                        User user,
                        EntityDictionary dictionary,
                        JsonApiMapper mapper,
                        AuditLogger auditLogger,
                        MultivaluedMap<String, String> queryParams,
                        SecurityMode securityMode,
                        Function<RequestScope, PermissionExecutor> permissionExecutorGenerator) {
        this.jsonApiDocument = jsonApiDocument;
        this.transaction = transaction;
        this.user = user;
        this.dictionary = dictionary;
        this.mapper = mapper;
        this.auditLogger = auditLogger;
        this.securityMode = securityMode;

        this.objectEntityCache = new ObjectEntityCache();
        this.newPersistentResources = new LinkedHashSet<>();
        this.failedAuthorizations = new ArrayList<>();
        this.dirtyResources = new LinkedHashSet<>();
        this.commitTriggers = new LinkedHashSet<>();

        this.permissionExecutor = (permissionExecutorGenerator == null)
                ? new ActivePermissionExecutor(this)
                : permissionExecutorGenerator.apply(this);

        this.queryParams = (queryParams == null || queryParams.size() == 0)
                ? Optional.empty()
                : Optional.of(queryParams);

        if (this.queryParams.isPresent()) {
            this.sparseFields = parseSparseFields(queryParams);
            this.predicates = Predicate.parseQueryParams(this.dictionary, queryParams);
            this.sorting = Sorting.parseQueryParams(queryParams);
            this.pagination = Pagination.parseQueryParams(queryParams);
        } else {
            this.sparseFields = Collections.emptyMap();
            this.predicates = Collections.emptyMap();
            this.sorting = Sorting.getDefaultEmptyInstance();
            this.pagination = Pagination.getDefaultPagination();
        }

        if (transaction instanceof RequestScopedTransaction) {
            ((RequestScopedTransaction) transaction).setRequestScope(this);
        }
    }

    public RequestScope(JsonApiDocument jsonApiDocument,
                        DataStoreTransaction transaction,
                        User user,
                        EntityDictionary dictionary,
                        JsonApiMapper mapper,
                        AuditLogger auditLogger,
                        SecurityMode securityMode,
                        Function<RequestScope, PermissionExecutor> permissionExecutor) {
        this(
                jsonApiDocument,
                transaction,
                user,
                dictionary,
                mapper,
                auditLogger,
                null,
                securityMode,
                permissionExecutor
        );
    }

    public RequestScope(JsonApiDocument jsonApiDocument,
                        DataStoreTransaction transaction,
                        User user,
                        EntityDictionary dictionary,
                        JsonApiMapper mapper,
                        AuditLogger auditLogger,
                        MultivaluedMap<String, String> queryParams) {
        this(
                jsonApiDocument,
                transaction,
                user,
                dictionary,
                mapper,
                auditLogger,
                queryParams,
                SecurityMode.SECURITY_ACTIVE,
                null
        );
    }

    public RequestScope(JsonApiDocument jsonApiDocument,
                        DataStoreTransaction transaction,
                        User user,
                        EntityDictionary dictionary,
                        JsonApiMapper mapper,
                        AuditLogger auditLogger) {
        this(
                jsonApiDocument,
                transaction,
                user,
                dictionary,
                mapper,
                auditLogger,
                null,
                SecurityMode.SECURITY_ACTIVE,
                null
        );
    }

    /**
     * Outer RequestScope constructor for use by Patch Extension.
     *
     * @param transaction the transaction
     * @param user        the user
     * @param dictionary  the dictionary
     * @param mapper      the mapper
     * @param auditLogger      the logger
     */
    protected RequestScope(DataStoreTransaction transaction,
                           User user,
                           EntityDictionary dictionary,
                           JsonApiMapper mapper,
                           AuditLogger auditLogger) {
        this(null, transaction, user, dictionary, mapper, auditLogger);
    }

    /**
     * Special copy constructor for use by PatchRequestScope.
     *
     * @param jsonApiDocument   the json api document
     * @param outerRequestScope the outer request scope
     */
    protected RequestScope(JsonApiDocument jsonApiDocument, RequestScope outerRequestScope) {
        this.jsonApiDocument = jsonApiDocument;
        this.transaction = outerRequestScope.transaction;
        this.user = outerRequestScope.user;
        this.dictionary = outerRequestScope.dictionary;
        this.mapper = outerRequestScope.mapper;
        this.auditLogger = outerRequestScope.auditLogger;
        this.queryParams = Optional.empty();
        this.sparseFields = Collections.emptyMap();
        this.predicates = Collections.emptyMap();
        this.sorting = Sorting.getDefaultEmptyInstance();
        this.pagination = Pagination.getDefaultPagination();
        this.objectEntityCache = outerRequestScope.objectEntityCache;
        this.securityMode = outerRequestScope.securityMode;
        this.newPersistentResources = outerRequestScope.newPersistentResources;
        this.commitTriggers = outerRequestScope.commitTriggers;
        this.permissionExecutor = outerRequestScope.getPermissionExecutor();
        this.failedAuthorizations = outerRequestScope.failedAuthorizations;
        this.dirtyResources = outerRequestScope.dirtyResources;
    }

    public Set<com.yahoo.elide.security.PersistentResource> getNewResources() {
        return (Set<com.yahoo.elide.security.PersistentResource>) (Set) newPersistentResources;
    }

    /**
     * Parses queryParams and produces sparseFields map.
     * @param queryParams The request query parameters
     * @return Parsed sparseFields map
     */
    private static Map<String, Set<String>> parseSparseFields(MultivaluedMap<String, String> queryParams) {
        Map<String, Set<String>> result = new HashMap<>();

        for (Map.Entry<String, List<String>> kv : queryParams.entrySet()) {
            String key = kv.getKey();
            if (key.startsWith("fields[") && key.endsWith("]")) {
                String type = key.substring(7, key.length() - 1);

                LinkedHashSet<String> filters = new LinkedHashSet<>();
                for (String filterParams : kv.getValue()) {
                    Collections.addAll(filters, filterParams.split(","));
                }

                if (!filters.isEmpty()) {
                    result.put(type, filters);
                }
            }
        }

        return result;
    }

    /**
     * Get predicates for a specific collection type.
     * @param type The name of the type
     * @return The set of predicates for the given type
     */
    public Set<Predicate> getPredicatesOfType(String type) {
        return predicates.getOrDefault(type, Collections.emptySet());
    }

    /**
     * run any deferred post-commit triggers.
     *
     * @see com.yahoo.elide.annotation.CreatePermission
     */
    public void runCommitTriggers() {
        new ArrayList<>(commitTriggers).forEach(Runnable::run);
        commitTriggers.clear();
    }

    public void queueCommitTrigger(PersistentResource resource) {
        queueCommitTrigger(resource, "");
    }

    public void queueCommitTrigger(PersistentResource resource, String fieldName) {
        commitTriggers.add(() -> resource.runTriggers(OnCommit.class, fieldName));
    }

    public void logAuthFailure(Class<? extends Check> check, String type, String id) {
        failedAuthorizations.add(() -> String.format("ForbiddenAccess %s %s#%s",
                check == null ? "" : check.getName(), type, id));
    }

    public void logAuthFailure(Class<? extends Check> check) {
        failedAuthorizations.add(() -> String.format("ForbiddenAccess %s",
                check == null ? "" : check.getName()));
    }

    public String getAuthFailureReason() {
        Set<String> uniqueReasons = new HashSet<>();
        StringBuffer buf = new StringBuffer();
        buf.append("Failed authorization checks:\n");
        for (Supplier<String> authorizationFailure : failedAuthorizations) {
            String reason = authorizationFailure.get();
            if (!uniqueReasons.contains(reason)) {
                buf.append(authorizationFailure.get());
                buf.append("\n");
                uniqueReasons.add(reason);
            }
        }
        return buf.toString();
    }

    public void saveObjects() {
        dirtyResources.stream().map(PersistentResource::getObject).forEach(transaction::save);
    }
}
