package edu.course.rehab;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import javax.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class Auth implements HandlerInterceptor {
    private final Store store;
    public Auth(Store store) { this.store=store; }
    public static String hash(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format("%02x",b)); return out.toString();
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String newToken() { byte[] b=new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if (request.getRequestURI().equals("/api/login") || request.getRequestURI().equals("/api/health")) return true;
        String header=request.getHeader("Authorization");
        Map<String,Object> user=header!=null && header.startsWith("Bearer ")?store.authenticate(hash(header.substring(7))):null;
        if(user==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录，或重新登录已过期的会话");
        request.setAttribute("user",user); return true;
    }
}

@Configuration
class WebConfig implements WebMvcConfigurer {
    private final Auth auth;
    WebConfig(Auth auth) { this.auth=auth; }
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(auth).addPathPatterns("/api/**"); }
}
