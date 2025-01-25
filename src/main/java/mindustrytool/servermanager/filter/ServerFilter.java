package mindustrytool.servermanager.filter;

import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.service.ServerService;
import mindustrytool.servermanager.types.data.MindustryServer;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
@Order(1000)
@Slf4j
@RequiredArgsConstructor
public class ServerFilter implements WebFilter {

    public static final Class<?> CONTEXT_KEY = MindustryServer.class;

    private final ServerService serverService;

    public static Mono<MindustryServer> getContext() {
        return Mono.deferContextual(Mono::just)//
                .cast(Context.class)//
                .filter(ServerFilter::hasContext)//
                .flatMap(ServerFilter::getContext);
    }

    private static boolean hasContext(Context context) {
        return context.hasKey(CONTEXT_KEY);
    }

    private static Mono<MindustryServer> getContext(Context context) {
        return context.<Mono<MindustryServer>>get(CONTEXT_KEY);
    }

    public static Context withRequest(Mono<? extends MindustryServer> request) {
        return Context.of(CONTEXT_KEY, request);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String uri = exchange.getRequest().getURI().getPath();

        if (uri.startsWith("/internal-api")) {
            var serverIdHeader = exchange.getRequest().getHeaders().getFirst("X-SERVER-ID");

            if (serverIdHeader == null) {
                log.warn("Request to %s not contain X-SERVER-ID header".formatted(uri));
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return exchange.getResponse().setComplete();
            }

            UUID serverId = UUID.fromString(serverIdHeader);
            var server = serverService.getServerById(serverId);

            if (server == null) {
                log.warn("Request to %s with invalid X-SERVER-ID: %s header or server not exist".formatted(uri, serverId));
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange).contextWrite(withRequest(Mono.just(server)));
        }
        return chain.filter(exchange);
    }

}
