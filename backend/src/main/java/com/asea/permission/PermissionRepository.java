package com.asea.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByTenantIdAndActorType(String tenantId, String actorType);

    Optional<Permission> findByTenantIdAndActorTypeAndOperation(String tenantId,
                                                                 String actorType,
                                                                 String operation);

    @Query("SELECT p FROM Permission p WHERE p.tenantId = :tenantId AND p.actorType = :actorType " +
           "AND p.allowed = true")
    List<Permission> findGrantedPermissions(@Param("tenantId") String tenantId,
                                            @Param("actorType") String actorType);
}
