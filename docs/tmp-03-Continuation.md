# Continuation

Continuation形成一棵“调用树”.
parent continuation 是“上层执行上下文”，通常仍是一个 Continuation；只有在最顶层时，才会回到 OS 线程（carrier）。


```text
public class Continuation {

    private Continuation parent;            // null for native stack
    private Continuation child;             // non-null when we're yielded in a child continuation
    
    
    private final Runnable target;          // target即虚拟线程要执行的task,一个Continuation代表一个task的执行,所以是final,不可更改
    
    
    private boolean done;                   // task是否执行完了
    private volatile boolean mounted;
    private Object yieldInfo;
    private boolean preempted;
    
    private StackChunk tail;
}
```


## run

虚拟线程的task被封装到Continuation中,由Continuation来控制task的执行/暂停/再执行.
如果要推动task执行(启动/恢复),就必须主动调用continuation.run()方法.   
(备注:  主动调用continuation.run()意味着占用调用方的os线程,所以初始的Thread.startVirtualThread()必须是让线程池的工作线程来调用continuation.run(),否则caller的代码就只能等新创建的VirtualThread结束后才能继续执行,也就违背了startVirtualThread的语义了.
)

(注意: continuation.run()方法只会被java.lang.VirtualThread.runContinuation调用.)

```text
// Mounts and runs the continuation body. If suspended, continues it from the last suspend point.

public final void run() {
    
    // 🎯🎯🎯🎯 注意这里的while-true循环,因为task会被暂停执行,恢复执行,再暂停执行.
    while (true) {
        mount();
        JLA.setScopedValueCache(scopedValueCache);

        if (done)
            throw new IllegalStateException("Continuation terminated");

        Thread t = currentCarrierThread();               // os线程
        if (parent != null) {
            if (parent != JLA.getContinuation(t))
                throw new IllegalStateException();
        } else
            this.parent = JLA.getContinuation(t);
        JLA.setContinuation(t, this);                   // 为carrierThread设置当前正在执行的continuation

        // enterSpecial是native方法,有2个两个模式
        try {
            boolean isVirtualThread = (scope == JLA.virtualThreadContinuationScope());
            if (!isStarted()) { // is this the first run? (at this point we know !done)
                enterSpecial(this, false, isVirtualThread);                                   // 🎯首次启动Continuation (创建执行上下文,初始化栈,调用continuation.enter0()方法,且该方法会调用target.run()方法)
            } else {
                assert !isEmpty();
                enterSpecial(this, true, isVirtualThread);                                    // 🎯恢复一个已挂起的Continuation(从StackChunk恢复栈,重建frame,从yield点继续执行)
            }
        } finally {
            fence();
            try {
                assert isEmpty() == done : "empty: " + isEmpty() + " done: " + done + " cont: " + Integer.toHexString(System.identityHashCode(this));
                JLA.setContinuation(currentCarrierThread(), this.parent);
                if (parent != null)
                    parent.child = null;

                postYieldCleanup();                                                             // yield会导致本执行片结束(即从enterSpecial返回)

                unmount();
                if (PRESERVE_SCOPED_VALUE_CACHE) {
                    scopedValueCache = JLA.scopedValueCache();
                } else {
                    scopedValueCache = null;
                }
                JLA.setScopedValueCache(null);
            } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        }
        // we're now in the parent continuation

        // 到达这里有2种情况: 任务执行完了;yield结束了本执行片
        
        assert yieldInfo == null || yieldInfo instanceof ContinuationScope;
        if (yieldInfo == null || yieldInfo == scope) {                                            // 这是在多次执行片后,任务执行完毕
            this.parent = null;
            this.yieldInfo = null;
            return;
        } else {                                                                                  // yield传播链 = 每一层continuation依次执行“保存栈 + 退出”,直到命中目标 scope
            parent.child = this;
            parent.yield0((ContinuationScope)yieldInfo, this);
            parent.child = null;
        }
    }
}
```

## yield

```text
@Hidden
public static boolean yield(ContinuationScope scope) {
    Continuation cont = JLA.getContinuation(currentCarrierThread());           // 从当前carrierThread获取Continuation
    Continuation c;
    for (c = cont; c != null && c.scope != scope; c = c.parent)                // 找到对应的native stack
        ;
    if (c == null)
        throw new IllegalStateException("Not in scope " + scope);

    return cont.yield0(scope, null);
}
```

```text
@Hidden
private boolean yield0(ContinuationScope scope, Continuation child) {
    preempted = false;

    if (scope != this.scope)
        this.yieldInfo = scope;
    
    int res = doYield();                                                                  // 🎯🎯🎯 代码在这里就不执行了,当返回时即unpark后了
    U.storeFence(); // needed to prevent certain transformations by the compiler

    assert scope != this.scope || yieldInfo == null : "scope: " + scope + " this.scope: " + this.scope + " yieldInfo: " + yieldInfo + " res: " + res;
    assert yieldInfo == null || scope == this.scope || yieldInfo instanceof Integer : "scope: " + scope + " this.scope: " + this.scope + " yieldInfo: " + yieldInfo + " res: " + res;

    if (child != null) {
        if (res != 0) {
            child.yieldInfo = res;
        } else if (yieldInfo != null) {
            assert yieldInfo instanceof Integer;
            child.yieldInfo = yieldInfo;
        } else {
            child.yieldInfo = res;
        }
        this.yieldInfo = null;
    } else {
        if (res == 0 && yieldInfo != null) {
            res = (Integer)yieldInfo;
        }
        this.yieldInfo = null;

        if (res == 0)
            onContinue();
        else
            onPinned0(res);
    }
    assert yieldInfo == null;

    return res == 0;
}
```

### doYield

```text
private static native int doYield();

返回值用于表示：成功挂起/特殊状态/错误

在实际实现中：
区分是否真正 yield
是否被pin不能挂起）


doYield导致continuation的本次执行片结束(即enterSpecial执行完从方法返回)🎯🎯🎯🎯
即在enterSpecial执行target.run时,run中触发了yield,yield保存好当前栈信息,然后返回到enterSpecial的方法出口.
```

```text
doYield 的本质是: 触发一次 Continuation 的“栈冻结（freeze）”，保存当前执行状态到堆，然后返回到上层调用者.


进入 JVM 后，大致流程：  (c/c++代码逻辑)
doYield
   ↓
Continuation::yield
   ↓
freeze（核心）
   ↓
切回 parent continuation/调用者

Step 1️⃣ 获取当前 Continuation.
Step 2️⃣ 执行 freeze（最核心）
执行 freeze（最核心）:
遍历当前调用栈
拷贝栈到 StackChunk（heap）
修复引用（GC 安全）
记录恢复点（关键）

native stack
   ↓ copy
heap (StackChunk)

结构：
StackChunk
 ├── frame data
 ├── metadata
 └── oop map
 
保存：
SP（栈顶位置）
PC（程序计数器）
frame chain 

Step 3️⃣ 修改执行流（退出 continuation）
freeze 完成后,JVM 会：
丢弃当前 continuation 栈
   ↓
恢复调用者的栈（parent continuation 或 carrier）
   ↓
返回到yield调用点的“外层”(parent continuation 或 carrier)
```

```text
什么时候不能 yield?

❌ pinned 状态
如果当前线程处于：
synchronized
native 调用
JNI
critical section

JVM 会检测：
无法安全 freeze 栈

此时：
doYield → 失败/fallback
```

```text
doYield 时到底回到哪里？

情况1️⃣ 有 parent continuation
Continuation A（正在运行）
   ↓ yield
Continuation B（parent）

执行：
A.doYield()
   ↓
freeze A
   ↓
切换到 B 的栈
   ↓
继续执行 B

这里：不会回到 OS 线程栈，而是回到另一个 continuation


情况2️⃣ 没有 parent（最外层）
Continuation A（top-level）
   ↓ yield
(no parent)
   ↓
carrier thread

执行：
A.doYield()
   ↓
freeze A
   ↓
回到 carrier thread（JavaThread）



虚拟线程中的真实结构

虚拟线程其实是这样组织的：
VirtualThread
   ↓
Continuation（用户代码）
   ↓ parent
Scheduler Continuation（调度器）
   ↓ parent
Carrier Thread


执行流程：

scheduler continuation
   ↓ enterSpecial
user continuation
   ↓ doYield
回到 scheduler continuation

👉 注意： 调度器本身也是一个 continuation (Scheduler的工作线程carrierThread在执行调用continuation.run时,carrierThread会将当前continuation作为自己的continuation)
```




## enterSpecial


```text
enterSpecial 会在当前 continuation “本轮执行结束”时返回

而“本轮执行结束”有且只有三种情况：
1️⃣ continuation 执行完（run 方法结束）
2️⃣ 调用了 yield（主动挂起）
3️⃣ 抛出异常（未被内部捕获）

一旦发生 yield,这一轮 enterSpecial 就结束了.
但注意：不是线程结束！只是一次“执行时间片”结束. 🔥🔥🔥

enterSpecial的执行周期: 从continuation恢复开始,到“遇到 yield / 执行结束 / 异常”为止
```




```text
private static void enter(Continuation c, boolean isContinue) {                    // 方法是static,由jvm调用
    // This method runs in the "entry frame".
    // A yield jumps to this method's caller as if returning from this method.
    try {
        c.enter0();
    } finally {
        c.finish();
    }
}
    
@Hidden
private void enter0() {
    target.run();
}    
```

为什么叫 enterSpecial?
因为它不是普通方法调用,而是切换整个栈然后去执行第一个入参的enter0()方法.

一句话总结: 在JVM层完成一次“栈切换 + 执行跳转”,用于启动或恢复一个Continuation。

```text
enterSpecial
   ↓
Continuation::enter
   ↓
if (new) → start
if (resume) → thaw
   ↓
切换 stack
   ↓
跳转执行

关键步骤拆解
Step 1：保存当前执行状态
    当前 Java 栈
    当前 SP / PC
Step 2：切换到 Continuation 栈
    如果是首次执行：
        分配新栈（StackChunk）
        初始化 frame    
    如果是恢复：
        从 heap 中的 StackChunk
        恢复到 native 栈（thaw）
Step 3：修改寄存器（非常关键）
    JVM 会直接操作：
            SP（stack pointer）        
            FP（frame pointer）
            PC（program counter）
Step 4：开始执行            
```

```text
汇编级别（最底层）

真正的“魔法”在这里：
JVM 使用 stub：
StubRoutines::cont_enter

做的事：
切换 SP
设置返回地址
跳转执行
```