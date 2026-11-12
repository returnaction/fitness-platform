package io.github.returnaction.fitness.gateway.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GatewayErrorCode {

    NOT_FOUND("not_found", "Resource not found."), INVALID_REQUEST("invalid_request",
            "Check the request format."), INVALID_ACCESS_TOKEN("invalid_access_token",
                    "Authentication required."), ACCESS_DENIED("access_denied", "Access denied."), METHOD_NOT_ALLOWED(
                            "method_not_allowed",
                            "HTTP method is not supported."), UNSUPPORTED_MEDIA_TYPE("unsupported_media_type",
                                    "Content type is not supported."), IDENTITY_UNAVAILABLE("identity_unavailable",
                                            "Identity service is unavailable."), UPSTREAM_TIMEOUT("upstream_timeout",
                                                    "Service response timed out."), UPSTREAM_UNAVAILABLE(
                                                            "upstream_unavailable",
                                                            "Service is unavailable."), INTERNAL_ERROR("internal_error",
                                                                    "Unexpected server error.");

    private final String code;
    private final String message;
}
