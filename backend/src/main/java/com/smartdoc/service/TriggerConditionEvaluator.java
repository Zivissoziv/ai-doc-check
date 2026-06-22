package com.smartdoc.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则触发条件表达式解析与评估工具类。
 * <p>
 * 支持三种运算符：
 * <ul>
 *   <li><code>==</code>（相等比较）：{{data.字段名}} == "值"</li>
 *   <li><code>!=</code>（不等比较）：{{data.字段名}} != "值"</li>
 *   <li><code>contains</code>（包含比较）：{{data.字段名}} contains "值"</li>
 * </ul>
 */
public class TriggerConditionEvaluator {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile(
            "\\{\\{data\\.([^}]+)\\}\\}\\s*(==|!=|contains)\\s*[\"']([^\"']*)[\"']\\s*$");

    private static final Pattern DATA_VAR_PATTERN = Pattern.compile("\\{\\{data\\.([^}]+)\\}\\}");

    private static final Pattern DOT_SPLIT_PATTERN = Pattern.compile("\\.");

    /**
     * 解析结果，包含表达式各组成部分。
     */
    public static class ParsedExpression {
        private final String leftVarPath;
        private final String operator;
        private final String rightValue;

        public ParsedExpression(String leftVarPath, String operator, String rightValue) {
            this.leftVarPath = leftVarPath;
            this.operator = operator;
            this.rightValue = rightValue;
        }

        public String getLeftVarPath() {
            return leftVarPath;
        }

        public String getOperator() {
            return operator;
        }

        public String getRightValue() {
            return rightValue;
        }
    }

    /**
     * 解析表达式字符串，提取变量路径、运算符和右值。
     *
     * @param expression 表达式字符串，例如 {@code {{data.状态}} == "已关闭"}
     * @return 解析后的表达式对象
     * @throws IllegalArgumentException 如果表达式格式非法
     */
    public static ParsedExpression parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("表达式不能为空");
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(expression.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("非法表达式格式: " + expression);
        }

        String leftVarPath = matcher.group(1);
        String operator = matcher.group(2);
        String rightValue = matcher.group(3);

        if (leftVarPath.isEmpty()) {
            throw new IllegalArgumentException("表达式缺少左值变量路径: " + expression);
        }
        if (rightValue.isEmpty()) {
            throw new IllegalArgumentException("表达式缺少右值: " + expression);
        }

        return new ParsedExpression(leftVarPath, operator, rightValue);
    }

    /**
     * 解析并评估表达式，返回比较结果。
     *
     * @param expression 表达式字符串
     * @param data       变量数据 Map
     * @return 比较结果
     * @throws IllegalArgumentException 如果表达式非法或变量缺失
     */
    public static boolean evaluate(String expression, Map<String, Object> data) {
        ParsedExpression parsed = parse(expression);

        String[] parts = DOT_SPLIT_PATTERN.split(parsed.getLeftVarPath());
        Object leftValue = resolveDataValue(data, parts, 0);

        if (leftValue == null) {
            throw new IllegalArgumentException("变量不存在: " + parsed.getLeftVarPath());
        }

        String leftStr = String.valueOf(leftValue);
        String rightStr = parsed.getRightValue();

        switch (parsed.getOperator()) {
            case "==":
                return leftStr.equals(rightStr);
            case "!=":
                return !leftStr.equals(rightStr);
            case "contains":
                return leftStr.contains(rightStr);
            default:
                throw new IllegalArgumentException("不支持的运算符: " + parsed.getOperator());
        }
    }

    /**
     * 校验表达式格式是否合法。
     *
     * @param expression 表达式字符串
     * @return true 如果表达式格式合法
     */
    public static boolean validate(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return false;
        }
        return EXPRESSION_PATTERN.matcher(expression.trim()).matches();
    }

    /**
     * 从表达式中提取引用的 data 变量路径（如 {@code 状态}、{@code 用户.地址.城市}）。
     *
     * @param expression 表达式字符串
     * @return 变量路径，如果表达式中不包含变量引用则返回 null
     */
    public static String getReferencedVarPath(String expression) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = DATA_VAR_PATTERN.matcher(expression);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 根据路径从 data Map 中解析出对应的值。
     * <p>
     * 支持嵌套 Map 路径（如 {@code 用户.地址.城市}）和数组索引访问（如 {@code 列表.0.名称}）。
     *
     * @param value      当前层级的数据对象
     * @param parts      路径片段数组
     * @param startIndex 开始处理的索引
     * @return 解析到的值，如果中间路径不存在或类型不匹配则返回 null
     */
    private static Object resolveDataValue(Object value, String[] parts, int startIndex) {
        if (value == null) {
            return null;
        }
        for (int i = startIndex; i < parts.length; i++) {
            String part = parts[i];
            if (value instanceof Map) {
                value = ((Map<?, ?>) value).get(part);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                try {
                    int idx = Integer.parseInt(part);
                    if (idx >= 0 && idx < list.size()) {
                        value = list.get(idx);
                    } else {
                        return null;
                    }
                } catch (NumberFormatException e) {
                    List<String> collected = new ArrayList<>();
                    for (Object item : list) {
                        Object itemResult = resolveDataValue(item, parts, i);
                        if (itemResult != null) {
                            collected.add(itemResult.toString());
                        }
                    }
                    return collected.isEmpty() ? null : String.join(", ", collected);
                }
            } else {
                return null;
            }
        }
        return value;
    }
}
