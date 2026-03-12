package io.ekbatan.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public class VirtualClock extends Clock {

    private final Clock realClock;
    private Instant frozenInstant;
    private Instant offsetBase;
    private Instant offsetRealBase;

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
        if (offsetBase != null) {
            return offsetBase.plus(Duration.between(offsetRealBase, realClock.instant()));
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
        clock.offsetBase = this.offsetBase;
        clock.offsetRealBase = this.offsetRealBase;
        return clock;
    }

    public void pause() {
        this.frozenInstant = instant();
        this.offsetBase = null;
        this.offsetRealBase = null;
    }

    public void pauseAt(Instant instant) {
        this.frozenInstant = instant;
        this.offsetBase = null;
        this.offsetRealBase = null;
    }

    public void advance(Duration duration) {
        if (frozenInstant == null) {
            throw new IllegalStateException("Cannot advance: clock is not paused");
        }
        this.frozenInstant = frozenInstant.plus(duration);
    }

    public void resumeFrom(Instant instant) {
        this.frozenInstant = null;
        this.offsetBase = instant;
        this.offsetRealBase = realClock.instant();
    }

    public void resume() {
        this.frozenInstant = null;
        this.offsetBase = null;
        this.offsetRealBase = null;
    }
}
