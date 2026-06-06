package io.example.wallet.repository;

import static io.example.wallet.generated.jooq.public_schema.tables.TransferSteps.TRANSFER_STEPS;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.EntityRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.di.EkbatanRepository;
import io.example.wallet.generated.jooq.public_schema.tables.TransferSteps;
import io.example.wallet.generated.jooq.public_schema.tables.records.TransferStepsRecord;
import io.example.wallet.model.TransferStep;
import io.example.wallet.model.TransferStepBuilder;
import io.example.wallet.model.TransferStepName;
import java.util.UUID;

@EkbatanRepository
public class TransferStepRepository extends EntityRepository<TransferStep, TransferStepsRecord, TransferSteps, UUID> {

    public TransferStepRepository(DatabaseRegistry databaseRegistry) {
        super(TransferStep.class, TRANSFER_STEPS, TRANSFER_STEPS.ID, databaseRegistry);
    }

    public boolean existsStep(UUID transferId, TransferStepName step) {
        return existsWhere(TRANSFER_STEPS.TRANSFER_ID.eq(transferId).and(TRANSFER_STEPS.STEP.eq(step.name())));
    }

    @Override
    public TransferStep fromRecord(TransferStepsRecord r) {
        return TransferStepBuilder.transferStep()
                .id(Id.of(TransferStep.class, r.getId()))
                .version(r.getVersion())
                .state(GenericState.valueOf(r.getState()))
                .transferId(r.getTransferId())
                .step(TransferStepName.valueOf(r.getStep()))
                .build();
    }

    @Override
    public TransferStepsRecord toRecord(TransferStep step) {
        return new TransferStepsRecord(
                step.id.getValue(), step.version, step.state.name(), step.transferId, step.step.name());
    }
}
