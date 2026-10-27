package com.ticarvo.trade.controller;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class InvitationController {
    private final InvitationService invitations;
    public InvitationController(InvitationService invitations) { this.invitations=invitations; }
    @PostMapping("/companies/{id}/trade-invitation") @ResponseStatus(HttpStatus.CREATED)
    public TradeDtos.Invitation rotate(@PathVariable UUID id,Authentication auth) { return invitations.rotate(id,auth); }
    @DeleteMapping("/companies/{id}/trade-invitation") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id,Authentication auth) { invitations.revoke(id,auth); }
    @PostMapping("/trade-invitations/resolve")
    public TradeDtos.Seller resolve(@Valid @RequestBody TradeDtos.Resolve request,Authentication auth) { return invitations.resolve(request.code(),auth); }
}
