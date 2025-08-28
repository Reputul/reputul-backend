package com.reputul.backend.models;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "stripe_events")
public class StripeEventEntity {
    @Id
    private String id;
    private Instant processedAt = Instant.now();

    protected StripeEventEntity() {}

    public StripeEventEntity(String id) {
        this.id = id;
        this.processedAt = Instant.now();
    }

    public String getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
}
