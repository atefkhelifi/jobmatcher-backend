package ai.jobmatcher.common;

/**
 * The request is valid but would likely create a duplicate. The caller may repeat it with an explicit confirmation. The
 * details (for example the existing records) are returned in the problem body so the client can show them.
 */
public class ConfirmationRequiredException extends RuntimeException {

    private final String code;
    private final transient Object details;

    public ConfirmationRequiredException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
