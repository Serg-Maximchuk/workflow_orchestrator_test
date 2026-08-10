package com.example.sil.shared.orders;

/**
 * Order lifecycle, named after the TMF641 state machine. The JSON representation uses the TMF
 * spelling ({@code acknowledged}, {@code inProgress}, ...) while the enum stays Java-conventional.
 */
public enum ServiceOrderState {

    /** Accepted and durably stored, but no supplier has been contacted yet. */
    ACKNOWLEDGED("acknowledged"),

    /** A workflow instance is driving the order through the supplier operations. */
    IN_PROGRESS("inProgress"),

    COMPLETED("completed"),

    FAILED("failed"),

    /** Cancelled by the client. Reached only after everything provisioned so far was undone. */
    CANCELLED("cancelled");

    private final String tmfName;

    ServiceOrderState(String tmfName) {
        this.tmfName = tmfName;
    }

    public String tmfName() {
        return tmfName;
    }
}
