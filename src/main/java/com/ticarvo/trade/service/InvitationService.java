package com.ticarvo.trade.service;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.identity.service.CurrentUserProvider;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.PrincipalRateLimit;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.entity.SellerInvitation;
import com.ticarvo.trade.repository.InvitationRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class InvitationService {
    private final InvitationRepository invitations;
    private final CompanyAccess companies;
    private final CurrentUserProvider identity;
    private final PrincipalRateLimit limits;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public InvitationService(InvitationRepository invitations, CompanyAccess companies,
            CurrentUserProvider identity, PrincipalRateLimit limits, Clock clock) {
        this.invitations=invitations; this.companies=companies; this.identity=identity; this.limits=limits; this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TradeDtos.Invitation rotate(UUID sellerId, Authentication auth) {
        UUID actor = companies.requireAdmin(sellerId, auth);
        limits.check(PrincipalRateLimit.Operation.ROTATE,actor);
        companies.lockSeller(sellerId);
        // ARCH-M43-01: re-read the role after the lock.
        companies.requireAdmin(sellerId, auth);
        byte[] bytes = new byte[24]; random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), expiry=now.plus(Duration.ofDays(7));
        var invitation=invitations.findForUpdate(sellerId).orElseGet(() -> new SellerInvitation(sellerId));
        invitation.rotate(hash(code),actor,now,expiry);
        invitations.saveAndFlush(invitation);
        return new TradeDtos.Invitation(code,expiry);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void revoke(UUID sellerId,Authentication auth) {
        companies.requireAdmin(sellerId,auth);
        companies.lockSeller(sellerId);
        companies.requireAdmin(sellerId,auth);
        invitations.findForUpdate(sellerId).ifPresent(invitation -> invitation.revoke(clock.instant()));
    }
    @Transactional(readOnly=true)
    public TradeDtos.Seller resolve(String code,Authentication auth) {
        limits.check(PrincipalRateLimit.Operation.RESOLVE,identity.currentUserId(auth));
        String hash=hash(code);
        UUID sellerId=invitations.sellerIdForHash(hash).orElseThrow(InvitationService::notFound);
        var invitation=invitations.findById(sellerId).orElseThrow(InvitationService::notFound);
        if(!invitation.valid(hash,clock.instant())) throw notFound();
        var seller=companies.summary(sellerId);
        if(seller.suspended()) throw notFound();
        return new TradeDtos.Seller(seller.id(),seller.name());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID lockAndValidateForApply(String code) {
        String hash=hash(code);
        // Scalar prelookup only. After the seller lock, a locking read sees the latest committed row.
        UUID sellerId=invitations.sellerIdForHash(hash).orElseThrow(InvitationService::notFound);
        if(companies.lockSeller(sellerId).suspended()) throw notFound();
        var invitation=invitations.findForUpdate(sellerId).orElseThrow(InvitationService::notFound);
        if(!invitation.valid(hash,clock.instant())) throw notFound();
        return sellerId;
    }
    static String hash(String code) {
        if(code==null || !code.matches("[A-Za-z0-9_-]{32}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Invitation code has an invalid format.");
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND,"INVITATION_NOT_FOUND","Invitation was not found.");
    }
}
