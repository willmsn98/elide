/*
 * Copyright 2016, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package example;

import com.yahoo.elide.annotation.Audit;
import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.SharePermission;
import com.yahoo.elide.annotation.UpdatePermission;
import com.yahoo.elide.security.checks.prefab.Role;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToOne;

@Entity
@Audit(action = Audit.Action.CREATE,
        logStatement = "Created with value: {0}",
        logExpressions = {"${auditEntity.value}"})
@Include(rootLevel = true)
@ReadPermission(all = Role.ALL.class)
@CreatePermission(all = Role.ALL.class)
@DeletePermission(all = Role.ALL.class)
@UpdatePermission(all = Role.ALL.class)
@SharePermission(all = Role.ALL.class)
public class AuditEntity {
    private Long id;
    private AuditEntity otherEntity;
    private String value;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @OneToOne
    @Audit(action = Audit.Action.UPDATE,
            logStatement = "Updated relationship (for id: {0}): {1}",
            logExpressions = {"${auditEntity.id}", "${auditEntity.otherEntity.id}"})
    public AuditEntity getOtherEntity() {
        return otherEntity;
    }

    public void setOtherEntity(AuditEntity otherEntity) {
        this.otherEntity = otherEntity;
    }

    @Audit(action = Audit.Action.UPDATE,
            logStatement = "Updated value (for id: {0}): {1}",
            logExpressions = {"${auditEntity.id}", "${auditEntity.value}"})
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "Value: " + value + " relationship: " + ((otherEntity == null) ? "null" : otherEntity.getId());
    }
}
