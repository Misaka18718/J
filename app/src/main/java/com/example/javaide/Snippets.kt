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
        Snippet(
            "psvm", "public static void main",
            "public static void main(String[] args) {\n    $C\n}\n"
        ),
        Snippet(
            "sout", "System.out.println",
            "System.out.println($C);"
        ),
        Snippet(
            "soutv", "打印变量",
            "System.out.println(\"$C = \" + $C);"
        ),
        Snippet(
            "sos", "System.out.print",
            "System.out.print($C);"
        ),
        Snippet(
            "fori", "for 循环",
            "for (int i = 0; i < $C; i++) {\n    \n}\n"
        ),
        Snippet(
            "foreach", "for-each 循环",
            "for ($C : ) {\n    \n}\n"
        ),
        Snippet(
            "if", "if 语句",
            "if ($C) {\n    \n}\n"
        ),
        Snippet(
            "el", "else 语句",
            "else {\n    $C\n}\n"
        ),
        Snippet(
            "wh", "while 循环",
            "while ($C) {\n    \n}\n"
        ),
        Snippet(
            "sw", "switch 语句",
            "switch ($C) {\n    case :\n        break;\n}\n"
        ),
        Snippet(
            "try", "try-catch",
            "try {\n    $C\n} catch (Exception e) {\n    e.printStackTrace();\n}\n"
        ),
        Snippet(
            "psf", "public static final",
            "public static final $C"
        ),
        Snippet(
            "classn", "public class",
            "public class $C {\n    \n}\n"
        )
    )

    /** 根据当前已输入的词前缀，过滤出可候选的片段。 */
    fun matches(query: String): List<Snippet> {
        if (query.isEmpty()) return emptyList()
        return ALL.filter { it.trigger.startsWith(query) }
    }
}
