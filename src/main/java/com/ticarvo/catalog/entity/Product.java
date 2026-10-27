package com.ticarvo.catalog.entity;
import com.ticarvo.catalog.model.ProductStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity @Table(name="catalog_products")
public class Product {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="seller_company_id",length=36,nullable=false) private UUID sellerCompanyId;
    @Column(length=64,nullable=false) private String sku;
    @Column(length=200,nullable=false) private String name;
    @Column(length=2000,nullable=false) private String description;
    @Enumerated(EnumType.STRING) @Column(length=20,nullable=false) private ProductStatus status;
    @Column(name="public_visible",nullable=false) private boolean publicVisible;
    @Column(name="b2b_amount",precision=19,scale=2,nullable=false) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length=3,nullable=false) private String currency;
    @Column(name="tax_included",nullable=false) private boolean taxIncluded;
    @Version @Column(nullable=false) private long version;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="category_id",length=36) private UUID categoryId;
    // Operational, not content: orders reserve stock without bumping the version the seller edits against (ADR-009).
    @org.hibernate.annotations.OptimisticLock(excluded=true) @Column(name="stock_quantity") private Integer stockQuantity;
    // Consumer price, all taxes included; null means the product is discovery-only for consumers (ADR-010).
    @Column(name="retail_amount",precision=19,scale=2) private BigDecimal retailAmount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="retail_currency",length=3) private String retailCurrency;
    // ADR-011 details; content, so they change only while DRAFT.
    @Column(name="vat_rate",nullable=false) private int vatRate=20;
    @Enumerated(EnumType.STRING) @Column(length=10,nullable=false) private com.ticarvo.catalog.model.ProductUnit unit=com.ticarvo.catalog.model.ProductUnit.PIECE;
    @Column(name="case_pack",nullable=false) private int casePack=1;
    @Column(name="min_quantity",nullable=false) private int minQuantity=1;
    @Column(length=14) private String barcode;
    @Column(length=100) private String brand;

    protected Product() {}
    public Product(UUID seller,String sku,String name,String description,BigDecimal amount,String currency,boolean tax,Instant now) {
        id=UUID.randomUUID();
        sellerCompanyId=seller;
        status=ProductStatus.DRAFT;
        this.sku=sku; this.name=name; this.description=description;
        this.amount=amount; this.currency=currency; taxIncluded=tax;
        createdAt=now.truncatedTo(ChronoUnit.MICROS);
        updatedAt=createdAt;
    }
    public void categorize(UUID categoryId) { this.categoryId=categoryId; }
    public UUID categoryId() { return categoryId; }
    public void edit(String sku,String name,String description,BigDecimal amount,String currency,boolean tax,Instant now) {
        this.sku=sku; this.name=name; this.description=description;
        this.amount=amount; this.currency=currency; taxIncluded=tax;
        touch(now);
    }
    /** Always moves updatedAt forward so a grant-only change still dirties the row and bumps @Version. */
    public void touch(Instant now) {
        Instant candidate=now.truncatedTo(ChronoUnit.MICROS);
        updatedAt=candidate.isAfter(updatedAt)?candidate:updatedAt.plus(1,ChronoUnit.MICROS);
    }
    public void status(ProductStatus value,Instant now) {
        status=value;
        touch(now);
    }
    public void publicVisible(boolean value,Instant now) {
        publicVisible=value;
        touch(now);
    }

    public UUID id() { return id; }
    public UUID seller() { return sellerCompanyId; }
    public ProductStatus status() { return status; }
    public long version() { return version; }
    public boolean publicVisible() { return publicVisible; }
    public String sku() { return sku; }
    public String name() { return name; }
    public String description() { return description; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public boolean taxIncluded() { return taxIncluded; }
    /** null: stock is not tracked. */
    public Integer stockQuantity() { return stockQuantity; }
    public void stock(Integer quantity) { stockQuantity=quantity; }
    public BigDecimal retailAmount() { return retailAmount; }
    public String retailCurrency() { return retailCurrency; }
    /** Content, like the B2B price: callers allow it only while DRAFT. */
    public void retail(BigDecimal amount,String currency) { retailAmount=amount; retailCurrency=currency; }
    public void details(int vatRate,com.ticarvo.catalog.model.ProductUnit unit,int casePack,int minQuantity,String barcode,String brand) {
        this.vatRate=vatRate; this.unit=unit; this.casePack=casePack; this.minQuantity=minQuantity; this.barcode=barcode; this.brand=brand;
    }
    public int vatRate() { return vatRate; }
    public com.ticarvo.catalog.model.ProductUnit unit() { return unit; }
    public int casePack() { return casePack; }
    public int minQuantity() { return minQuantity; }
    public String barcode() { return barcode; }
    public String brand() { return brand; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
