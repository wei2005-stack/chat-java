package com.chat.web;

import com.chat.config.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Reads the authenticated principal that {@link AuthFilter} stored on the request. */
public final class AuthContext {

    private AuthContext() {
    }

    public static long userId() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw ApiException.unauthorized("no request context");
        }
        return userId(attrs.getRequest());
    }

    public static long userId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.USER_ID_ATTR);
        if (value == null) {
            throw ApiException.unauthorized("not authenticated");
        }
        return (Long) value;
    }
}
