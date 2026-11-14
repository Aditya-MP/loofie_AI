package com.loofie.core.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.loofie.core.job.domain.Actor;

/** Lets controllers declare an {@code Actor} parameter; it is filled by the active {@link ActorResolver}. */
@Configuration(proxyBeanMethods = false)
class WebActorConfig implements WebMvcConfigurer {

    private final ActorResolver resolver;

    WebActorConfig(ActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return Actor.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return resolver.resolve(request.getNativeRequest(HttpServletRequest.class));
            }
        });
    }
}
