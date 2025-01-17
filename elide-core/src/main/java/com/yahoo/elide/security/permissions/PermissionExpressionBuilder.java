/*
 * Copyright 2016, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.security.permissions;

import com.yahoo.elide.core.CheckInstantiator;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.parsers.expression.PermissionExpressionVisitor;
import com.yahoo.elide.security.ChangeSpec;
import com.yahoo.elide.security.PersistentResource;
import com.yahoo.elide.security.RequestScope;
import com.yahoo.elide.security.checks.Check;
import com.yahoo.elide.security.permissions.expressions.AnyFieldExpression;
import com.yahoo.elide.security.permissions.expressions.DeferredCheckExpression;
import com.yahoo.elide.security.permissions.expressions.Expression;
import com.yahoo.elide.security.permissions.expressions.ImmediateCheckExpression;
import com.yahoo.elide.security.permissions.expressions.OrExpression;
import com.yahoo.elide.security.permissions.expressions.SpecificFieldExpression;
import com.yahoo.elide.security.permissions.expressions.UserCheckOnlyExpression;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.antlr.v4.runtime.tree.ParseTree;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.function.Function;

import static com.yahoo.elide.security.permissions.expressions.Expression.Results.FAILURE;

/**
 * Expression builder to parse annotations and express the result as the Expression AST.
 */
public class PermissionExpressionBuilder implements CheckInstantiator {
    private final EntityDictionary entityDictionary;
    private final ExpressionResultCache cache;

    private static final Expressions SUCCESSFUL_EXPRESSIONS = new Expressions(
            OrExpression.SUCCESSFUL_EXPRESSION,
            OrExpression.SUCCESSFUL_EXPRESSION
    );

    /**
     * Constructor.
     *
     * @param cache Cache
     * @param dictionary EntityDictionary
     */
    public PermissionExpressionBuilder(ExpressionResultCache cache, EntityDictionary dictionary) {
        this.cache = cache;
        this.entityDictionary = dictionary;
    }

    /**
     * Build an expression that checks a specific field.
     *
     * @param resource        Resource
     * @param annotationClass Annotation class
     * @param field           Field
     * @param changeSpec      Change spec
     * @param <A>             Type parameter
     * @return Commit and operation expressions
     */
    public <A extends Annotation> Expressions buildSpecificFieldExpressions(final PersistentResource resource,
                                                                            final Class<A> annotationClass,
                                                                            final String field,
                                                                            final ChangeSpec changeSpec) {

        Class<?> resourceClass = resource.getResourceClass();
        if (!entityDictionary.entityHasChecksForPermission(resourceClass, annotationClass)) {
            return SUCCESSFUL_EXPRESSIONS;
        }

        final Function<Check, Expression> deferredCheckFn = getDeferredExpressionFor(resource, changeSpec);
        final Function<Check, Expression> immediateCheckFn = getImmediateExpressionFor(resource, changeSpec);

        final Function<Function<Check, Expression>, Expression> buildExpressionFn =
                (checkFn) -> buildSpecificFieldExpression(
                        resourceClass,
                        annotationClass,
                        field,
                        checkFn
                );

        return new Expressions(
                buildExpressionFn.apply(deferredCheckFn),
                buildExpressionFn.apply(immediateCheckFn)
        );

    }

    /**
     * Build an expression that checks any field on a bean.
     *
     * @param resource        Resource
     * @param annotationClass annotation class
     * @param changeSpec      change spec
     * @param <A>             type parameter
     * @return Commit and operation expressions
     */
    public <A extends Annotation> Expressions buildAnyFieldExpressions(final PersistentResource resource,
                                                                       final Class<A> annotationClass,
                                                                       final ChangeSpec changeSpec) {


        Class<?> resourceClass = resource.getResourceClass();
        if (!entityDictionary.entityHasChecksForPermission(resourceClass, annotationClass)) {
            return SUCCESSFUL_EXPRESSIONS;
        }

        final Function<Check, Expression> deferredCheckFn = getDeferredExpressionFor(resource, changeSpec);
        final Function<Check, Expression> immediateCheckFn = getImmediateExpressionFor(resource, changeSpec);

        final Function<Function<Check, Expression>, Expression> expressionFunction =
                (checkFn) -> buildAnyFieldExpression(
                        resource.getResourceClass(),
                        annotationClass,
                        checkFn
                );

        return new Expressions(
                expressionFunction.apply(deferredCheckFn),
                expressionFunction.apply(immediateCheckFn)
        );
    }

    /**
     * Build an expression that strictly evaluates UserCheck's and ignores other checks for a specific field.
     * <p>
     * NOTE: This method returns _NO_ commit checks.
     *
     * @param resource        Resource
     * @param annotationClass Annotation class
     * @param field           Field to check (if null only check entity-level)
     * @param <A>             type parameter
     * @return User check expression to evaluate
     */
    public <A extends Annotation> Expressions buildUserCheckFieldExpressions(final PersistentResource resource,
                                                                             final Class<A> annotationClass,
                                                                             final String field) {
        Class<?> resourceClass = resource.getResourceClass();
        if (!entityDictionary.entityHasChecksForPermission(resourceClass, annotationClass)) {
            return SUCCESSFUL_EXPRESSIONS;
        }

        final Function<Check, Expression> userCheckFn =
                (check) -> new UserCheckOnlyExpression(
                        check,
                        resource,
                        resource.getRequestScope(),
                        (ChangeSpec) null,
                        cache
                );

        return new Expressions(
                buildSpecificFieldExpression(resourceClass, annotationClass, field, userCheckFn),
                null
        );
    }

    /**
     * Build an expression that strictly evaluates UserCheck's and ignores other checks for an entity.
     * <p>
     * NOTE: This method returns _NO_ commit checks.
     *
     * @param resourceClass   Resource class
     * @param annotationClass Annotation class
     * @param requestScope    Request scope
     * @param <A>             type parameter
     * @return User check expression to evaluate
     */
    public <A extends Annotation> Expressions buildUserCheckAnyExpression(final Class<?> resourceClass,
                                                                          final Class<A> annotationClass,
                                                                          final RequestScope requestScope) {
        final Function<Check, Expression> userCheckFn =
                (check) -> new UserCheckOnlyExpression(
                        check,
                        (PersistentResource) null,
                        requestScope,
                        (ChangeSpec) null,
                        cache
                );

        return new Expressions(
                buildAnyFieldExpression(resourceClass, annotationClass, userCheckFn),
                null
        );
    }

    private Function<Check, Expression> getImmediateExpressionFor(PersistentResource resource, ChangeSpec changeSpec) {
        return (check) -> new ImmediateCheckExpression(
                check,
                resource,
                resource.getRequestScope(),
                changeSpec,
                cache
        );
    }

    private Function<Check, Expression> getDeferredExpressionFor(PersistentResource resource, ChangeSpec changeSpec) {
        return (check) -> new DeferredCheckExpression(
                check,
                resource,
                resource.getRequestScope(),
                changeSpec,
                cache
        );
    }

    /**
     * Builder for specific field expressions.
     *
     * @param <A>             type parameter
     * @param resourceClass   Resource class
     * @param annotationClass Annotation class
     * @param field           Field
     * @param checkFn         Operation check function
     * @return Expressions representing specific field
     */
    private <A extends Annotation> Expression buildSpecificFieldExpression(final Class<?> resourceClass,
                                                                           final Class<A> annotationClass,
                                                                           final String field,
                                                                           final Function<Check, Expression> checkFn) {
        ParseTree classPermissions = entityDictionary.getPermissionsForClass(resourceClass, annotationClass);
        ParseTree fieldPermissions = entityDictionary.getPermissionsForField(resourceClass, field, annotationClass);

        return new SpecificFieldExpression(
                expressionFromParseTree(classPermissions, checkFn),
                expressionFromParseTree(fieldPermissions, checkFn)
        );
    }

    /**
     * Build an expression representing any field on an entity.
     *
     * @param <A>             type parameter
     * @param resourceClass   Resource class
     * @param annotationClass Annotation class
     * @param checkFn         check function
     * @return Expressions
     */
    private <A extends Annotation> Expression buildAnyFieldExpression(final Class<?> resourceClass,
                                                                      final Class<A> annotationClass,
                                                                      final Function<Check, Expression> checkFn) {

        ParseTree classPermissions = entityDictionary.getPermissionsForClass(resourceClass, annotationClass);
        Expression entityExpression = expressionFromParseTree(classPermissions, checkFn);

        OrExpression allFieldsExpression = new OrExpression(FAILURE, null);
        List<String> fields = entityDictionary.getAllFields(resourceClass);
        for (String field : fields) {
            ParseTree fieldPermissions = entityDictionary.getPermissionsForField(resourceClass, field, annotationClass);
            Expression fieldExpression = expressionFromParseTree(fieldPermissions, checkFn);

            allFieldsExpression = new OrExpression(allFieldsExpression, fieldExpression);
        }

        return new AnyFieldExpression(entityExpression, allFieldsExpression);
    }

    private Expression expressionFromParseTree(ParseTree permissions, Function<Check, Expression> checkFn) {
        if (permissions == null) {
            return null;
        }

        return new PermissionExpressionVisitor(entityDictionary, checkFn).visit(permissions);
    }

    /**
     * Structure containing operation-time and commit-time expressions.
     */
    @AllArgsConstructor
    public static class Expressions {
        @Getter private final Expression operationExpression;
        @Getter private final Expression commitExpression;
    }
}
