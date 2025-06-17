package mindustrytool.servermanager.utils;

import java.util.function.Consumer;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;
import reactor.util.context.Context;

@RequiredArgsConstructor
public class SSE {

    public static final Class<?> CONTEXT_KEY = Consumer.class;

    public static Mono<Many<String>> getContext() {
        return Mono.deferContextual(Mono::just)//
                .cast(Context.class)//
                .filter(SSE::hasContext)//
                .flatMap(SSE::getContext);
    }

    private static boolean hasContext(Context context) {
        return context.hasKey(CONTEXT_KEY);
    }

    private static Mono<Many<String>> getContext(Context context) {
        return Mono.just(context.<Many<String>>get(CONTEXT_KEY));
    }

    public static Mono<Void> event(String message) {
        return getContext()
                .doOnNext(event -> event.tryEmitNext(message))
                .then();
    }

    public static Flux<String> create(Function<Consumer<String>, Flux<String>> func) {
        Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

        return Flux.merge(
                sink.asFlux(),
                func.apply(sink::tryEmitNext)
                        .contextWrite(Context.of(CONTEXT_KEY, sink))
                        .onErrorResume(error -> {
                            error.printStackTrace();

                            return Mono.empty();

                        })//
                        .doFinally(_ignore -> sink.tryEmitComplete()));
    }

}
