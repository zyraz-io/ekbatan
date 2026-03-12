package io.ekbatan.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public class VirtualClock extends Clock {

    private final Clock realClock;
    private Instant frozenInstant;
    private Instant virtualAnchor;
    private Instant realAnchor;

    public VirtualClock() {
        this(Clock.systemUTC());
    }

    public VirtualClock(ZoneId zone) {
        this(Clock.system(zone));
    }

    public VirtualClock(Clock realClock) {
        this.realClock = realClock;
    }

    @Override
    public Instant instant() {
        if (frozenInstant != null) {
            return frozenInstant;
        }
        if (virtualAnchor != null) {
            return virtualAnchor.plus(Duration.between(realAnchor, realClock.instant()));
        }
        return realClock.instant();
    }

    @Override
    public ZoneId getZone() {
        return realClock.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        var clock = new VirtualClock(realClock.withZone(zone));
        clock.frozenInstant = this.frozenInstant;
        clock.virtualAnchor = this.virtualAnchor;
        clock.realAnchor = this.realAnchor;
        return clock;
    }

    public void pause() {
        this.frozenInstant = instant();
        this.virtualAnchor = null;
        this.realAnchor = null;
    }

    public void pauseAt(Instant instant) {
        this.frozenInstant = instant;
        this.virtualAnchor = null;
        this.realAnchor = null;
    }

    public void advance(Duration duration) {
        if (frozenInstant == null) {
            throw new IllegalStateException("Cannot advance: clock is not paused");
        }
        this.frozenInstant = frozenInstant.plus(duration);
    }

    public void resumeFrom(Instant instant) {
        this.frozenInstant = null;
        this.virtualAnchor = instant;
        this.realAnchor = realClock.instant();
    }

    public void resume() {
        this.frozenInstant = null;
        this.virtualAnchor = null;
        this.realAnchor = null;
    }
}
