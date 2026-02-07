package github.javaguide.utils;

/**
 * String 工具类
 *
 */
public class StringUtil {

    public static boolean isBlank(String s) {
        // 1. 判断字符串是否为空
        if (s == null || s.length() == 0) {
            return true;
        }
        // 2. 判断字符串是否为空格
        for (int i = 0; i < s.length(); ++i) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
