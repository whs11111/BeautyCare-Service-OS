package com.beautycare.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Snapshot before serialization: robot/scheduler writes must not mutate a response mid-JSON. */
@RestControllerAdvice(assignableTypes = ReceptionController.class)
public class ReceptionSnapshotAdvice implements ResponseBodyAdvice<Object> {
    private final ReceptionController reception;
    public ReceptionSnapshotAdvice(ReceptionController reception) { this.reception = reception; }
    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);
    }
    @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        return reception.responseSnapshot(body);
    }
}
