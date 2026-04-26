package io.ekbatan.test.local_event_handler_mariadb.widget.repository;

import static io.ekbatan.test.local_event_handler.widget.models.WidgetBuilder.widget;
import static io.ekbatan.test.local_event_handler_mariadb.generated.jooq.Tables.WIDGETS;

import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.EmbeddedBitsShardingStrategy;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.test.local_event_handler.widget.models.Widget;
import io.ekbatan.test.local_event_handler.widget.models.WidgetState;
import io.ekbatan.test.local_event_handler_mariadb.generated.jooq.tables.Widgets;
import io.ekbatan.test.local_event_handler_mariadb.generated.jooq.tables.records.WidgetsRecord;
import java.util.UUID;

public class WidgetRepository extends ModelRepository<Widget, WidgetsRecord, Widgets, UUID> {

    public WidgetRepository(DatabaseRegistry databaseRegistry) {
        super(Widget.class, WIDGETS, WIDGETS.ID, databaseRegistry, new EmbeddedBitsShardingStrategy());
    }

    @Override
    public Widget fromRecord(WidgetsRecord record) {
        return widget().id(ShardedId.of(Widget.class, ShardedUUID.from(record.getId())))
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
