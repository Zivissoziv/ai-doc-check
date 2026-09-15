package com.smartdoc.controller;

import com.smartdoc.dto.PermissionGroupDto;
import com.smartdoc.service.PermissionGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限组接口：管理端 CRUD + 按 URL 参数值取生效权限
 */
@Slf4j
@RestController
@RequestMapping("/api/permission-groups")
@RequiredArgsConstructor
public class PermissionGroupController {

    private final PermissionGroupService permissionGroupService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> listPermissionGroups() {
        List<PermissionGroupDto> groups = permissionGroupService.listAll();
        Map<String, Object> response = new HashMap<>();
        response.put("groups", groups);
        return ResponseEntity.ok(response);
    }

    /**
     * 按 URL 参数值取权限组。查不到属正常情况（未传/参数无效），返回 found=false 由前端兜底为全部可见。
     */
    @GetMapping("/{permKey}")
    public ResponseEntity<Map<String, Object>> getPermissionGroup(@PathVariable String permKey) {
        PermissionGroupDto group = permissionGroupService.getByPermKey(permKey);
        Map<String, Object> response = new HashMap<>();
        if (group == null) {
            response.put("found", false);
            return ResponseEntity.ok(response);
        }
        response.put("found", true);
        response.put("permissionGroup", group);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<PermissionGroupDto> createPermissionGroup(@Valid @RequestBody PermissionGroupDto dto) {
        return ResponseEntity.ok(permissionGroupService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PermissionGroupDto> updatePermissionGroup(
            @PathVariable Long id,
            @Valid @RequestBody PermissionGroupDto dto) {
        return ResponseEntity.ok(permissionGroupService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deletePermissionGroup(@PathVariable Long id) {
        permissionGroupService.delete(id);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }
}
