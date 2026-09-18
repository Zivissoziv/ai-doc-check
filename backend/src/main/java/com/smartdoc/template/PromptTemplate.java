package com.smartdoc.template;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词模板加载工具。
 * 将 LLM 提示词抽取为 src/main/resources/prompts/ 下的独立文件，
 * 避免硬编码在业务代码中，方便维护和修改。
 */
public class PromptTemplate {

    private static final String PROMPT_DIR = "prompts/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /**
     * 加载并格式化提示词模板
     *
     * @param name   模板文件名（不含后缀），自动定位到 prompts/{name}.prompt
     * @param params 占位符参数，替换模板中的 {key}；可为 null
     * @return 格式化后的完整提示词
     */
    public static String format(String name, Map<String, String> params) {
        return formatText(raw(name), params);
    }

    /**
     * 对任意提示词文本做占位符替换（用于用户自定义提示词：内容来自数据库而非模板文件）。
     * 占位符形如 {key}（单花括号），只替换 params 里出现的键。
     */
    public static String formatText(String template, Map<String, String> params) {
        String result = template == null ? "" : template;
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
    }

    /**
     * 读取模板原始内容（不做替换），用于前端展示「内置默认值」。
     */
    public static String raw(String name) {
        return CACHE.computeIfAbsent(name, PromptTemplate::loadFromFile);
    }

    /**
     * 模板是否存在（用于校验前端传来的 promptKey 合法性）。
     */
    public static boolean exists(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        try {
            loadFromFile(name);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String loadFromFile(String name) {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_DIR + name + ".prompt");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("加载提示词模板失败: " + name, e);
        }
    }
}
