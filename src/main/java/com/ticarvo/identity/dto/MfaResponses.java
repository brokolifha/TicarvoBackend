package com.ticarvo.identity.dto;

import java.util.List;
public final class MfaResponses {
    private MfaResponses() {}
    public record Setup(String secret, String otpauthUri) {
        @Override public String toString() { return "Setup[REDACTED]"; }
    }
    public record RecoveryCodes(List<String> recoveryCodes) {
        @Override public String toString() { return "RecoveryCodes[REDACTED]"; }
    }
}
