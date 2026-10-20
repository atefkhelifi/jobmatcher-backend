package ai.jobmatcher.common;

/** Thrown for missing resources and for resources owned by someone else, so ownership is never leaked. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
