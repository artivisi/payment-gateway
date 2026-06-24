package com.artivisi.paymentgateway.entity;

import com.artivisi.paymentgateway.config.SecretConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/**
 * A client application (registration, academic, ...). Creates charges and receives
 * payment notifications on its webhook URL.
 */
@Getter
@Setter
@Entity
@Table(name = "consumer")
public class Consumer {

    @Id
    @UuidGenerator
    private String id;

    private String code;

    private String name;

    private String clientId;

    @Convert(converter = SecretConverter.class)
    private String clientSecret;

    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    private ConsumerStatus status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
