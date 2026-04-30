package io.ekbatan.core.action;

import java.security.Principal;
import java.time.Clock;

/**
 * The base class for an Ekbatan action — a single unit of domain work.
 *
 * <h2>Lifetime</h2>
 *
 * <p>An {@code Action} is a <b>singleton</b>. Exactly one instance per concrete subclass exists
 * for the lifetime of the application; that instance is shared across every concurrent invocation.
 * The instance's only state is the {@link Clock} and any constructor-injected dependencies
 * (repositories, services, etc.) — all immutable after construction.
 *
 * <p>Per-execution mutable state — specifically the {@link ActionPlan} accumulating the call's
 * intended persistence changes — is not stored on the instance. It is bound by the framework
 * (see {@link #runIn(ActionPlan, Principal, Object)}) into a {@link java.lang.ScopedValue} for
 * the duration of {@link #perform(Principal, Object)} and accessed via {@link #plan()}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Because the same instance handles concurrent calls, action subclasses <b>must not have
 * mutable instance state</b> beyond what's set in the constructor. Use {@code Atomic*} /
 * {@code Concurrent*} / locks if you legitimately need cross-call shared state (counters, lazy
 * caches, rate limiters); {@link #plan()} for per-call state.
 *
 * <p>Within a single execution, {@link ActionPlan} is <b>not</b> thread-safe. If
 * {@code perform()} spawns parallel work to gather data, the results must be joined before
 * mutating the plan. Only the thread that invoked {@code perform()} should call
 * {@code plan().add(...)} / {@code plan().update(...)} — never call {@code plan()} from a
 * spawned thread.
 *
 * <h2>Discovery</h2>
 *
 * <p>Subclasses are framework-internal: they are <em>not</em> registered as Spring beans and
 * cannot be autowired into application services. The only legitimate consumers of an
 * {@code Action} are {@code ActionExecutor} (production) and {@code ActionSpec} (tests). Both
 * invoke the action via {@link #runIn(ActionPlan, Principal, Object)}.
 *
 * @param <PARAM>  the action's input parameter type
 * @param <RESULT> the action's return type
 */
public abstract class Action<PARAM, RESULT> {

    public final Clock clock;

    private static final ScopedValue<ActionPlan> CURRENT_PLAN = ScopedValue.newInstance();

    protected Action(Clock clock) {
        this.clock = clock;
    }

    /**
     * The plan this execution is accumulating into. Only valid while {@link #perform} is running
     * on the thread that {@link #runIn(ActionPlan, Principal, Object) runIn(...)} was invoked
     * on. Do not call {@code plan()} from any thread spawned inside {@code perform()} — the
     * scoped binding belongs to the invoking thread.
     *
     * @throws IllegalStateException if called outside an active execution scope
     */
    protected final ActionPlan plan() {
        if (!CURRENT_PLAN.isBound()) {
            throw new IllegalStateException("plan() called outside an action execution. "
                    + "Actions must be invoked via ActionExecutor.execute(...) or ActionSpec.execute(...).");
        }
        return CURRENT_PLAN.get();
    }

    /**
     * Framework hook — invokes {@link #perform} with the given {@link ActionPlan} bound as the
     * scoped per-call state. Only {@code ActionExecutor} (production) and {@code ActionSpec}
     * (tests) call this.
     */
    public final RESULT runIn(ActionPlan plan, Principal principal, PARAM params) throws Exception {
        return ScopedValue.where(CURRENT_PLAN, plan).call(() -> perform(principal, params));
    }

    protected abstract RESULT perform(Principal principal, PARAM params) throws Exception;
}
