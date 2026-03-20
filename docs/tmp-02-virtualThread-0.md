# VirtualThread


```text
public static Thread startVirtualThread(Runnable task) {
    Objects.requireNonNull(task);
    var thread = ThreadBuilders.newVirtualThread(null, null, 0, task);
    thread.start();
    return thread;
}

VirtualThread(Executor scheduler, String name, int characteristics, Runnable task) {
    super(name, characteristics, /*bound*/ false);
    Objects.requireNonNull(task);

    // choose scheduler if not specified
    if (scheduler == null) {
        Thread parent = Thread.currentThread();
        if (parent instanceof VirtualThread vparent) {
            scheduler = vparent.scheduler;
        } else {
            scheduler = DEFAULT_SCHEDULER;
        }
    }

    this.scheduler = scheduler;
    this.cont = new VThreadContinuation(this, task);
    this.runContinuation = this::runContinuation;
}

private static class VThreadContinuation extends Continuation {
    VThreadContinuation(VirtualThread vthread, Runnable task) {
        super(VTHREAD_SCOPE, wrap(vthread, task));
    }
    
    // ...
    
    private static Runnable wrap(VirtualThread vthread, Runnable task) {
        return new Runnable() {
            @Hidden
            public void run() {
                vthread.run(task);
            }
        };
    }
}


java.lang.VirtualThread.run(java.lang.Runnable)    // 🎯🎯🎯🎯 这里虚拟线程开始执行task的入口
@ChangesCurrentThread
private void run(Runnable task) {
    assert state == RUNNING;

    // first mount
    mount();
    notifyJvmtiStart();

    // emit JFR event if enabled
    if (VirtualThreadStartEvent.isTurnedOn()) {
        var event = new VirtualThreadStartEvent();
        event.javaThreadId = threadId();
        event.commit();
    }

    Object bindings = Thread.scopedValueBindings();
    try {
        runWith(bindings, task);
    } catch (Throwable exc) {
        dispatchUncaughtException(exc);
    } finally {
        try {
            // pop any remaining scopes from the stack, this may block
            StackableScope.popAll();

            // emit JFR event if enabled
            if (VirtualThreadEndEvent.isTurnedOn()) {
                var event = new VirtualThreadEndEvent();
                event.javaThreadId = threadId();
                event.commit();
            }

        } finally {
            // last unmount
            notifyJvmtiEnd();
            unmount();

            // final state
            setState(TERMINATED);
        }
    }
}


java.lang.Thread.runWith

@Hidden
@ForceInline
final void runWith(Object bindings, Runnable op) {
    ensureMaterializedForStackWalk(bindings);
    op.run();                                                // 调用Runnable.run()方法
    Reference.reachabilityFence(bindings);
}
```


```text
public void start() {
    start(ThreadContainers.root());
}
    
void start(ThreadContainer container) {
    if (!compareAndSetState(NEW, STARTED)) {
        throw new IllegalThreadStateException("Already started");
    }

    // bind thread to container
    assert threadContainer() == null;
    setThreadContainer(container);

    // start thread
    boolean addedToContainer = false;
    boolean started = false;
    try {
        container.onStart(this);  // may throw
        addedToContainer = true;

        // scoped values may be inherited
        inheritScopedValueBindings(container);

        // submit task to run thread
        submitRunContinuation();
        started = true;
    } finally {
        if (!started) {
            setState(TERMINATED);
            afterTerminate(addedToContainer, /*executed*/false);
        }
    }
}

private void submitRunContinuation() {
    try {
        scheduler.execute(runContinuation);
    } catch (RejectedExecutionException ree) {
        submitFailed(ree);
        throw ree;
    }
}



private void runContinuation() {
    // the carrier must be a platform thread
    if (Thread.currentThread().isVirtual()) {
        throw new WrongThreadException();
    }

    // set state to RUNNING
    int initialState = state();
    if (initialState == STARTED && compareAndSetState(STARTED, RUNNING)) {
        // first run
    } else if (initialState == RUNNABLE && compareAndSetState(RUNNABLE, RUNNING)) {
        // consume parking permit
        setParkPermit(false);
    } else {
        // not runnable
        return;
    }

    // notify JVMTI before mount
    notifyJvmtiMount(/*hide*/true);

    try {
        cont.run();
    } finally {
        if (cont.isDone()) {
            afterTerminate();
        } else {
            afterYield();
        }
    }
}
```