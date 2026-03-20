package com.example.learn.java.lang;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 *
 * 虚拟线程的调用栈不在“物理线程栈”上，而是在 Continuation 对象里
 * 在debug时,只能看到 at jdk.internal.vm.Continuation.run(...)
 *
 */
public class VirtualThreadTest2 {

    public static void main(String[] args) throws Exception {
        test1();
    }


    /**
     * 测试虚拟线程方法
     */
    private static void test1() throws InterruptedException {
        final Thread[] threads = new Thread[3];

        Thread virtualThread = Thread.startVirtualThread(new Handler(threads));
        threads[0] = virtualThread;

        // startVirtualThread返回的是虚拟线程对象
        System.out.println("virtualThread.getClass() = " + virtualThread.getClass());

        // 等待newThread结束
//        virtualThread.join();
        System.out.println("virtualThread == currentThread ? " + (threads[0] == threads[1]));
        System.out.println("virtualThread == currentCarrierThread ? " + (threads[0] == threads[2]));

        while (!Thread.currentThread().isInterrupted()) {
            TimeUnit.SECONDS.sleep(5);
        }
    }

    private static class Handler implements Runnable{

        final Thread[] threads;

        public Handler(Thread[] threads) {
            this.threads = threads;
        }

        @Override
        public void run() {
            threads[1] = Thread.currentThread();
            threads[2] = currentCarrierThread(threads[1]);
        }
    }

    /**
     * 从 Java 9 开始, 即使使用反射,也无法访问未开放的包, setAccessible(true) 不再具有“万能钥匙”的能力
     * 你必须显式打开模块访问权限 启动参数添加 --add-opens
     *      --add-opens java.base/java.lang=com.example.learn.java.lang
     */
    private static Thread currentCarrierThread(Thread thread) {
        try {
            Method currentCarrierThread = Thread.class.getDeclaredMethod("currentCarrierThread");
            currentCarrierThread.setAccessible(true);
            return (Thread) currentCarrierThread.invoke(thread);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
