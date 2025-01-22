package mindustrytool.servermanager.filter;

import java.time.Duration;
import java.time.Instant;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Order(999)
@Slf4j
@RequiredArgsConstructor
public class RequestFilter implements WebFilter {
    @SuppressWarnings("null")
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var start = Instant.now();

        return chain.filter(exchange)//
                .doOnTerminate(() -> {
                    var request = exchange.getRequest();
                    String requestUrl = request.getURI().getPath().toString();
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

}
