package com.ticarvo.admin.model;

/** Value sets of ADR-008; stored as their names. */
public final class Moderation {
    private Moderation() {}
    public enum PolicyReason { ILLEGAL_PRODUCT, COUNTERFEIT, INTELLECTUAL_PROPERTY, FRAUD, MISLEADING_INFORMATION, OFFENSIVE_CONTENT, TERMS_VIOLATION, OTHER }
    public enum ComplaintStatus {
        OPEN, IN_REVIEW, ACTION_TAKEN, DISMISSED;
        public boolean open() { return this == OPEN || this == IN_REVIEW; }
    }
    public enum AppealStatus { PENDING, UPHELD, REVERSED }
    public enum TargetType { USER, COMPANY }
    public enum RestrictionAction { SUSPEND, RESTORE }
}
