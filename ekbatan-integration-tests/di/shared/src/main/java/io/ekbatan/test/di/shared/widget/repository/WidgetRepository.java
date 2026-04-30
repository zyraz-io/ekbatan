package io.ekbatan.test.di.shared.widget.repository;

import static io.ekbatan.test.di.shared.generated.jooq.public_schema.Tables.WIDGETS;
import static io.ekbatan.test.di.shared.widget.models.WidgetBuilder.widget;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.di.EkbatanRepository;
import io.ekbatan.test.di.shared.generated.jooq.public_schema.tables.Widgets;
import io.ekbatan.test.di.shared.generated.jooq.public_schema.tables.records.WidgetsRecord;
import io.ekbatan.test.di.shared.widget.models.Widget;
import io.ekbatan.test.di.shared.widget.models.WidgetState;
import java.util.UUID;

@EkbatanRepository
public class WidgetRepository extends ModelRepository<Widget, WidgetsRecord, Widgets, UUID> {

    public WidgetRepository(DatabaseRegistry databaseRegistry) {
        super(Widget.class, WIDGETS, WIDGETS.ID, databaseRegistry);
    }

    @Override
    public Widget fromRecord(WidgetsRecord record) {
        return widget().id(Id.of(Widget.class, record.getId()))
                .version(record.getVersion())
                .state(WidgetState.valueOf(record.getState()))
                .name(record.getName())
                .color(record.getColor())
                .createdDate(record.getCreatedDate())
                .updatedDate(record.getUpdatedDate())
                .build();
    }

    @Override
    public WidgetsRecord toRecord(Widget model) {
        return new WidgetsRecord(
                model.id.getValue(),
                model.version,
                model.state.name(),
                model.name,
                model.color,
                model.createdDate,
                model.updatedDate);
    }
}
