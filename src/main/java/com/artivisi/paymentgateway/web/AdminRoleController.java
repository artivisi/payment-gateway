package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.Permission;
import com.artivisi.paymentgateway.service.RoleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Runtime role + permission management (requires ROLE_MANAGE). */
@Controller
@RequestMapping("/admin/roles")
public class AdminRoleController {

    private final RoleService roleService;

    public AdminRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("roles", roleService.list());
        return "admin/role/list";
    }

    @GetMapping("/new")
    public String form(Model model) {
        model.addAttribute("permissions", Permission.values());
        return "admin/role/form";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String label,
                         @RequestParam(required = false) List<Permission> permissions,
                         RedirectAttributes ra) {
        try {
            roleService.create(name, label, toSet(permissions));
            ra.addFlashAttribute("message", "Role '" + name + "' created.");
            return "redirect:/admin/roles";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/roles/new";
        }
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable String id, Model model) {
        model.addAttribute("role", roleService.get(id));
        model.addAttribute("permissions", Permission.values());
        return "admin/role/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id,
                         @RequestParam(required = false) String label,
                         @RequestParam(required = false) List<Permission> permissions,
                         RedirectAttributes ra) {
        try {
            roleService.update(id, label, toSet(permissions));
            ra.addFlashAttribute("message", "Role updated.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/roles";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        try {
            roleService.delete(id);
            ra.addFlashAttribute("message", "Role deleted.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/roles";
    }

    private static Set<Permission> toSet(List<Permission> permissions) {
        return permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }
}
