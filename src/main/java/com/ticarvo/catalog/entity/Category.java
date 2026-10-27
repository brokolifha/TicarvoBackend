package com.ticarvo.catalog.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

/** Platform category; two levels, products are assigned to second-level categories only (ADR-007). */
@Entity @Table(name = "catalog_categories")
public class Category {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "parent_id", length = 36) private UUID parentId;
    @Column(length = 60, nullable = false) private String code;
    @Column(length = 100, nullable = false) private String name;
    @Column(nullable = false) private int position;

    protected Category() {}
    public UUID id() { return id; }
    public UUID parentId() { return parentId; }
    public String code() { return code; }
    public String name() { return name; }
    public int position() { return position; }
    public boolean isLeaf() { return parentId != null; }
}
