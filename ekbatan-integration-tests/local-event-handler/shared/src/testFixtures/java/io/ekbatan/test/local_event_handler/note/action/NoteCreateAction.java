package io.ekbatan.test.local_event_handler.note.action;

import static io.ekbatan.test.local_event_handler.note.models.Note.createNote;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.local_event_handler.note.models.Note;
import java.security.Principal;
import java.time.Clock;

public class NoteCreateAction extends Action<NoteCreateAction.Params, Note> {

    public record Params(ShardIdentifier shard, String widgetId, String text) {}

    public NoteCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Note perform(Principal principal, Params params) {
        final var note = createNote(params.shard(), params.widgetId(), params.text(), clock.instant())
                .build();
        return plan.add(note);
    }
}
