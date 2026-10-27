package com.ticarvo.trade.dto;
import com.ticarvo.trade.model.TradeStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class TradeDtos {
    private TradeDtos() {}
    public record Apply(UUID sellerCompanyId,
            @Pattern(regexp="[A-Za-z0-9_-]{32}") String invitationCode) {
        @Override public String toString() { return "Apply[REDACTED]"; }
    }
    public record Resolve(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{32}") String code) {
        @Override public String toString() { return "Resolve[REDACTED]"; }
    }
    public record Decision(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record Invitation(String code, Instant expiresAt) {
        @Override public String toString() { return "Invitation[REDACTED]"; }
    }
    public record Seller(UUID id,String name) {}
    public record Relationship(UUID id,UUID buyerCompanyId,UUID sellerCompanyId,
            TradeStatus status,long version,Instant createdAt,Instant updatedAt) {}
    public record RelationshipPage(List<Relationship> content,int page,int size,long totalElements) {}
    public enum Direction { INCOMING, OUTGOING }
}
