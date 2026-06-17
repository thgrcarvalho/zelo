package io.github.thgrcarvalho.zelo.infrastructure.security;

import io.github.thgrcarvalho.zelo.application.error.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the request's {@link AccountPrincipal} (set by {@link SessionAuthFilter})
 * into controller methods that declare it. Unlike the API-key resolver, this one
 * <b>throws 401</b> when no principal is present: any {@code /account} method that
 * declares an {@code AccountPrincipal} parameter is thereby authentication-required,
 * while public methods (signup/login) simply do not declare it.
 */
public class AccountPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AccountPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object principal = webRequest.getAttribute(
                SessionAuthFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (principal == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return principal;
    }
}
