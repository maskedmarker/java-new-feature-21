package com.example.learn.java.lang;


import java.util.Arrays;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class SumTaskUsingCountedCompleterTest21 {

    private static final int THRESHOLD = 100; // 拆分阈值

    static class SumTask extends CountedCompleter<Long> {
        final int[] array;
        final int start, end;  // [start,edn)闭开区间
        final AtomicLong localSum = new AtomicLong();  // 当前任务计算的求和值
        SumTask left, right;

        SumTask(CountedCompleter<?> parent, int[] array, int start, int end) {
            super(parent);
            this.array = array;
            this.start = start;
            this.end = end;
        }

        @Override
        public void compute() {
            if (end - start <= THRESHOLD) {
                long sum = 0;
                for (int i = start; i < end; i++){
                    sum += array[i];
                }
                localSum.addAndGet(sum);
                System.out.printf("[%d, %d) produces %d\n", start, end, sum);
            } else {
                int mid = (start + end) >>> 1;
                setPendingCount(2);
                left = new SumTask(this, array, start, mid);
                right = new SumTask(this, array, mid, end);
                left.fork();
                right.fork();
            }

            tryComplete();
        }

        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            // 非叶子节点才可能需要合并子任务的结果
            if (caller != this) {
                // 判断非叶子节点既可以用通用的方式(caller != this) ; 这里也可以使用树的结构特点 (left!=null && right!=null)
                localSum.addAndGet(left.localSum.get());
                localSum.addAndGet(right.localSum.get());
                System.out.printf("[%d,%d) localSum=%d  | [%d,%d) localSum=%d | [%d,%d) localSum=%d \n", start, end, localSum.get(), left.start, left.end, left.localSum.get(), right.start, right.end, right.localSum.get());
            }
        }

        @Override
        public Long getRawResult() {
            return localSum.get();
        }
    }

    public static void main(String[] args) {
        int[] arr = new int[10000];
        Arrays.fill(arr, 1);
        int expectedSum = IntStream.of(arr).sum();
        System.out.println("expectedSum = " + expectedSum);

        ForkJoinPool pool = ForkJoinPool.commonPool();
        for (int i = 0; i < 10000; i++) {
            test(pool, arr, expectedSum);
        }

    }

    public static void test(ForkJoinPool pool, int[] arr, int expectedSum) {
        SumTask rootTask = new SumTask(null, arr, 0, arr.length);
        Long result = pool.invoke(rootTask);
        if (expectedSum != result) {
            System.out.printf("result = %d\n", result);
            System.exit(-1);
        }
        System.out.println("-------------------------------------------");
    }
}
