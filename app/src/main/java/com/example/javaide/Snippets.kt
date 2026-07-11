package com.example.javaide

/**
 * 快捷提示词（代码片段）。
 *
 * @param trigger  触发关键字，例如 psvm
 * @param label    在下拉列表中展示的说明
 * @param body     插入到编辑器中的代码模板，用 `$CARET$` 标记光标最终停留的位置
 */
data class Snippet(
    val trigger: String,
    val label: String,
    val body: String
)

/**
 * 内置的快捷提示词集合。输入 trigger 后从下拉列表中选择即可展开。
 */
object Snippets {

    private const val C = "\$CARET\$"

    val ALL: List<Snippet> = listOf(
        // ---- 入口 / 输出 ----
        Snippet("psvm", "public static void main",
            "public static void main(String[] args) {\n    $C\n}\n"),
        Snippet("main", "main 方法（同 psvm）",
            "public static void main(String[] args) {\n    $C\n}\n"),
        Snippet("sout", "System.out.println",
            "System.out.println($C);"),
        Snippet("soutv", "打印变量",
            "System.out.println(\"$C = \" + $C);"),
        Snippet("sos", "System.out.print",
            "System.out.print($C);"),
        Snippet("serr", "System.err.println",
            "System.err.println($C);"),

        // ---- 控制流 ----
        Snippet("fori", "for 循环",
            "for (int i = 0; i < $C; i++) {\n    \n}\n"),
        Snippet("foreach", "for-each 循环",
            "for ($C : ) {\n    \n}\n"),
        Snippet("forr", "反向 for 循环",
            "for (int i = $C.length - 1; i >= 0; i--) {\n    \n}\n"),
        Snippet("while", "while 循环",
            "while ($C) {\n    \n}\n"),
        Snippet("dowhile", "do-while 循环",
            "do {\n    $C\n} while ();\n"),
        Snippet("if", "if 语句",
            "if ($C) {\n    \n}\n"),
        Snippet("el", "else 语句",
            "else {\n    $C\n}\n"),
        Snippet("ifelse", "if-else",
            "if ($C) {\n    \n} else {\n    \n}\n"),
        Snippet("ifn", "if (x == null)",
            "if ($C == null) {\n    \n}\n"),
        Snippet("inn", "if (x != null)",
            "if ($C != null) {\n    \n}\n"),
        Snippet("sw", "switch 语句",
            "switch ($C) {\n    case :\n        break;\n}\n"),

        // ---- 异常 ----
        Snippet("try", "try-catch",
            "try {\n    $C\n} catch (Exception e) {\n    e.printStackTrace();\n}\n"),
        Snippet("tryr", "try-with-resources",
            "try ($C) {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}\n"),
        Snippet("catch", "catch 块",
            "catch ($C e) {\n    e.printStackTrace();\n}"),
        Snippet("thr", "throw 异常",
            "throw new $C();"),

        // ---- 类型 / 成员 ----
        Snippet("psf", "public static final",
            "public static final $C"),
        Snippet("psfi", "public static final int",
            "public static final int $C = ;"),
        Snippet("psfs", "public static final String",
            "public static final String $C = \"\";"),
        Snippet("classn", "public class",
            "public class $C {\n    \n}\n"),
        Snippet("interface", "interface 接口",
            "public interface $C {\n    \n}\n"),
        Snippet("enum", "enum 枚举",
            "public enum $C {\n    $C\n}\n"),
        Snippet("abstract", "abstract class",
            "public abstract class $C {\n    \n}\n"),
        Snippet("anno", "@interface 注解",
            "public @interface $C {\n    \n}\n"),

        // ---- 集合 / 常用 ----
        Snippet("list", "ArrayList",
            "java.util.List<$C> list = new java.util.ArrayList<>();"),
        Snippet("map", "HashMap",
            "java.util.Map<$C, $C> map = new java.util.HashMap<>();"),
        Snippet("set", "HashSet",
            "java.util.Set<$C> set = new java.util.HashSet<>();"),
        Snippet("arr", "数组",
            "$C[] arr = new $C[]{$C};"),
        Snippet("thread", "new Thread",
            "new Thread(() -> {\n    $C\n}).start();"),
        Snippet("lambda", "Lambda 表达式",
            "($C) -> {\n    \n}"),
        Snippet("stream", "集合 stream",
            "$C.stream().forEach(e -> {\n    \n});"),
        Snippet("getter", "getter",
            "public $C getType() {\n    return $C;\n}"),
        Snippet("setter", "setter",
            "public void setType($C value) {\n    this.$C = value;\n}"),
        Snippet("equals", "equals & hashCode",
            "@Override\npublic boolean equals(Object o) {\n    if (this == o) return true;\n    if (!(o instanceof $C)) return false;\n    $C that = ($C) o;\n    return $C;\n}\n\n@Override\npublic int hashCode() {\n    return java.util.Objects.hash($C);\n}"),
        Snippet("tostring", "toString",
            "@Override\npublic String toString() {\n    return \"$C{\" +\n        \"}\";\n}")
    )

    /** 根据当前已输入的词前缀，过滤出可候选的片段。 */
    fun matches(query: String): List<Snippet> {
        if (query.isEmpty()) return emptyList()
        return ALL.filter { it.trigger.startsWith(query) }
    }
}
