package io.ekbatan.core.test.model.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.test.model.Dummy;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public class DummyCreatedEvent extends ModelEvent<Dummy> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal initialBalance;

    public DummyCreatedEvent(Id<Dummy> dummyId, UUID ownerId, Currency currency, BigDecimal initialBalance) {
        super(dummyId.getValue().toString(), Dummy.class);
        this.ownerId = ownerId;
        this.currency = currency;
        this.initialBalance = initialBalance;
    }
}
