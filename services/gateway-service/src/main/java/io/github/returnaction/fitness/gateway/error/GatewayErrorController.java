package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.error.dto.ApiErrorResponse;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import io.github.returnaction.fitness.gateway.timeout.DeadlineHttp11Protocol;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.SocketTimeoutException;
import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles container error dispatch, including Tomcat's sendError on an upload
 * timeout.
 */
@RestController
public class GatewayErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ApiErrorResponse> error(HttpServletRequest request) {
        Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        boolean timeout = false;
        for (Throwable current = failure instanceof Throwable throwable
                ? throwable
                : null; current != null; current = current.getCause()) {
            timeout |= current instanceof SocketTimeoutException;
        }
        int status = timeout ? 504 : 500;
        if (timeout) {
            DeadlineHttp11Protocol.stopReadingRequest();
        }
        var code = timeout ? GatewayErrorCode.UPSTREAM_TIMEOUT : GatewayErrorCode.INTERNAL_ERROR;
        String id = RequestIdResolver.getOrCreate(request);
        return ResponseEntity.status(status)
                .header("X-Request-Id", id)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(
                        new ApiErrorResponse.Error(code.getCode(), code.getMessage(), List.of(), id)));
    }
}
