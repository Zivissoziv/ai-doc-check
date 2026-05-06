package com.smartdoc.config;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Base64;

public class ConfigEncryptor {

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    public static String encrypt(String plaintext, String secretKey) {
        try {
            byte[] keyBytes = deriveKey(secretKey);
            byte[] ivBytes = deriveIv(keyBytes);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    public static String decrypt(String ciphertext, String secretKey) {
        try {
            byte[] keyBytes = deriveKey(secretKey);
            byte[] ivBytes = deriveIv(keyBytes);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("解密失败，请检查 SMARTDOC_SECRET_KEY 是否正确", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    public static String unwrap(String value) {
        if (isEncrypted(value)) {
            return value.substring(ENC_PREFIX.length(), value.length() - ENC_SUFFIX.length());
        }
        return value;
    }

    private static byte[] deriveKey(String secretKey) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(secretKey.getBytes("UTF-8"));
        byte[] key = new byte[16];
        System.arraycopy(hash, 0, key, 0, 16);
        return key;
    }

    private static byte[] deriveIv(byte[] key) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(key);
        byte[] iv = new byte[16];
        System.arraycopy(hash, 0, iv, 0, 16);
        return iv;
    }

    public static String encryptWithPrefix(String plaintext, String secretKey) {
        return ENC_PREFIX + encrypt(plaintext, secretKey) + ENC_SUFFIX;
    }
}
