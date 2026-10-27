package com.ticarvo.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** --ticarvo.admin.grant=<email> / --ticarvo.admin.revoke=<email>: the only way to change platform admins (ADR-008). */
@Component
public class AdminCommandRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminCommandRunner.class);
    private final AdminAccess admins;
    private final String grant, revoke;
    public AdminCommandRunner(AdminAccess admins, @Value("${ticarvo.admin.grant:}") String grant, @Value("${ticarvo.admin.revoke:}") String revoke) {
        this.admins = admins; this.grant = grant; this.revoke = revoke;
    }
    @Override public void run(ApplicationArguments args) {
        // A failure throws, so startup fails and the command exits non-zero.
        if (!grant.isBlank()) log.info("Platform admin grant: {}", admins.grant(grant));
        if (!revoke.isBlank()) log.info("Platform admin revoke: {}", admins.revoke(revoke));
    }
}
