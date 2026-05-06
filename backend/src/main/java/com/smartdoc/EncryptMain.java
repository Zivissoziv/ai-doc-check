package com.smartdoc;

import com.smartdoc.config.ConfigEncryptor;

import java.util.Scanner;

public class EncryptMain {

    public static void main(String[] args) {
        String secretKey = System.getenv("SMARTDOC_SECRET_KEY");
        if (secretKey == null || secretKey.isEmpty()) {
            System.out.println("错误: 请先设置环境变量 SMARTDOC_SECRET_KEY");
            System.out.println("  Windows: set SMARTDOC_SECRET_KEY=your-key");
            System.out.println("  Linux:   export SMARTDOC_SECRET_KEY=your-key");
            System.exit(1);
        }

        if (secretKey.length() < 8) {
            System.out.println("警告: 密钥长度建议至少 8 位");
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("===== SmartDoc 配置加密工具 =====");
        System.out.println("输入要加密的明文（输入空行退出）:\n");

        while (true) {
            System.out.print("> ");
            String plaintext = scanner.nextLine().trim();
            if (plaintext.isEmpty()) {
                break;
            }
            try {
                String encrypted = ConfigEncryptor.encryptWithPrefix(plaintext, secretKey);
                System.out.println("加密结果: " + encrypted + "\n");
            } catch (Exception e) {
                System.out.println("加密失败: " + e.getMessage() + "\n");
            }
        }

        System.out.println("已退出。");
    }
}
