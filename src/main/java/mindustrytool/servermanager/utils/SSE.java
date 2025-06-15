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
        return context.<Mono<Many<String>>>get(CONTEXT_KEY);
    }

    public static Mono<Void> event(String message) {
        return getContext()
                .flatMap(event -> {
                    event.tryEmitNext(message);
                    return Mono.empty();
                });
    }

    public static Flux<String> create(Function<Consumer<String>, Flux<String>> func) {
        Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        return Flux.merge(func.apply(sink::tryEmitNext).onErrorResume(error -> {
            error.printStackTrace();

            if (error instanceof ApiError apiError && apiError.status.value() < 500) {
                sink.tryEmitNext(error.getMessage());
            }

            sink.tryEmitError(error);

            return Mono.empty();

        })//
                .doFinally(_ignore -> sink.tryEmitComplete()), sink.asFlux())
                .contextWrite(context -> context.put(CONTEXT_KEY, Mono.just(sink)));
    }

}
