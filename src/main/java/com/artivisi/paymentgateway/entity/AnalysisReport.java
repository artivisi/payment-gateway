package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/**
 * An analysis run computed outside the gateway and posted in for display.
 *
 * <p>Deliberately dumb storage: the gateway does not compute these and does not interpret
 * {@link #payload} beyond the schema its {@link #kind} implies. The numbers may come from a legacy
 * system, a warehouse, or a spreadsheet — the gateway's job is to keep the series and render it.
 *
 * <p>Kept as a series rather than a single current value: these run periodically, and the movement
 * between runs is the point.
 */
@Getter
@Setter
@Entity
@Table(name = "analysis_report")
public class AnalysisReport {

    @Id
    @UuidGenerator
    private String id;

    /** Schema selector for {@link #payload}, e.g. {@code collection-aging}. */
    private String kind;

    private String title;

    /** Where the numbers came from, in the author's words — this is provenance, not a foreign key. */
    private String source;

    /** What the run covers, e.g. {@code 2026-Q3}. Optional. */
    private String periodLabel;

    /** When the analysis ran — NOT when it was uploaded. Ordering and trend use this. */
    private Instant generatedAt;

    private String payload;

    @CreationTimestamp
    private Instant createdAt;
}
