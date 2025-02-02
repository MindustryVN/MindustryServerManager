package mindustrytool.servermanager.filter;

import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.service.GatewayService;
import mindustrytool.servermanager.service.GatewayService.GatewayClient;
import mindustrytool.servermanager.utils.ApiError;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RequiredArgsConstructor
public class ServerFilter implements WebFilter {

    public static final Class<?> CONTEXT_KEY = GatewayClient.class;

    private final GatewayService gatewayService;

    public static Mono<GatewayClient> getContext() {
        return Mono.deferContextual(Mono::just)//
                .cast(Context.class)//
                .filter(ServerFilter::hasContext)//
                .flatMap(ServerFilter::getContext);
    }

    private static boolean hasContext(Context context) {
        return context.hasKey(CONTEXT_KEY);
    }

    private static Mono<GatewayClient> getContext(Context context) {
        return context.<Mono<GatewayClient>>get(CONTEXT_KEY);
    }

    public static Context withRequest(Mono<? extends GatewayClient> request) {
        return Context.of(CONTEXT_KEY, request);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String uri = exchange.getRequest().getURI().getPath();

        if (uri.startsWith("/internal-api")) {
            var serverIdHeader = exchange.getRequest().getHeaders().getFirst("X-SERVER-ID");

            if (serverIdHeader == null) {
                log.warn("Request to %s not contain X-SERVER-ID header".formatted(uri));

                return ApiError.badRequest("Missing header");
            }

            UUID serverId = UUID.fromString(serverIdHeader);
            var gateway = gatewayService.of(serverId);

            return chain.filter(exchange).contextWrite(withRequest(Mono.just(gateway)));
        }
        return chain.filter(exchange);
    }

}
