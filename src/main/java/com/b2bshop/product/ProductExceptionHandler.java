package com.b2bshop.product;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Feature-local error handling: turns every failure of the product endpoints into
 * an RFC 7807 {@link ProblemDetail} response. Highest precedence so these handlers
 * deterministically win over Boot's {@code ProblemDetailsExceptionHandler}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ProductExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<Object> handleProductNotFound(ProductNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler(DuplicateSkuException.class)
    ResponseEntity<Object> handleDuplicateSku(DuplicateSkuException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return problem(HttpStatus.BAD_REQUEST, "Validation failed for one or more fields", request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            // Parameter names are retained at compile time (-parameters), so they
            // identify the offending query parameter ("page", "size", ...).
            String parameterName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> errors.putIfAbsent(
                    (parameterName != null) ? parameterName : "request", error.getDefaultMessage()));
        }
        return problem(HttpStatus.BAD_REQUEST, "Validation failed for one or more request parameters", request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return problem(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + mismatch.getName() + "'",
                    request, null);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        UnrecognizedPropertyException unknownProperty = findCause(ex, UnrecognizedPropertyException.class);
        if (unknownProperty != null) {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put(unknownProperty.getPropertyName(), "unknown property");
            return problem(HttpStatus.BAD_REQUEST, "Unknown JSON property in request body", request, errors);
        }
        return problem(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", request, null);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail, WebRequest request,
            Map<String, String> errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        String description = request.getDescription(false);
        if (description.startsWith("uri=")) {
            body.setInstance(URI.create(description.substring("uri=".length())));
        }
        if (errors != null && !errors.isEmpty()) {
            body.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).body(body);
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
