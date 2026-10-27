package com.ticarvo.company.controller;
import com.ticarvo.company.dto.ProfileDtos;
import com.ticarvo.company.service.CompanyProfileService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

/** ADR-011 company profile, logo and seller application. */
@RestController @RequestMapping("/api/v1")
public class CompanyProfileController {
    private final CompanyProfileService profiles;
    public CompanyProfileController(CompanyProfileService profiles) { this.profiles = profiles; }
    @GetMapping("/companies/{id}/profile")
    public ProfileDtos.Profile profile(@PathVariable UUID id, Authentication auth) { return profiles.profile(id, auth); }
    @PutMapping("/companies/{id}/profile")
    public ProfileDtos.Profile edit(@PathVariable UUID id, @Valid @RequestBody ProfileDtos.Edit request, Authentication auth) {
        return profiles.edit(id, request, auth);
    }
    @PostMapping("/companies/{id}/seller-application")
    public ProfileDtos.Profile apply(@PathVariable UUID id, Authentication auth) { return profiles.apply(id, auth); }
    @PostMapping(path = "/companies/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileDtos.Profile uploadLogo(@PathVariable UUID id, @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        return profiles.uploadLogo(id, file.getBytes(), auth);
    }
    @DeleteMapping("/companies/{id}/logo")
    public ProfileDtos.Profile deleteLogo(@PathVariable UUID id, Authentication auth) { return profiles.deleteLogo(id, auth); }
    @GetMapping("/companies/{id}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable UUID id, Authentication auth) { return image(profiles.logo(id, auth)); }
    @GetMapping("/sellers/{id}/logo")
    public ResponseEntity<byte[]> publicLogo(@PathVariable UUID id) { return image(profiles.publicLogo(id)); }
    static ResponseEntity<byte[]> image(ProfileDtos.Logo logo) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(logo.contentType())).body(logo.bytes());
    }
}
