package mindustrytool.servermanager.filter;

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
@Order(200)
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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String uri = exchange.getRequest().getURI().getPath();

        if (uri.isEmpty() || uri.equals("/")) {
            return chain.filter(exchange);
        }

        if (uri.contains("internal-api")) {
            String clientIp = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();

            if (!isInternalIp(clientIp)) {
                log.info("Invalid ip accessing internal-api: " + clientIp);
                return ApiError.forbidden();
            }

            return chain.filter(exchange);
        }

        String securityKey = envConfig.serverConfig().securityKey();

        if (securityKey == null) {
            return ApiError.forbidden("Security is not set");
        }

        String accessToken = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (accessToken == null) {
            log.info("No access token found");
            return ApiError.unauthorized();
        }

        String token = accessToken.replace("Bearer ", "");

        var isTokenValid = validateToken(token, securityKey);

        if (!isTokenValid) {
            return ApiError.forbidden("Invalid token");
        }

        var data = getDataFromToken(token, securityKey);

        return chain.filter(exchange).contextWrite(withRequest(Mono.just(data)));
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

    private boolean isInternalIp(String ip) {
        return ip.startsWith("172.") || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.equals("127.0.0.1");
    }

}
