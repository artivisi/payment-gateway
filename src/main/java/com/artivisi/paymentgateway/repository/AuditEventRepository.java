package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    List<AuditEvent> findTop200ByOrderByCreatedAtDesc();
}
