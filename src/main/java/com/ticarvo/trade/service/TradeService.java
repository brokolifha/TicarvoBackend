package com.ticarvo.trade.service;
import com.ticarvo.company.service.CompanyAccess;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.security.PrincipalRateLimit;
import com.ticarvo.shared.validation.PageValidation;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.entity.TradeEvent;
import com.ticarvo.trade.entity.TradeRelationship;
import com.ticarvo.trade.model.TradeStatus;
import com.ticarvo.trade.repository.TradeEventRepository;
import com.ticarvo.trade.repository.TradeRepository;
import com.ticarvo.trade.service.mapper.TradeMapper;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class TradeService implements TradeAccess {
    private final TradeRepository relationships;
    private final TradeEventRepository events;
    private final CompanyAccess companies;
    private final InvitationService invitations;
    private final PrincipalRateLimit limits;
    private final Clock clock;
    public TradeService(TradeRepository relationships,TradeEventRepository events,CompanyAccess companies,
            InvitationService invitations,PrincipalRateLimit limits,Clock clock) {
        this.relationships=relationships; this.events=events; this.companies=companies;
        this.invitations=invitations; this.limits=limits; this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TradeDtos.Relationship apply(UUID buyerId,TradeDtos.Apply request,Authentication auth) {
        UUID actor=companies.requireAdmin(buyerId,auth);
        limits.check(PrincipalRateLimit.Operation.APPLY,actor);
        if((request.sellerCompanyId()==null)==(request.invitationCode()==null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Provide exactly one sellerCompanyId or invitationCode.");
        }
        UUID sellerId;
        if(request.invitationCode()!=null) {
            sellerId=invitations.lockAndValidateForApply(request.invitationCode());
        } else {
            sellerId=request.sellerCompanyId();
            if(buyerId.equals(sellerId)) throw self();
            var seller=companies.lockSeller(sellerId);
            if(!seller.listed() || seller.suspended()) {
                throw new ApiException(HttpStatus.NOT_FOUND,"SELLER_NOT_FOUND","Seller was not found.");
            }
        }
        // ARCH-M43-01: re-read the role after the seller lock; READ_COMMITTED sees a demotion committed meanwhile.
        companies.requireAdmin(buyerId,auth);
        if(buyerId.equals(sellerId)) throw self();
        if(relationships.findPairForUpdate(buyerId,sellerId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,"RELATIONSHIP_EXISTS","A relationship for these companies already exists.");
        }
        var now=clock.instant();
        var relationship=relationships.saveAndFlush(new TradeRelationship(buyerId,sellerId,actor,now));
        events.saveAndFlush(new TradeEvent(relationship,actor,buyerId,null,"APPLIED",now));
        return TradeMapper.toResponse(relationship);
    }
    @Transactional(readOnly=true)
    public TradeDtos.RelationshipPage list(UUID companyId,TradeDtos.Direction direction,TradeStatus status,int page,int size,Authentication auth) {
        companies.requireMember(companyId,auth);
        PageValidation.validatePage(page,size);
        if(direction==null) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Direction is required.");
        var pageable=PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("id")));
        var result=direction==TradeDtos.Direction.OUTGOING ?
            relationships.outgoing(companyId,status,pageable) : relationships.incoming(companyId,status,pageable);
        return new TradeDtos.RelationshipPage(result.stream().map(TradeMapper::toResponse).toList(),page,size,result.getTotalElements());
    }
    @Transactional(readOnly=true)
    public TradeDtos.Relationship get(UUID companyId,UUID relationshipId,Authentication auth) {
        companies.requireMember(companyId,auth);
        return TradeMapper.toResponse(relationships.findVisible(relationshipId,companyId).orElseThrow(TradeService::notFound));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TradeDtos.Relationship decide(UUID companyId,UUID relationshipId,long expectedVersion,TradeStatus target,Authentication auth) {
        UUID actor=companies.requireAdmin(companyId,auth);
        UUID sellerId=relationships.visibleSellerId(relationshipId,companyId).orElseThrow(TradeService::notFound);
        if(!sellerId.equals(companyId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,"SELLER_OWNER_REQUIRED","Only the seller owner may decide this relationship.");
        }
        companies.lockSeller(sellerId);
        companies.requireAdmin(companyId,auth);
        var relationship=relationships.findForUpdate(relationshipId).orElseThrow(TradeService::notFound);
        if(relationship.version()!=expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT,"RELATIONSHIP_VERSION_CONFLICT","Relationship changed. Reload before retrying.");
        }
        var previous=relationship.status();
        boolean valid=(previous==TradeStatus.PENDING && (target==TradeStatus.APPROVED || target==TradeStatus.REJECTED))
            || (previous==TradeStatus.APPROVED && target==TradeStatus.REVOKED);
        if(!valid) throw new ApiException(HttpStatus.CONFLICT,"RELATIONSHIP_STATE_CONFLICT","This transition is not permitted.");
        var now=clock.instant();
        relationship.transition(target,actor,now);
        relationships.flush();
        events.saveAndFlush(new TradeEvent(relationship,actor,companyId,previous,target.name(),now));
        return TradeMapper.toResponse(relationship);
    }
    /** Internal predicate only; future catalog entry points must separately authorize the buyer principal. */
    @Transactional(readOnly=true)
    public boolean hasApprovedRelationship(UUID buyerId,UUID sellerId) {
        return relationships.existsByBuyerCompanyIdAndSellerCompanyIdAndStatus(buyerId,sellerId,TradeStatus.APPROVED);
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"RELATIONSHIP_NOT_FOUND","Relationship was not found."); }
    private static ApiException self() { return new ApiException(HttpStatus.BAD_REQUEST,"SELF_RELATIONSHIP_NOT_ALLOWED","A company cannot apply to itself."); }
}
