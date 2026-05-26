package io.ekbatan.testsupport.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} that supports pause, advance, and time-skew operations. It is designed for tests that
 * need to drive time-dependent behavior deterministically. Pass an instance to {@link
 * io.ekbatan.core.action.ActionExecutor.Builder#clock(Clock)} in test setup, then manipulate via
 * {@link #pause()} / {@link #advance(Duration)} / {@link #resumeFrom(Instant)} to step through
 * time-sensitive action behavior.
 *
 * <p>Three modes:
 * <ul>
 *   <li><b>Real time</b> (default): delegates to the underlying real clock.</li>
 *   <li><b>Frozen</b> (after {@link #pause()} / {@link #pauseAt(Instant)}): {@link #instant()}
 *       returns the same instant until {@link #advance(Duration)} or a resume call.</li>
 *   <li><b>Skewed</b> (after {@link #resumeFrom(Instant)}): time progresses at real-clock
 *       rate but anchored to a different instant than wall-clock-now.</li>
 * </ul>
 */
public class VirtualClock extends Clock {

    private final Clock realClock;
    private final AtomicReference<State> state;

    /** Constructs a virtual clock backed by {@link Clock#systemUTC()}. */
    public VirtualClock() {
        this(Clock.systemUTC());
    }

    /**
     * Constructs a virtual clock backed by the system clock in the given zone.
     *
     * @param zone the zone for the underlying real clock.
     */
    public VirtualClock(ZoneId zone) {
        this(Clock.system(zone));
    }

    /**
     * Constructs a virtual clock backed by an arbitrary real clock.
     *
     * @param realClock the underlying real clock; used while not paused or skewed.
     */
    public VirtualClock(Clock realClock) {
        this.realClock = realClock;
        this.state = new AtomicReference<>(State.realTime());
    }

    private VirtualClock(Clock realClock, State state) {
        this.realClock = realClock;
        this.state = new AtomicReference<>(state);
    }

    @Override
    public Instant instant() {
        return instant(state.get());
    }

    @Override
    public ZoneId getZone() {
        return realClock.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new VirtualClock(realClock.withZone(zone), state.get());
    }

    /** Freezes the clock at the current instant. */
    public void pause() {
        state.updateAndGet(current -> State.frozen(instant(current)));
    }

    /**
     * Freezes the clock at a specific instant.
     *
     * @param instant the instant to freeze at.
     */
    public void pauseAt(Instant instant) {
        state.set(State.frozen(instant));
    }

    /**
     * Advances the (paused) clock by a duration.
     *
     * @param duration the amount to advance by.
     * @throws IllegalStateException if the clock is not currently paused.
     */
    public void advance(Duration duration) {
        state.updateAndGet(current -> {
            if (current.frozenInstant == null) {
                throw new IllegalStateException("Cannot advance: clock is not paused");
            }
            return State.frozen(current.frozenInstant.plus(duration));
        });
    }

    /**
     * Resumes the clock anchored to an arbitrary instant. Time progresses at real-clock rate but
     * reports a skewed instant.
     *
     * @param instant the virtual now-instant; subsequent reads progress from here.
     */
    public void resumeFrom(Instant instant) {
        state.set(State.skewed(instant, realClock.instant()));
    }

    /** Resumes the clock at real wall-clock time, discarding any pause or skew. */
    public void resume() {
        state.set(State.realTime());
    }

    private Instant instant(State current) {
        if (current.frozenInstant != null) {
            return current.frozenInstant;
        }
        if (current.virtualAnchor != null) {
            return current.virtualAnchor.plus(Duration.between(current.realAnchor, realClock.instant()));
        }
        return realClock.instant();
    }

    private record State(Instant frozenInstant, Instant virtualAnchor, Instant realAnchor) {

        static State realTime() {
            return new State(null, null, null);
        }

        static State frozen(Instant instant) {
            return new State(instant, null, null);
        }

        static State skewed(Instant virtualAnchor, Instant realAnchor) {
            return new State(null, virtualAnchor, realAnchor);
        }
    }
}
