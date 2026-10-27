package com.ticarvo.company.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "companies")
public class Company {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "public_seller", nullable = false)
    private boolean publicSeller;
    @Column(name = "suspended_at")
    private Instant suspendedAt;
    public boolean isSuspended() { return suspendedAt != null; }
    public void suspend(Instant now) { suspendedAt = now; }
    public void restore() { suspendedAt = null; }
    public boolean isPublicSeller() { return publicSeller; }
    public void setPublicSeller(boolean value) { publicSeller = value; }
    protected Company() {}
    public Company(String name) { this.id = UUID.randomUUID(); this.name = name; this.createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
