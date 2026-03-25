# VirtualThread


BaseVirtualThread extends Thread

如下方法都是package可见
```text
sealed abstract class BaseVirtualThread extends Thread permits VirtualThread, ThreadBuilders.BoundVirtualThread {
    
    // Parks the current virtual thread until the parking permit is available or the thread is interrupted. 
    abstract void park();
    
    // Makes available the parking permit to the given this virtual thread.
    abstract void unpark();
    
    // Parks current virtual thread up to the given waiting time until the parking permit is available or the thread is interrupted.
    abstract void parkNanos(long nanos);
}
```

```text
final class VirtualThread extends BaseVirtualThread {
    
    private static final ContinuationScope VTHREAD_SCOPE = new ContinuationScope("VirtualThreads");
    private static final ForkJoinPool DEFAULT_SCHEDULER = createDefaultScheduler();
    private static final ScheduledExecutorService UNPARKER = createDelayedTaskScheduler();
    
    
    // scheduler and continuation
    private final Executor scheduler;
    private final Continuation cont;                           // 🎯🎯🎯 task被封装到Continuation中,由Continuation来控制task的执行/暂停/再执行
    private final Runnable runContinuation;
    
    
    // virtual thread state, accessed by VM
    private volatile int state;
    
    
    
    // interrupt status (read/written by VM)
    volatile boolean interrupted;
    
    // parking permit
    private volatile boolean parkPermit;
    
    // carrier thread when mounted, accessed by VM 正在执行虚拟线程的平台线程
    private volatile Thread carrierThread;
    
    // termination object when joining, created lazily if needed
    private volatile CountDownLatch termination;
    
    //... 
}
```


```text
Virtual thread state and transitions

NEW -> STARTED         // Thread.start
STARTED -> TERMINATED      // failed to start
STARTED -> RUNNING         // first run

RUNNING -> PARKING         // Thread attempts to park
PARKING -> PARKED          // cont.yield successful, thread is parked
PARKING -> PINNED          // cont.yield failed, thread is pinned

PARKED -> RUNNABLE        // unpark or interrupted
PINNED -> RUNNABLE        // unpark or interrupted

RUNNABLE -> RUNNING         // continue execution

RUNNING -> YIELDING        // Thread.yield
YIELDING -> RUNNABLE        // yield successful
YIELDING -> RUNNING         // yield failed

RUNNING -> TERMINATED      // done
```


```text
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
    this.cont = new VThreadContinuation(this, task);           // Continuation包含了task
    this.runContinuation = this::runContinuation;
}
```

## VirtualThread.start

```text
@Override
public void start() {
    start(ThreadContainers.root());
}


@Override
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
        submitRunContinuation();                                            // 🎯🎯🎯 这里是关键
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
        scheduler.execute(runContinuation);                                 // 🎯🎯🎯 向线程池提交待执行的任务
    } catch (RejectedExecutionException ree) {
        submitFailed(ree);
        throw ree;
    }
}  
```


## park

不管是VirtualThread还是Thread类,在使用park时,都是当前"(虚拟)线程"自己主动调用LockSupport.park(),而非其他线程调用另一个线程.  🎯🎯🎯🎯🎯🎯
都是处于某种条件下,当前线程觉得自己需要先暂时挂起自己,等待条件满足后,被其他线程LockSupport.unpark().🎯🎯🎯🎯🎯🎯


```text
void park() {
    assert Thread.currentThread() == this;

    // complete immediately if parking permit available or interrupted
    if (getAndSetParkPermit(false) || interrupted)                                                 // 如果当前虚拟线程被unpark,或者被中断
        return;

    // park the thread
    boolean yielded = false;
    setState(PARKING);
    try {
        yielded = yieldContinuation();                                                              // 🎯 这里是关键
    } finally {
        assert (Thread.currentThread() == this) && (yielded == (state() == RUNNING));
        if (!yielded) {
            assert state() == PARKING;
            setState(RUNNING);
        }
    }

    // park on the carrier thread when pinned
    if (!yielded) {
        parkOnCarrierThread(false, 0);
    }
}
```

```text
private boolean getAndSetParkPermit(boolean newValue) {
    if (parkPermit != newValue) {
        return U.getAndSetBoolean(this, PARK_PERMIT, newValue);
    } else {
        return newValue;
    }
}

如果newValue与当前parkPermit不同,则通过CAS修改parkPermit为newValue,并返回CAS时parkPermit的旧值.
如果newValue与当前parkPermit相同,则不修改parkPermit.即parkPermit的旧值与新值相同,不用修改,返回的也是parkPermit的旧值.

getAndSetParkPermit(false)返回true: 即parkPermit由true->false.
getAndSetParkPermit(true)返回false: 即parkPermit由false->true.

下面2种情况无用
getAndSetParkPermit(false)返回false: 即permit本来就是false.
getAndSetParkPermit(true)返回true: 即permit本来就是true.
```

这里的 unmount（Java层） ≠ JVM 的栈卸载（C++层）, Java 这里只是“状态同步”.


Unmounts this virtual thread, invokes Continuation. yield, and re-mounts the thread when continued. When enabled, JVMTI must be notified from this method.
```text
@Hidden
@ChangesCurrentThread
private boolean yieldContinuation() {
    // unmount
    notifyJvmtiUnmount(/*hide*/true);
    unmount();
    
    try {
        return Continuation.yield(VTHREAD_SCOPE);                          // 🎯🎯🎯 暂停执行代码   (Continuation.yield是static方法,从当前carrierThread获取Continuation)
    } finally {
        // re-mount
        mount();
        notifyJvmtiMount(/*hide*/false);
    }
}
```

```text
@ChangesCurrentThread
@ReservedStackAccess
private void unmount() {
    // set Thread.currentThread() to return the platform thread
    Thread carrier = this.carrierThread;
    carrier.setCurrentThread(carrier);

    // break connection to carrier thread, synchronized with interrupt
    synchronized (interruptLock) {
        setCarrierThread(null);
    }
    carrier.clearInterrupt();             // 清空carrier在执行虚拟线程期间的中断标识
}


@ChangesCurrentThread
@ReservedStackAccess
private void mount() {
    // sets the carrier thread
    Thread carrier = Thread.currentCarrierThread();
    setCarrierThread(carrier);

    // sync up carrier thread interrupt status if needed
    if (interrupted) {
        carrier.setInterrupt();
    } else if (carrier.isInterrupted()) {                    // 清空carrier在执行本虚拟线程前的中断标识
        synchronized (interruptLock) {
            // need to recheck interrupt status
            if (!interrupted) {
                carrier.clearInterrupt();
            }
        }
    }

    // set Thread.currentThread() to return this virtual thread
    carrier.setCurrentThread(this);
}
```



## unpark

```text
@ChangesCurrentThread
void unpark() {
    Thread currentThread = Thread.currentThread();
    
    if (!getAndSetParkPermit(true) && currentThread != this) {
        int s = state();
        if (s == PARKED && compareAndSetState(PARKED, RUNNABLE)) {
            if (currentThread instanceof VirtualThread vthread) {
                vthread.switchToCarrierThread();
                try {
                    submitRunContinuation();
                } finally {
                    switchToVirtualThread(vthread);
                }
            } else {
                submitRunContinuation();
            }
        } else if (s == PINNED) {
            // unpark carrier thread when pinned.
            synchronized (carrierThreadAccessLock()) {
                Thread carrier = carrierThread;
                if (carrier != null && state() == PINNED) {
                    U.unpark(carrier);
                }
            }
        }
    }
}
```

```text
private void submitRunContinuation() {
    try {
        scheduler.execute(runContinuation);                         // 🎯🎯🎯 由线程池的平台线程继续执行虚拟线程的任务
    } catch (RejectedExecutionException ree) {
        submitFailed(ree);
        throw ree;
    }
}
```


## parkNanos

```text
void parkNanos(long nanos) {
    assert Thread.currentThread() == this;

    // complete immediately if parking permit available or interrupted
    if (getAndSetParkPermit(false) || interrupted)
        return;

    // park the thread for the waiting time
    if (nanos > 0) {
        long startTime = System.nanoTime();

        boolean yielded = false;
        Future<?> unparker = scheduleUnpark(this::unpark, nanos);                       // 🎯🎯🎯通过向定时任务线程池(不是任务执行的线程池)提交定时任务,由定时任务执行unpark操作(然后当前虚拟线程暂停执行)
        setState(PARKING);
        try {
            yielded = yieldContinuation();  // may throw
        } finally {
            assert (Thread.currentThread() == this) && (yielded == (state() == RUNNING));
            if (!yielded) {
                assert state() == PARKING;
                setState(RUNNING);
            }
            cancel(unparker);
        }

        // park on carrier thread for remaining time when pinned
        if (!yielded) {
            long remainingNanos = nanos - (System.nanoTime() - startTime);
            parkOnCarrierThread(true, remainingNanos);
        }
    }
}
```

## runContinuation

runContinuation不仅在start时会被调用,在unpark后也会被调用.

```text
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
        cont.run();                 // 🎯🎯🎯 继续执行当前虚拟线程
    } finally {
        if (cont.isDone()) {
            afterTerminate();
        } else {
            afterYield();
        }
    }
}
```


## sleep

```text
void sleepNanos(long nanos) throws InterruptedException {
    assert Thread.currentThread() == this && nanos >= 0;
    if (getAndClearInterrupt())
        throw new InterruptedException();
    if (nanos == 0) {
        tryYield();
    } else {
        // park for the sleep time
        try {
            long remainingNanos = nanos;
            long startNanos = System.nanoTime();
            while (remainingNanos > 0) {
                parkNanos(remainingNanos);
                if (getAndClearInterrupt()) {
                    throw new InterruptedException();
                }
                remainingNanos = nanos - (System.nanoTime() - startNanos);
            }
        } finally {
            // may have been unparked while sleeping
            setParkPermit(true);
        }
    }
}
```