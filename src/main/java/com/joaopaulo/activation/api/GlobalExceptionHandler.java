package com.joaopaulo.activation.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.joaopaulo.activation.application.ActivationRequestNotFoundException;
import com.joaopaulo.activation.application.DuplicateActivationRequestException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ProblemDetail handleNotFound(ActivationRequestNotFoundException e) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, "not-found", "Solicitação não encontrada",
                "Nenhuma solicitação encontrada com o id informado.");
    }

    @ExceptionHandler(DuplicateActivationRequestException.class)
    ProblemDetail handleDuplicate(DuplicateActivationRequestException e) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "duplicate-request", "Solicitação duplicada",
                "O externalId informado já foi usado em uma solicitação com dados diferentes.");
        problem.setProperty("externalId", e.getExternalId());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Erro inesperado", e);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro interno",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = validationProblem("Um ou mais campos não passaram na validação.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = validationProblem("Um ou mais parâmetros não passaram na validação.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(
                ProblemDetails.of(HttpStatus.BAD_REQUEST, "malformed-request", "Requisição inválida",
                        "O corpo da requisição está ausente ou não é um JSON válido."));
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemDetails.withTraceId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail validationProblem(String detail) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos", detail);
    }
}
