package com.fams.modules.rbac.repository;

import com.fams.modules.rbac.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByName(String name);

    List<Permission> findAllByOrderByResourceAscActionAsc();
}
