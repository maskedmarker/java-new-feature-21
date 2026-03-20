# 虚拟线程调用栈快照


## jstack

jstack无法捕获虚拟线程的调用栈.
最多显示到at jdk.internal.vm.Continuation.run

```text
"ForkJoinPool-1-worker-1" #33 [19828] daemon prio=5 os_prio=0 cpu=15.62ms elapsed=19.63s tid=0x000002d5f82587a0  [0x000000dbaeffe000]
   Carrying virtual thread #32
        at jdk.internal.vm.Continuation.run(java.base@21.0.2/Continuation.java:248)                              --> 对应 jdk.internal.vm.Continuation.enterSpecial(this, false, isVirtualThread);
        at java.lang.VirtualThread.runContinuation(java.base@21.0.2/VirtualThread.java:221)
        at java.lang.VirtualThread$$Lambda/0x000002d581057fe8.run(java.base@21.0.2/Unknown Source)
        at java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(java.base@21.0.2/ForkJoinTask.java:1423)
        at java.util.concurrent.ForkJoinTask.doExec$$$capture(java.base@21.0.2/ForkJoinTask.java:387)
        at java.util.concurrent.ForkJoinTask.doExec(java.base@21.0.2/ForkJoinTask.java)
        at java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(java.base@21.0.2/ForkJoinPool.java:1312)
        at java.util.concurrent.ForkJoinPool.scan(java.base@21.0.2/ForkJoinPool.java:1843)
        at java.util.concurrent.ForkJoinPool.runWorker(java.base@21.0.2/ForkJoinPool.java:1808)
        at java.util.concurrent.ForkJoinWorkerThread.run(java.base@21.0.2/ForkJoinWorkerThread.java:188)
```

```text
在idea debug时,idea会辅助展示更多虚拟线程的调用栈.
但是虚拟线程的调用栈起始于Continuation.enter方法,而非Thread.run.


run:51, VirtualThreadTest2$Handler (com.example.learn.java.lang)
runWith:1596, Thread (java.lang)
run:309, VirtualThread (java.lang)
run:190, VirtualThread$VThreadContinuation$1 (java.lang)
enter0:320, Continuation (jdk.internal.vm)                                                  --> 对应 jdk.internal.vm.Continuation.enter0
enter:312, Continuation (jdk.internal.vm)                                                   --> 对应 jdk.internal.vm.Continuation.enter
```

```text
将carrierThread的调用栈与虚拟线程的调用栈拼接起来就是完整的调用栈.


run:51, VirtualThreadTest2$Handler (com.example.learn.java.lang)
runWith:1596, Thread (java.lang)
run:309, VirtualThread (java.lang)
run:190, VirtualThread$VThreadContinuation$1 (java.lang)
enter0:320, Continuation (jdk.internal.vm)                                                               --> 对应 jdk.internal.vm.Continuation.enter0
enter:312, Continuation (jdk.internal.vm)                                                                --> 对应 jdk.internal.vm.Continuation.enter 
at jdk.internal.vm.Continuation.run(java.base@21.0.2/Continuation.java:248)                              --> 对应 jdk.internal.vm.Continuation.enterSpecial(this, false, isVirtualThread);
at java.lang.VirtualThread.runContinuation(java.base@21.0.2/VirtualThread.java:221)
at java.lang.VirtualThread$$Lambda/0x000002d581057fe8.run(java.base@21.0.2/Unknown Source)
at java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(java.base@21.0.2/ForkJoinTask.java:1423)
at java.util.concurrent.ForkJoinTask.doExec$$$capture(java.base@21.0.2/ForkJoinTask.java:387)
at java.util.concurrent.ForkJoinTask.doExec(java.base@21.0.2/ForkJoinTask.java)
at java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(java.base@21.0.2/ForkJoinPool.java:1312)
at java.util.concurrent.ForkJoinPool.scan(java.base@21.0.2/ForkJoinPool.java:1843)
at java.util.concurrent.ForkJoinPool.runWorker(java.base@21.0.2/ForkJoinPool.java:1808)
at java.util.concurrent.ForkJoinWorkerThread.run(java.base@21.0.2/ForkJoinWorkerThread.java:188)
```