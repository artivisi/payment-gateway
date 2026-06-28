package com.artivisi.paymentgateway.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * A data-defined role: a named bundle of {@link Permission}s, assigned 1:1 to operators.
 * Built-in roles cannot be deleted. An all-permissions role is a superuser whose effective
 * permissions are every {@link Permission} (including ones added later), so its stored set is ignored.
 */
@Getter
@Setter
@Entity
@Table(name = "role")
public class Role {

    @Id
    @UuidGenerator
    private String id;

    private String name;

    private String label;

    /** Built-in roles (ADMIN/OPERATOR/AUDITOR) cannot be deleted. */
    @Column(name = "built_in")
    private boolean builtIn;

    /** Grants every permission, present and future (e.g. ADMIN). */
    @Column(name = "all_permissions")
    private boolean allPermissions;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission")
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    /** Effective permissions: all of them for a superuser role, otherwise the assigned set. */
    public Set<Permission> effectivePermissions() {
        return allPermissions ? EnumSet.allOf(Permission.class) : permissions;
    }
}
