package com.ticarvo.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** --ticarvo.mfa.reseal=true: reseals every MFA secret with the current key, then exits (REL-M4-MFAKEY). */
@Component
public class MfaCommandRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MfaCommandRunner.class);
    private final MfaService mfa;
    private final boolean reseal;
    public MfaCommandRunner(MfaService mfa, @Value("${ticarvo.mfa.reseal:false}") boolean reseal) { this.mfa = mfa; this.reseal = reseal; }
    @Override public void run(ApplicationArguments args) {
        if (reseal) log.info("MFA secrets resealed with the current key: {}", mfa.resealAll());
    }
}
