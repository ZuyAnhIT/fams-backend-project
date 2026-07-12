package com.fams.modules.notification.repository;

import com.fams.modules.notification.entity.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

  @Query("SELECT d FROM UserDevice d WHERE d.userId = :userId AND d.deletedAt IS NULL")
  List<UserDevice> findActiveByUserId(@Param("userId") UUID userId);

  @Query("SELECT d FROM UserDevice d WHERE d.deviceToken = :token AND d.deletedAt IS NULL")
  Optional<UserDevice> findActiveByToken(@Param("token") String token);
}
