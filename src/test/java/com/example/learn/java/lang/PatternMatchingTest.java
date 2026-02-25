package com.example.learn.java.lang;

import java.lang.reflect.Array;

/**
 * Java 16 在instanceof 引入模式匹配
 * Java 17 在switch-case 引入模式匹配
 */
public class PatternMatchingTest {

    public static void main(String[] args) throws Exception {
        test0();
        System.out.println("-------------------------------------------------");
        test1();
    }

    private static void test0() {
        Person person = new Doctor();

        // 新增语法糖: 在instanceof成立的情况下,才声明Doctor类型的变量doctor
        if (person instanceof Doctor doctor) {
            System.out.println("this person is a doctor");
        }
    }

    private static void test1() {
//        Object arg = "abc";
//        Object arg = Integer.valueOf(1);
        Object arg = new int[2];

        switch (arg) {
            case String s -> System.out.println("变量是String");
            case Integer i -> System.out.println("变量是Integer");
            case int[] a -> System.out.println("变量是数组");
            default -> System.out.println("其他类型");
        }
    }



    private static class Person {

    }

    private static class Doctor extends Person {

    }
}
