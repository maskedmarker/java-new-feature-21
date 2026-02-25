package com.example.learn.java.lang;

public class TextBlockTest {

    public static void main(String[] args) {
        System.out.println("JDK: " + System.getProperty("java.version"));

        // JDK 17 特性测试
        String text = """
                This is a JDK 17 text block!
                Multi-line string works.
                """;
        System.out.println(text);
    }
}
