package com.github.rostmyr.jfibers;

/**
 * Rostyslav Myroshnychenko
 * on 31.05.2018.
 */
public final class FiberManagers {
    private static final ThreadLocal<FiberManager> FIBER_MANAGER = ThreadLocal.withInitial(FiberManager::new);

    public static FiberManager current() {
        return FIBER_MANAGER.get();
    }
}
