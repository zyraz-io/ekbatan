package io.ekbatan.test.event_pipeline.common.router;

public class EventRoute {

    public final String modelType;
    public final String eventType;
    public final String topic;

    private EventRoute(String modelType, String eventType, String topic) {
        this.modelType = modelType;
        this.eventType = eventType;
        this.topic = topic;
    }

    public static EventRoute forModelType(String modelType, String topic) {
        return new EventRoute(modelType, null, topic);
    }

    public static EventRoute forEventType(String eventType, String topic) {
        return new EventRoute(null, eventType, topic);
    }

    public boolean matches(String eventModelType, String eventEventType) {
        if (modelType != null && modelType.equals(eventModelType)) {
            return true;
        }
        return eventType != null && eventType.equals(eventEventType);
    }
}
