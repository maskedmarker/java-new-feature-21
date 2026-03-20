package com.example.learn.java.lang;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.concurrent.TimeUnit;

/**
 * 虚拟线程适用于执行阻塞式任务,在阻塞期间,可以将CPU资源让渡给其他任务
 * 虚拟线程不适合CPU密集计算或非阻塞任务,虚拟线程并不会运行的更快
 * 虚拟线程是轻量级资源,用完即抛,不需要池化
 */
public class VirtualThreadTest {

    public static void main(String[] args) throws Exception {
        test0();
        System.out.println("-------------------------------------------------");
        test1();
        System.out.println("-------------------------------------------------");
//        test2();
    }


    /**
     * 测试虚拟线程方法
     */
    private static void test0() throws InterruptedException {
        final Thread[] threads = new Thread[2];

        Thread platformThread = new Thread(() -> {
            threads[1] = Thread.currentThread();

        });
        platformThread.start();
        threads[0] = platformThread;

        // new Thread()返回的是OS平台线程对象
        System.out.println("platformThread.getClass() = " + platformThread.getClass());

        // 等待newThread结束
        platformThread.join();
        System.out.println("platformThread == currentThread ? " + (threads[0] == threads[1]));
    }

    /**
     * 测试虚拟线程方法
     */
    private static void test1() throws InterruptedException {
        final Thread[] threads = new Thread[3];

        Thread virtualThread = Thread.startVirtualThread(() -> {
            threads[1] = Thread.currentThread();
            threads[2] = currentCarrierThread(threads[1]);
        });
        threads[0] = virtualThread;

        // startVirtualThread返回的是虚拟线程对象
        System.out.println("virtualThread.getClass() = " + virtualThread.getClass());

        // 等待newThread结束
        virtualThread.join();
        System.out.println("virtualThread == currentThread ? " + (threads[0] == threads[1]));
        System.out.println("virtualThread == currentCarrierThread ? " + (threads[0] == threads[2]));
    }

    /**
     * 创建虚拟线的另一个方法
     */
    private static void test2() throws InterruptedException {
        final Thread[] threads = new Thread[3];

        // ofVirtual采用了builder模式来构造虚拟线程
        Thread virtualThread = Thread.ofVirtual().name("vt").start(() -> {
            threads[1] = Thread.currentThread();
            threads[2] = currentCarrierThread(threads[1]);
        });
        threads[0] = virtualThread;

        // startVirtualThread返回的是虚拟线程对象
        System.out.println("virtualThread.getClass() = " + virtualThread.getClass());

        // 等待newThread结束
        virtualThread.join();
        System.out.println("virtualThread == currentThread ? " + (threads[0] == threads[1]));
        System.out.println("virtualThread == currentCarrierThread ? " + (threads[0] == threads[2]));
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
