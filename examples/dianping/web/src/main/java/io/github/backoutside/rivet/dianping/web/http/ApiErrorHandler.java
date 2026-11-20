package io.github.backoutside.rivet.dianping.web.http;

import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiError(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(RpcTimeoutException.class)
    public ResponseEntity<ApiError> rpcTimeout(RpcTimeoutException exception) {
        LOG.warn("RPC request timed out", exception);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ApiError("RPC_TIMEOUT", "Upstream service timed out"));
    }

    @ExceptionHandler({RpcUnavailableException.class, RpcException.class})
    public ResponseEntity<ApiError> rpcUnavailable(RpcException exception) {
        LOG.warn("RPC service unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("RPC_UNAVAILABLE", "Upstream service is unavailable"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> sessionStoreUnavailable(DataAccessException exception) {
        LOG.warn("Session store unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("SESSION_STORE_UNAVAILABLE", "Session store is unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> otherError(Exception exception) {
        if (exception instanceof ErrorResponse webError) {
            int status = webError.getStatusCode().value();
            String code = status == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
            String message = status == 404 ? "Resource not found" : "Invalid HTTP request";
            return ResponseEntity.status(status).body(new ApiError(code, message));
        }
        LOG.error("Unexpected HTTP error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "Internal server error"));
    }
}
