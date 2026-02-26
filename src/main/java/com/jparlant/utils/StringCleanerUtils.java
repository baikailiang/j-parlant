package com.jparlant.utils;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringCleanerUtils {

    public static String extractJson(String input) {
        Pattern pattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return input;
    }




}

