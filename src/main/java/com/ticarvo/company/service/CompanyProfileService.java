package com.ticarvo.company.service;

import com.ticarvo.company.dto.ProfileDtos;
import com.ticarvo.company.entity.Company;
import com.ticarvo.company.model.SellerStatus;
import com.ticarvo.company.repository.CompanyRepository;
import com.ticarvo.shared.exception.ApiException;
import com.ticarvo.shared.storage.ImageStore;
import com.ticarvo.shared.storage.Images;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** ADR-011: company profile, logo and the seller application; a platform admin decides through CompanyModeration. */
@Service
public class CompanyProfileService {
    private final CompanyRepository companies;
    private final CompanyAccessService access;
    private final ImageStore store;
    private final Clock clock;
    public CompanyProfileService(CompanyRepository companies, CompanyAccessService access, ImageStore store, Clock clock) {
        this.companies = companies; this.access = access; this.store = store; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public ProfileDtos.Profile profile(UUID id, Authentication auth) {
        access.requireMember(id, auth);
        return profile(companies.findById(id).orElseThrow());
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProfileDtos.Profile edit(UUID id, ProfileDtos.Edit request, Authentication auth) {
        var company = locked(id, auth);
        var b = request.billing();
        String[] billing = Arrays.stream(new String[]{b.legalName(), b.taxId(), b.taxOffice(), b.address(), b.city(), b.phone()})
            .map(CompanyProfileService::clean).toArray(String[]::new);
        if (billingLocked(company.getSellerStatus()) && !Arrays.equals(billing, company.billing())) {
            throw new ApiException(HttpStatus.CONFLICT, "BILLING_LOCKED", "Billing details cannot change while the seller application is pending or verified.");
        }
        if (billing[1] != null && !validTaxId(billing[1])) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TAX_ID_INVALID", "The tax or identity number is not valid.");
        }
        company.billing(billing);
        company.showcase(clean(request.description()), clean(request.website()));
        return profile(company);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProfileDtos.Profile apply(UUID id, Authentication auth) {
        var company = locked(id, auth);
        if (company.getSellerStatus() != SellerStatus.NONE && company.getSellerStatus() != SellerStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_STATUS_CONFLICT", "The company has already applied or is verified.");
        }
        if (Arrays.stream(company.billing()).anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFILE_INCOMPLETE", "Fill in every billing detail before applying.");
        }
        company.seller(SellerStatus.PENDING, null, clock.instant(), company.isSellingEnabled());
        return profile(company);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProfileDtos.Profile uploadLogo(UUID id, byte[] content, Authentication auth) {
        // Cheap membership gate first, so outsiders cannot make the server decode images.
        access.requireMember(id, auth);
        var processed = Images.process(content);
        var company = locked(id, auth);
        String key = "logos/" + id + "/" + UUID.randomUUID() + (processed.contentType().equals("image/png") ? ".png" : ".jpg");
        // A logo is shown small: keep the 320px copy when the upload is larger.
        store.put(key, processed.variants().getOrDefault(Images.SIZES.get(0), processed.bytes()));
        replaceLogo(company, key);
        return profile(company);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProfileDtos.Profile deleteLogo(UUID id, Authentication auth) {
        var company = locked(id, auth);
        replaceLogo(company, null);
        return profile(company);
    }
    @Transactional(readOnly = true)
    public ProfileDtos.Logo logo(UUID id, Authentication auth) {
        access.requireMember(id, auth);
        return logo(companies.findById(id).orElseThrow());
    }
    @Transactional(readOnly = true)
    public ProfileDtos.Logo publicLogo(UUID id) {
        return logo(companies.findByIdAndPublicSellerTrueAndSuspendedAtIsNullAndSellingEnabledTrue(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "Seller was not found.")));
    }
    private ProfileDtos.Logo logo(Company company) {
        String key = company.getLogoKey();
        if (key == null || !store.exists(key)) throw new ApiException(HttpStatus.NOT_FOUND, "LOGO_NOT_FOUND", "The company has no logo.");
        return new ProfileDtos.Logo(key.endsWith(".png") ? "image/png" : "image/jpeg", store.get(key));
    }
    private void replaceLogo(Company company, String key) {
        String old = company.getLogoKey();
        company.setLogoKey(key);
        // Delete the old file only once the change is committed; a failed commit keeps the old logo.
        if (old != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { store.delete(old); }
        });
    }
    private Company locked(UUID id, Authentication auth) {
        access.requireAdmin(id, auth);
        access.lockSeller(id);
        // ARCH-M43-01: re-read the role after the lock.
        access.requireAdmin(id, auth);
        return companies.findById(id).orElseThrow();
    }
    static boolean billingLocked(SellerStatus status) { return status == SellerStatus.PENDING || status == SellerStatus.VERIFIED; }
    static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    static ProfileDtos.Profile profile(Company c) {
        String[] b = c.billing();
        return new ProfileDtos.Profile(c.getId(), c.getName(), new ProfileDtos.Billing(b[0], b[1], b[2], b[3], b[4], b[5]),
            c.getDescription(), c.getWebsite(), c.getLogoKey() != null, c.getSellerStatus(), c.getSellerNote(), c.getSellerAppliedAt(), c.isSellingEnabled());
    }
    /** VKN (10 digits, companies) or TCKN (11 digits, sole traders), each with its official check digits. */
    static boolean validTaxId(String id) {
        int[] d = id.chars().map(ch -> ch - '0').toArray();
        if (d.length == 10) {
            int sum = 0;
            for (int i = 0; i < 9; i++) {
                int t = (d[i] + 9 - i) % 10;
                int v = (t * (1 << (9 - i))) % 9;
                sum += t != 0 && v == 0 ? 9 : v;
            }
            return (10 - sum % 10) % 10 == d[9];
        }
        if (d.length != 11 || d[0] == 0) return false;
        int odd = d[0] + d[2] + d[4] + d[6] + d[8], even = d[1] + d[3] + d[5] + d[7];
        return Math.floorMod(odd * 7 - even, 10) == d[9] && (odd + even + d[9]) % 10 == d[10];
    }
}
