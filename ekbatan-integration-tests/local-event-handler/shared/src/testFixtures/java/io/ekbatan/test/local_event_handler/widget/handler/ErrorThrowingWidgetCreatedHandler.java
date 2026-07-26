package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throws an {@link Error} rather than an exception, modelling the realistic deployment fault: a
 * handler that touches a class missing from the runtime classpath.
 *
 * <p>{@code NoClassDefFoundError} is deliberate rather than a generic {@code Error}. It is a
 * {@link LinkageError}, which is the subtype the mainstream "fatal throwable" sets (Reactor, RxJava,
 * Scala's {@code NonFatal}) all rethrow - so a fix that adopted one of those sets verbatim would
 * leave this case broken. This handler is what keeps that honest.
 */
public final class ErrorThrowingWidgetCreatedHandler implements EventHandler<WidgetCreatedEvent> {

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public String name() {
        return "error-throwing-widget-created-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<WidgetCreatedEvent> envelope) {
        callCount.incrementAndGet();
        throw new NoClassDefFoundError("com/example/AnOptionalDependency");
    }

    public int callCount() {
        return callCount.get();
    }
}
