package io.ekbatan.test.di;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.test.di.shared.widget.action.WidgetCreateAction;
import io.ekbatan.test.di.shared.widget.handler.WidgetCreatedCounterHandler;
import io.ekbatan.test.di.shared.widget.repository.WidgetRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Test-only REST surface that drives the Ekbatan beans the native integration test cares about.
 *
 * <p>Exists because {@link io.quarkus.test.junit.QuarkusIntegrationTest} runs the packaged
 * artifact (jar or native binary) as a separate process. The test JVM is distinct from the app
 * JVM/process, so {@code @Inject} fields can't be wired into the test class. Driving the beans
 * over HTTP is the only way to exercise them from a {@code @QuarkusIntegrationTest} — see
 * {@code quarkusio/quarkus} integration-tests for the same pattern (Flyway, JPA-Postgres, etc.).
 *
 * <p>Lives in {@code src/main/java} (not test) so it's bundled into the native binary; this
 * module produces no production artifact, only the test app, so there's no harm in shipping the
 * endpoint with it.
 */
@Path("/test")
public class EkbatanTestEndpoint {

    @Inject
    ActionExecutor executor;

    @Inject
    WidgetRepository widgetRepository;

    @Inject
    WidgetCreatedCounterHandler counterHandler;

    public record CreateWidgetRequest(String name, String color) {}

    public record WidgetView(String id, String name, String color, String state, long version) {}

    public record HandlerState(int callCount, List<String> handledNames) {}

    @POST
    @Path("/widgets")
    public WidgetView createWidget(CreateWidgetRequest req) throws Exception {
        var widget = executor.execute(
                () -> "test-user", WidgetCreateAction.class, new WidgetCreateAction.Params(req.name(), req.color()));
        return toView(widget);
    }

    @GET
    @Path("/widgets/{id}")
    public Response getWidget(@PathParam("id") String id) {
        return widgetRepository
                .findById(UUID.fromString(id))
                .map(w -> Response.ok(toView(w)).build())
                .orElseGet(() -> Response.status(404).build());
    }

    @GET
    @Path("/handler-state")
    public HandlerState handlerState() {
        return new HandlerState(counterHandler.callCount(), counterHandler.handledNames());
    }

    private static WidgetView toView(io.ekbatan.test.di.shared.widget.models.Widget w) {
        return new WidgetView(w.id.getValue().toString(), w.name, w.color, w.state.name(), w.version);
    }
}
