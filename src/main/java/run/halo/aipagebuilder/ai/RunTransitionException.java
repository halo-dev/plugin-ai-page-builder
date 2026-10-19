package run.halo.aipagebuilder.ai;

/** A rejected run command that the HTTP boundary maps to a client status. */
final class RunTransitionException extends RuntimeException {
    enum Kind { CONFLICT, FORBIDDEN }

    private final Kind kind;

    private RunTransitionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    static RunTransitionException conflict(String message) {
        return new RunTransitionException(Kind.CONFLICT, message);
    }

    static RunTransitionException forbidden(String message) {
        return new RunTransitionException(Kind.FORBIDDEN, message);
    }

    Kind kind() {
        return kind;
    }
}
