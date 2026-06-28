package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.Permission;
import com.artivisi.paymentgateway.entity.Role;
import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Role administration: create custom roles and assign permissions at runtime. Built-in roles cannot
 * be deleted; a superuser (all-permissions) role's set is fixed; a role in use cannot be deleted.
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final OperatorRepository operatorRepository;
    private final AuditService auditService;

    public RoleService(RoleRepository roleRepository, OperatorRepository operatorRepository,
                       AuditService auditService) {
        this.roleRepository = roleRepository;
        this.operatorRepository = operatorRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Role> list() {
        return roleRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Role get(String id) {
        return roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role not found: " + id));
    }

    @Transactional
    public Role create(String name, String label, Set<Permission> permissions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name is required.");
        }
        if (roleRepository.existsByName(name)) {
            throw new DuplicateException("Role name already exists: " + name);
        }
        Role role = new Role();
        role.setName(name);
        role.setLabel(label);
        role.setBuiltIn(false);
        role.setAllPermissions(false);
        role.setPermissions(new HashSet<>(permissions));
        Role saved = roleRepository.save(role);
        auditService.record("ROLE_CREATED", "Role", saved.getId(), "name=" + name + " perms=" + permissions.size());
        return saved;
    }

    @Transactional
    public void update(String id, String label, Set<Permission> permissions) {
        Role role = get(id);
        if (role.isAllPermissions()) {
            throw new IllegalArgumentException("Superuser role permissions are fixed (all permissions).");
        }
        role.setLabel(label);
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);
        roleRepository.save(role);
        auditService.record("ROLE_UPDATED", "Role", id, "name=" + role.getName() + " perms=" + permissions.size());
    }

    @Transactional
    public void delete(String id) {
        Role role = get(id);
        if (role.isBuiltIn()) {
            throw new IllegalArgumentException("Built-in roles cannot be deleted.");
        }
        if (operatorRepository.countByRoleId(id) > 0) {
            throw new IllegalArgumentException("Role is assigned to operators; reassign them first.");
        }
        roleRepository.delete(role);
        auditService.record("ROLE_DELETED", "Role", id, "name=" + role.getName());
    }
}
