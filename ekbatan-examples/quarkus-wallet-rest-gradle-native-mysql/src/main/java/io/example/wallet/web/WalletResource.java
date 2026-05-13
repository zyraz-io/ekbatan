package io.example.wallet.web;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.domain.Id;
import io.example.wallet.action.WalletCloseAction;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.action.WalletDepositMoneyAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

@Path("/wallets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WalletResource {

    @Inject
    ActionExecutor executor;

    @Inject
    WalletRepository walletRepository;

    public record CreateRequest(UUID ownerId, String currency, BigDecimal initialBalance) {}

    public record DepositRequest(BigDecimal amount, String recipient) {}

    public record WalletResponse(
            UUID id, UUID ownerId, String currency, BigDecimal balance, String state, Long version) {
        static WalletResponse from(Wallet w) {
            return new WalletResponse(
                    w.id.getValue(), w.ownerId, w.currency.getCurrencyCode(), w.balance, w.state.name(), w.version);
        }
    }

    @POST
    public Response create(CreateRequest body) throws Exception {
        final var wallet = executor.execute(
                () -> "rest-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(
                        body.ownerId(), Currency.getInstance(body.currency()), body.initialBalance()));
        return Response.status(Response.Status.CREATED)
                .entity(WalletResponse.from(wallet))
                .build();
    }

    @POST
    @Path("/{id}/deposits")
    public WalletResponse deposit(@PathParam("id") UUID id, DepositRequest body) throws Exception {
        final var updated = executor.execute(
                () -> "rest-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(Id.of(Wallet.class, id), body.amount(), body.recipient()));
        return WalletResponse.from(updated);
    }

    @POST
    @Path("/{id}/close")
    public WalletResponse close(@PathParam("id") UUID id) throws Exception {
        final var closed = executor.execute(
                () -> "rest-user", WalletCloseAction.class, new WalletCloseAction.Params(Id.of(Wallet.class, id)));
        return WalletResponse.from(closed);
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        return walletRepository
                .findById(id)
                .map(WalletResponse::from)
                .map(view -> Response.ok(view).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }
}
