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
        String template = CACHE.computeIfAbsent(name, PromptTemplate::loadFromFile);
        String result = template;
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
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
