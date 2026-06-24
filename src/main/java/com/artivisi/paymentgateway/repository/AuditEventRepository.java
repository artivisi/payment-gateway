package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
}
