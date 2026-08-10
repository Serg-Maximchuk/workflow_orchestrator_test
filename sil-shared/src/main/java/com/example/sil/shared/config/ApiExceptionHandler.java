package com.example.sil.shared.config;

import com.example.sil.shared.idempotency.IdempotencyKeyReuseException;
import com.example.sil.shared.orders.OrderOrchestrator.NoWaitingOrderException;
import com.example.sil.shared.orders.ServiceOrderService.ServiceOrderNotFoundException;
import com.example.sil.shared.orders.WorkflowAdmin.UnknownDeadLetterWorkException;
import com.example.sil.shared.qualification.ServiceQualificationService.QualificationNotFoundException;
import com.example.sil.shared.supplier.VoipSupplierClient.SupplierUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the failure modes this API has into RFC 7807 problem responses.
 *
 * <p>The distinction that matters: a supplier outage is 503 (the caller may retry the same request
 * later), while a reused idempotency key is 409 (retrying will never help - the caller must fix the
 * key or the body).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QualificationNotFoundException.class)
    ProblemDetail handleNotFound(QualificationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Qualification not found", e.getMessage());
    }

    @ExceptionHandler(ServiceOrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(ServiceOrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Service order not found", e.getMessage());
    }

    @ExceptionHandler(UnknownDeadLetterWorkException.class)
    ProblemDetail handleUnknownDeadLetterWork(UnknownDeadLetterWorkException e) {
        return problem(HttpStatus.NOT_FOUND, "Dead letter work not found", e.getMessage());
    }

    /**
     * A callback for an order that is not waiting is a conflict rather than a 404: the order may
     * well exist, it has simply moved past the point where this message meant anything. Answering
     * 404 would invite the supplier to treat it as "unknown order" and give up on a live one.
     */
    @ExceptionHandler(NoWaitingOrderException.class)
    ProblemDetail handleUnexpectedCallback(NoWaitingOrderException e) {
        return problem(HttpStatus.CONFLICT, "No order waiting for this callback", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail handleKeyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.CONFLICT, "Idempotency-Key reused", e.getMessage());
    }

    @ExceptionHandler(SupplierUnavailableException.class)
    ProblemDetail handleSupplierDown(SupplierUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Supplier unavailable", e.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle(title);
        problemDetail.setDetail(detail);
        return problemDetail;
    }
}
