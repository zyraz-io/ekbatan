package io.example.wallet.model;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.processor.AutoBuilder;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class TransferStep extends Entity<TransferStep, Id<TransferStep>, GenericState> {

    public final UUID transferId;
    public final TransferStepName step;

    TransferStep(TransferStepBuilder builder) {
        super(builder);
        this.transferId = Validate.notNull(builder.transferId, "transferId cannot be null");
        this.step = Validate.notNull(builder.step, "step cannot be null");
    }

    public static TransferStepBuilder create(UUID transferId, TransferStepName step) {
        return TransferStepBuilder.transferStep()
                .id(Id.random(TransferStep.class))
                .state(GenericState.ACTIVE)
                .transferId(transferId)
                .step(step)
                .withInitialVersion();
    }

    @Override
    public TransferStepBuilder copy() {
        return TransferStepBuilder.transferStep()
                .copyBase(this)
                .transferId(transferId)
                .step(step);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        TransferStep that = (TransferStep) o;
        return transferId.equals(that.transferId) && step == that.step;
    }
}
