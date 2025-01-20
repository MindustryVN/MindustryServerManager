package mindustrytool.servermanager.filter;

import java.time.Duration;
import java.time.Instant;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.types.data.ServerManagerJwt;
import mindustrytool.servermanager.utils.ApiError;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

@Component
@Order(1000)
@Slf4j
@RequiredArgsConstructor
public class SecurityFilter implements WebFilter {
    private static final String ISSUER = "MindustryTool";

    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;

    public static final Class<?> CONTEXT_KEY = ServerManagerJwt.class;

    public static Mono<ServerManagerJwt> getContext() {
        return Mono.deferContextual(Mono::just)//
                .cast(Context.class)//
                .filter(SecurityFilter::hasContext)//
                .flatMap(SecurityFilter::getContext);
    }

    private static boolean hasContext(Context context) {
        return context.hasKey(CONTEXT_KEY);
    }

    private static Mono<ServerManagerJwt> getContext(Context context) {
        return context.<Mono<ServerManagerJwt>>get(CONTEXT_KEY);
    }

    public static Context withRequest(Mono<? extends ServerManagerJwt> request) {
        return Context.of(CONTEXT_KEY, request);
    }

    @SuppressWarnings("null")
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var start = Instant.now();

        String securityKey = envConfig.serverConfig().securityKey();

        if (securityKey == null) {
            return ApiError.forbidden("Security is not set");
        }

        String accessToken = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (accessToken == null) {
            return ApiError.unauthorized();
        }

        String token = accessToken.replace("Bearer ", "");

        var isTokenValid = validateToken(token, securityKey);

        if (!isTokenValid) {
            return ApiError.forbidden("Invalid token");
        }

        var data = getDataFromToken(token, securityKey);

        return chain.filter(exchange).contextWrite(withRequest(Mono.just(data))).doOnTerminate(() -> {
            var request = exchange.getRequest();
            String requestUrl = request.getURI().toString();
            var status = exchange.getResponse().getStatusCode();
            var method = request.getMethod();
            var duration = Duration.between(start, Instant.now()).toMillis();
            var color = duration < 50 //
                    ? "green"
                    : duration < 200//
                            ? "yellow"
                            : "red";

            var statusColor = status == null //
                    ? "red"
                    : status.value() < 300 //
                            ? "green"
                            : status.value() < 400 //
                                    ? "blue"
                                    : status.value() < 500//
                                            ? "yellow"
                                            : "red";

            log.info("[[%s]%dms[white]] [[%s]%s[white]] %s %s".formatted(color, duration, statusColor, status == null ? "Unknown" : status.value(), method.toString().toUpperCase(), requestUrl));
        });
    }

    public ServerManagerJwt getDataFromToken(String token, String secret) {
        var data = JWT.require(Algorithm.HMAC256(secret))//
                .withIssuer(ISSUER)//
                .build()//
                .verify(token)//
                .getSubject();

        try {
            return objectMapper.readValue(data, ServerManagerJwt.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean validateToken(String token, String secret) {
        try {
            JWT.require(Algorithm.HMAC256(secret))//
                    .withIssuer(ISSUER)//
                    .build()//
                    .verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
