package io.example.wallet.web;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.domain.Id;
import io.example.wallet.action.WalletCloseAction;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.action.WalletDepositMoneyAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;

@Controller("/wallets")
public class WalletController {

    /** Auto-release safety net for held leases. Real workloads pick this from the SLA they want. */
    private static final Duration LOCK_MAX_HOLD = Duration.ofSeconds(10);

    private final ActionExecutor executor;
    private final WalletRepository walletRepository;
    private final KeyedLockProvider lockProvider;

    public WalletController(
            ActionExecutor executor, WalletRepository walletRepository, KeyedLockProvider lockProvider) {
        this.executor = executor;
        this.walletRepository = walletRepository;
        this.lockProvider = lockProvider;
    }

    @Serdeable
    public record CreateRequest(UUID ownerId, String currency, BigDecimal initialBalance) {}

    @Serdeable
    public record DepositRequest(BigDecimal amount, String recipient) {}

    @Serdeable
    public record WalletResponse(
            UUID id, UUID ownerId, String currency, BigDecimal balance, String state, Long version) {
        static WalletResponse from(Wallet w) {
            return new WalletResponse(
                    w.id.getValue(), w.ownerId, w.currency.getCurrencyCode(), w.balance, w.state.name(), w.version);
        }
    }

    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public HttpResponse<WalletResponse> create(@Body CreateRequest body) throws Exception {
        final var wallet = executor.execute(
                () -> "rest-user",
                WalletCreateAction.class,
                new WalletCreateAction.Params(
                        body.ownerId(), Currency.getInstance(body.currency()), body.initialBalance()));
        return HttpResponse.created(WalletResponse.from(wallet));
    }

    @Post(value = "/{id}/deposits", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public WalletResponse deposit(@PathVariable UUID id, @Body DepositRequest body) throws Exception {
        final var updated = executor.execute(
                () -> "rest-user",
                WalletDepositMoneyAction.class,
                new WalletDepositMoneyAction.Params(Id.of(Wallet.class, id), body.amount(), body.recipient()));
        return WalletResponse.from(updated);
    }

    @Post(value = "/{id}/close", produces = MediaType.APPLICATION_JSON)
    public WalletResponse close(@PathVariable UUID id) throws Exception {
        final var closed = executor.execute(
                () -> "rest-user", WalletCloseAction.class, new WalletCloseAction.Params(Id.of(Wallet.class, id)));
        return WalletResponse.from(closed);
    }

    @Get(value = "/{id}", produces = MediaType.APPLICATION_JSON)
    public HttpResponse<WalletResponse> get(@PathVariable UUID id) {
        return walletRepository
                .findById(id)
                .map(WalletResponse::from)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
