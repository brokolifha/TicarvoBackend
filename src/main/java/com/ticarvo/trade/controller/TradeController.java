package com.ticarvo.trade.controller;
import com.ticarvo.trade.dto.TradeDtos;
import com.ticarvo.trade.model.TradeStatus;
import com.ticarvo.trade.service.TradeService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/companies/{companyId}/trade-relationships")
public class TradeController {
    private final TradeService trade;
    public TradeController(TradeService trade) { this.trade=trade; }
    @PostMapping
    public ResponseEntity<TradeDtos.Relationship> apply(@PathVariable UUID companyId,@Valid @RequestBody TradeDtos.Apply request,Authentication auth) {
        var result=trade.apply(companyId,request,auth);
        return ResponseEntity.created(URI.create("/api/v1/companies/"+companyId+"/trade-relationships/"+result.id())).body(result);
    }
    @GetMapping
    public TradeDtos.RelationshipPage list(@PathVariable UUID companyId,@RequestParam TradeDtos.Direction direction,
            @RequestParam(required=false) TradeStatus status,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,Authentication auth) { return trade.list(companyId,direction,status,page,size,auth); }
    @GetMapping("/{id}")
    public TradeDtos.Relationship get(@PathVariable UUID companyId,@PathVariable UUID id,Authentication auth) { return trade.get(companyId,id,auth); }
    @PostMapping("/{id}/approve")
    public TradeDtos.Relationship approve(@PathVariable UUID companyId,@PathVariable UUID id,
            @Valid @RequestBody TradeDtos.Decision request,Authentication auth) { return trade.decide(companyId,id,request.expectedVersion(),TradeStatus.APPROVED,auth); }
    @PostMapping("/{id}/reject")
    public TradeDtos.Relationship reject(@PathVariable UUID companyId,@PathVariable UUID id,
            @Valid @RequestBody TradeDtos.Decision request,Authentication auth) { return trade.decide(companyId,id,request.expectedVersion(),TradeStatus.REJECTED,auth); }
    @PostMapping("/{id}/revoke")
    public TradeDtos.Relationship revoke(@PathVariable UUID companyId,@PathVariable UUID id,
            @Valid @RequestBody TradeDtos.Decision request,Authentication auth) { return trade.decide(companyId,id,request.expectedVersion(),TradeStatus.REVOKED,auth); }
}
