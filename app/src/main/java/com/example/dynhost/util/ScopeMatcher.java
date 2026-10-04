package com.example.dynhost.util;

import java.util.List;

/** 作用域匹配：精确包名、"com.foo.*" 前缀通配、"*" 全部、"!pkg" 排除 */
public final class ScopeMatcher {

    public static boolean matches(List<String> scope, String pkg) {
        if (pkg == null) return false;
        // 未声明作用域 = 对所有 App 生效
        if (scope == null || scope.isEmpty()) return true;

        boolean hit = false;
        for (String raw : scope) {
            if (raw == null) continue;
            String rule = raw.trim();
            if (rule.isEmpty()) continue;

            if (rule.startsWith("!")) {              // 排除优先
                if (matchesOne(rule.substring(1), pkg)) return false;
                continue;
            }
            if (matchesOne(rule, pkg)) hit = true;
        }
        return hit;
    }

    private static boolean matchesOne(String rule, String pkg) {
        if ("*".equals(rule)) return true;
        if (rule.endsWith(".*")) {
            String prefix = rule.substring(0, rule.length() - 1); // 保留结尾的点
            return pkg.startsWith(prefix);
        }
        if (rule.endsWith("*")) {
            return pkg.startsWith(rule.substring(0, rule.length() - 1));
        }
        return rule.equals(pkg);
    }
}
