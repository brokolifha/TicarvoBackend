package com.ticarvo.company.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "company_memberships")
public class Membership {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "company_id", nullable = false)
    private Company company;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;
    @Column(nullable = false, length = 30)
    private String role;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    protected Membership() {}
    public Membership(Company company, UUID userId) { this(company, userId, "OWNER"); }
    public Membership(Company company, UUID userId, String role) {
        this.id = UUID.randomUUID(); this.company = company; this.userId = userId;
        this.role = role; this.createdAt = Instant.now();
    }
    public Company getCompany() { return company; }
    public String getRole() { return role; }
    public UUID getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public void changeRole(String role) { this.role = role; }
}
