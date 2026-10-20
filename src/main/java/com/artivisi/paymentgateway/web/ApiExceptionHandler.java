package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.EscrowLayoutIncompleteException;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.exception.KeyUnencodableException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.exception.UnauthorizedException;
import com.artivisi.paymentgateway.exception.VaNumberBusyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DuplicateException.class)
    public ProblemDetail handleDuplicate(DuplicateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * The number conflict, with its parts as fields rather than only inside the sentence.
     *
     * <p>A consumer no longer composes the VA number, so this response is the only place it can
     * learn which number it collided on and which of its own bills is holding it. Reading that out
     * of prose would make the message format part of the contract, and it would break the moment
     * the wording improved.
     */
    @ExceptionHandler(VaNumberBusyException.class)
    public ProblemDetail handleVaNumberBusy(VaNumberBusyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("VA number busy");
        problem.setProperty("code", "VA_NUMBER_BUSY");
        problem.setProperty("escrowCode", e.getEscrowCode());
        problem.setProperty("vaNumber", e.getVaNumber());
        if (e.getHeldByConsumerReference() != null) {
            problem.setProperty("heldByConsumerReference", e.getHeldByConsumerReference());
        }
        return problem;
    }

    /**
     * A consumer key the escrow's layout cannot hold. Its own code, because it is the one allocation
     * refusal a consumer passes back to whoever asked it for the bill; see
     * {@link KeyUnencodableException}.
     */
    @ExceptionHandler(KeyUnencodableException.class)
    public ProblemDetail handleKeyUnencodable(KeyUnencodableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Key unencodable");
        problem.setProperty("code", "KEY_UNENCODABLE");
        problem.setProperty("field", e.getField());
        problem.setProperty("escrowCode", e.getEscrowCode());
        return problem;
    }

    /** An escrow that cannot allocate: the operator's to fix, never the consumer's sender's. */
    @ExceptionHandler(EscrowLayoutIncompleteException.class)
    public ProblemDetail handleEscrowLayoutIncomplete(EscrowLayoutIncompleteException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Escrow layout incomplete");
        problem.setProperty("code", "ESCROW_LAYOUT_INCOMPLETE");
        problem.setProperty("escrowCode", e.getEscrowCode());
        return problem;
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidPaymentException.class)
    public ProblemDetail handleInvalidPayment(InvalidPaymentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
