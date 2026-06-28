package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.entity.AuditEvent;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Search + pagination mechanics (filter, explicit count query, page sizing) on the audit list. */
class PaginationSearchIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired AuditService auditService;
    @Autowired AuditEventRepository auditEventRepository;

    @Test
    void search_filtersByQuery() {
        String marker = "PGN" + SEQ.incrementAndGet();
        auditService.recordAs("tester", marker + "_EVENT", "Thing", "id-1", "detail");

        Page<AuditEvent> hits = auditEventRepository.search(marker, PageRequest.of(0, 10));

        assertThat(hits.getTotalElements()).isEqualTo(1);
        assertThat(hits.getContent().getFirst().getEventType()).isEqualTo(marker + "_EVENT");
        assertThat(hits.getContent().getFirst().getActor()).isEqualTo("tester");
    }

    @Test
    void nullQuery_pagesAllRows() {
        for (int i = 0; i < 3; i++) {
            auditService.recordAs("p", "PAGE_EVENT_" + SEQ.incrementAndGet(), "Thing", null, null);
        }
        Page<AuditEvent> page = auditEventRepository.search(null, PageRequest.of(0, 2));
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
    }
}
