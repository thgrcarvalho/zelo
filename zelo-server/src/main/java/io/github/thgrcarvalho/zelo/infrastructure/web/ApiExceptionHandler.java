package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.zelo.application.error.BadRequestException;
import io.github.thgrcarvalho.zelo.application.error.ConflictException;
import io.github.thgrcarvalho.zelo.application.error.ForbiddenException;
import io.github.thgrcarvalho.zelo.application.error.ResourceNotFoundException;
import io.github.thgrcarvalho.zelo.application.error.UnauthorizedException;
import io.github.thgrcarvalho.zelo.domain.dsr.InvalidDsrTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Translates exceptions into a consistent JSON error shape. Scoped by annotation
 * (not package) so it never hijacks any future non-REST controller.
 */
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(ResourceNotFoundException e) {
        return ApiError.of(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError conflict(ConflictException e) {
        return ApiError.of(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError unauthorized(UnauthorizedException e) {
        return ApiError.of(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError forbidden(ForbiddenException e) {
        return ApiError.of(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(InvalidDsrTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError invalidTransition(InvalidDsrTransitionException e) {
        return ApiError.of(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError dataIntegrity(DataIntegrityViolationException e) {
        return ApiError.of(HttpStatus.CONFLICT, "The request conflicts with existing data");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError concurrentModification(ObjectOptimisticLockingFailureException e) {
        // e.g. two fulfill calls racing the same request — the loser lands here.
        return ApiError.of(HttpStatus.CONFLICT, "The request was modified concurrently; please retry");
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError badRequest(BadRequestException e) {
        return ApiError.of(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ApiError.of(HttpStatus.BAD_REQUEST, message.isBlank() ? "Validation failed" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadable(HttpMessageNotReadableException e) {
        return ApiError.of(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError missingParam(MissingServletRequestParameterException e) {
        return ApiError.of(HttpStatus.BAD_REQUEST, "Missing required parameter '" + e.getParameterName() + "'");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    public record ApiError(int status, String error, String message, Instant timestamp) {

        static ApiError of(HttpStatus status, String message) {
            return new ApiError(status.value(), status.getReasonPhrase(), message, Instant.now());
        }
    }
}
