package com.example.learn.java.lang;

/**
 * 文本块是Java 17 引入的一个实用特性,旨在简化多行字符串的处理.
 *
 * 注意事项
 *      关闭引号单独一行：结束的 """ 必须单独占一行
 *      缩进规则：基于最左边的字符确定[公共缩进]
 *      编译时常量：文本块是常量表达式，可以在注解中使用
 */
public class TextBlockTest {

    public static void main(String[] args) {
        test0();
        System.out.println("-------------------------------------------------");
        test1();
        System.out.println("-------------------------------------------------");
        test2();
        System.out.println("-------------------------------------------------");
        test3();
        System.out.println("-------------------------------------------------");
        test4();
        System.out.println("-------------------------------------------------");
    }

    public static void test0() {
        String text = """
                (源码中,每行前面都有相同的tab缩进,公共缩进会被忽略掉)
                This is a JDK 17 text block!(文本块中直接换行换行符,无需转义)
                
                Multi-line string works.(行尾的空格/tab会被清理过掉)   
                """; // 关闭引号单独一行
        System.out.println(text);
    }

    public static void test1() {
        String text = """
            (文本块中可以直接使用双引号)
            {
                "name": "张三",
                "age": 25,
                "city": "北京"
            }
            """;
        System.out.println(text);
    }

    public static void test2() {
        String text = """
                (可以禁用源码中的换行符,用法类似于shell脚本中的写法,行尾以\\结束)
                SELECT id, name, age \
                FROM users \
                WHERE age > 18 \
                ORDER BY age
                """;
        System.out.println(text);
    }

    public static void test3() {
        String text = """
                第一行 开始 结束(行尾的空格需要用转义才能保留)\s
                第二行
                """;
        System.out.println(text);
    }

    public static void test4() {
        String text = """
            (文本块支持占位符)
            {
                "name": "%s",
                "age": %d,
                "city": "%s"
            }
            """.formatted("李四", 26, "上海");
        System.out.println(text);
    }
}
