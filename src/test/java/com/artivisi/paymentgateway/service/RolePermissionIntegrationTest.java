package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.config.OperatorDetailsService;
import com.artivisi.paymentgateway.entity.Permission;
import com.artivisi.paymentgateway.entity.Role;
import com.artivisi.paymentgateway.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Data-driven RBAC: seeded roles, permission-as-authority grants, and role-management guards. */
class RolePermissionIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired RoleService roleService;
    @Autowired RoleRepository roleRepository;
    @Autowired OperatorService operatorService;
    @Autowired OperatorDetailsService operatorDetailsService;

    private Set<String> authorities(String username) {
        return operatorDetailsService.loadUserByUsername(username).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void defaultRoles_areSeededWithExpectedPermissions() {
        Role operator = roleRepository.findByName("OPERATOR").orElseThrow();
        assertThat(operator.getPermissions())
                .contains(Permission.CONSUMER_MANAGE, Permission.WEBHOOK_MANAGE, Permission.AUDIT_VIEW)
                .doesNotContain(Permission.ESCROW_MANAGE, Permission.OPERATOR_MANAGE, Permission.ROLE_MANAGE);

        Role auditor = roleRepository.findByName("AUDITOR").orElseThrow();
        assertThat(auditor.getPermissions())
                .contains(Permission.AUDIT_VIEW, Permission.CONSUMER_VIEW)
                .doesNotContain(Permission.CONSUMER_MANAGE, Permission.WEBHOOK_MANAGE);

        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        assertThat(admin.isAllPermissions()).isTrue();
        assertThat(admin.effectivePermissions()).containsAll(EnumSet.allOf(Permission.class));
    }

    @Test
    void adminOperator_getsEveryPermissionAsAuthority() {
        String u = "rbac-admin-" + SEQ.incrementAndGet();
        operatorService.create(u, "A", roleRepository.findByName("ADMIN").orElseThrow().getId(), "password-123456");
        assertThat(authorities(u))
                .contains("OPERATOR_MANAGE", "ROLE_MANAGE", "BANK_IP_MANAGE", "ESCROW_MANAGE", "AUDIT_VIEW");
    }

    @Test
    void auditorOperator_getsViewOnlyAuthorities() {
        String u = "rbac-auditor-" + SEQ.incrementAndGet();
        operatorService.create(u, "A", roleRepository.findByName("AUDITOR").orElseThrow().getId(), "password-123456");
        assertThat(authorities(u))
                .contains("AUDIT_VIEW", "CONSUMER_VIEW")
                .doesNotContain("CONSUMER_MANAGE", "OPERATOR_MANAGE", "WEBHOOK_MANAGE");
    }

    @Test
    void customRole_grantsExactlyItsPermissions() {
        int n = SEQ.incrementAndGet();
        Role role = roleService.create("SUPPORT-" + n, "Support",
                EnumSet.of(Permission.CHARGE_VIEW, Permission.WEBHOOK_VIEW));
        String u = "rbac-support-" + n;
        operatorService.create(u, "S", role.getId(), "password-123456");
        assertThat(authorities(u)).containsExactlyInAnyOrder("CHARGE_VIEW", "WEBHOOK_VIEW");
    }

    @Test
    void builtInRole_cannotBeDeleted() {
        String adminId = roleRepository.findByName("ADMIN").orElseThrow().getId();
        assertThatThrownBy(() -> roleService.delete(adminId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void superuserRole_permissionsAreFixed() {
        String adminId = roleRepository.findByName("ADMIN").orElseThrow().getId();
        assertThatThrownBy(() -> roleService.update(adminId, "x", EnumSet.noneOf(Permission.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleInUse_cannotBeDeleted() {
        int n = SEQ.incrementAndGet();
        Role role = roleService.create("INUSE-" + n, "InUse", EnumSet.of(Permission.CHARGE_VIEW));
        operatorService.create("rbac-inuse-" + n, "X", role.getId(), "password-123456");
        assertThatThrownBy(() -> roleService.delete(role.getId())).isInstanceOf(IllegalArgumentException.class);
    }
}
