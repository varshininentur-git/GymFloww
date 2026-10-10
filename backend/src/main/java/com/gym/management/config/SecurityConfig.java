package com.gym.management.config;

import com.gym.management.security.SpaCsrfTokenRequestHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception{return configuration.getAuthenticationManager();}
    @Bean CookieCsrfTokenRepository csrfTokenRepository(@Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,@Value("${server.servlet.session.cookie.same-site:lax}") String sameSite){
        CookieCsrfTokenRepository repository=CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie->cookie.sameSite(sameSite).secure(secureCookie));
        return repository;
    }
    @Bean SecurityContextRepository securityContextRepository(){return new HttpSessionSecurityContextRepository();}
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,CookieCsrfTokenRepository csrfTokens,SecurityContextRepository contexts) throws Exception {
        http.cors(Customizer.withDefaults())
            .csrf(csrf->csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                    @Override public <O extends CsrfFilter> O postProcess(O filter){
                        filter.setAccessDeniedHandler((request,response,exception)->{
                            if(exception instanceof CsrfException){
                                response.setStatus(403);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
                                response.getWriter().write("{\"status\":403,\"code\":\"csrf_invalid\",\"message\":\"Security token expired. Retrying securely.\"}");
                            }else writeJsonError(response,403,"Access denied");
                        });
                        return filter;
                    }
                }))
            .securityContext(context->context.securityContextRepository(contexts).requireExplicitSave(true))
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation(fixation->fixation.changeSessionId()))
            .formLogin(form->form.disable()).httpBasic(basic->basic.disable())
            .authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/auth/csrf","/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/auth/activate-member").permitAll()
                .requestMatchers("/api/member/**").hasRole("MEMBER")
                .requestMatchers("/api/auth/me","/api/auth/logout").authenticated()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/h2-console/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE,"/api/members/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,"/api/members/*/fees").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,"/api/plans/**","/api/trainers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,"/api/plans/**","/api/trainers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE,"/api/plans/**","/api/trainers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,"/api/payments/*/process","/api/payments/*/cancel").hasRole("ADMIN")
                .anyRequest().hasAnyRole("ADMIN","STAFF"))
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,exception)->writeJsonError(response,401,"Authentication required"))
                .accessDeniedHandler((request,response,exception)->writeJsonError(response,403,"Access denied")))
            .headers(headers->headers.frameOptions(frame->frame.sameOrigin()));
        return http.build();
    }
    private void writeJsonError(HttpServletResponse response,int status,String message) throws java.io.IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":"+status+",\"message\":\""+message+"\"}");
    }
}
