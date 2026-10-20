package ai.jobmatcher.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Consistent RFC 9457 error bodies. Messages never echo user content or stack traces. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ProblemDetail noRoute(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "There is nothing at this address.");
    }

    @ExceptionHandler(ConfirmationRequiredException.class)
    ProblemDetail confirmationRequired(ConfirmationRequiredException e) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Confirmation required", e.getMessage());
        pd.setProperty("code", e.code());
        pd.setProperty("details", e.details());
        return pd;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    ProblemDetail invalid(InvalidInputException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid input", e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "File too large", "The uploaded file exceeds the size limit.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        pd.setProperty("fields", fields);
        return pd;
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class })
    ProblemDetail unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request could not be understood. Check parameter values and the JSON body.");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "Please retry later."));
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail badCredentials(AuthenticationException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid email or password.");
    }

    /** Let Spring Security's own handlers turn this into 401/403. */
    @ExceptionHandler(AccessDeniedException.class)
    void accessDenied(AccessDeniedException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
