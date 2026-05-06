package com.smartdoc.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

public class EncryptedPropertyPostProcessor implements EnvironmentPostProcessor {

    private static final String SECRET_KEY_PROPERTY = "smartdoc.secret-key";
    private static final String PROPERTY_SOURCE_NAME = "smartdocDecryptedProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String secretKey = environment.getProperty(SECRET_KEY_PROPERTY);
        if (secretKey == null || secretKey.isEmpty()) {
            secretKey = System.getenv("SMARTDOC_SECRET_KEY");
        }
        if (secretKey == null || secretKey.isEmpty()) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();
        Map<String, Object> decryptedValues = new HashMap<>();

        for (PropertySource<?> ps : propertySources) {
            if (ps instanceof EnumerablePropertySource) {
                EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) ps;
                for (String key : eps.getPropertyNames()) {
                    if (SECRET_KEY_PROPERTY.equals(key)) {
                        continue;
                    }
                    Object value = eps.getProperty(key);
                    if (value instanceof String) {
                        String strValue = (String) value;
                        if (ConfigEncryptor.isEncrypted(strValue)) {
                            String encrypted = ConfigEncryptor.unwrap(strValue);
                            try {
                                String decrypted = ConfigEncryptor.decrypt(encrypted, secretKey);
                                decryptedValues.put(key, decrypted);
                            } catch (Exception e) {
                                System.err.println("[SmartDoc] 解密失败: " + key + " - " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }

        if (!decryptedValues.isEmpty()) {
            propertySources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, decryptedValues));
        }
    }
}
