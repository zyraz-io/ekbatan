package io.ekbatan.core.test.model.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.test.model.Dummy;
import java.math.BigDecimal;

public class DummyMoneyWithdrawnEvent extends ModelEvent<Dummy> {
    public final BigDecimal amount;
    public final BigDecimal newBalance;

    public DummyMoneyWithdrawnEvent(Id<Dummy> dummyId, BigDecimal amount, BigDecimal newBalance) {
        super(dummyId.getValue().toString(), Dummy.class);
        this.amount = amount;
        this.newBalance = newBalance;
    }
}
