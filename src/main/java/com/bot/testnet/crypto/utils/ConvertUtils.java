package com.bot.testnet.crypto.utils;

import lombok.extern.log4j.Log4j2;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

@Log4j2
public class ConvertUtils {

    private ConvertUtils(){}

    public static String convertTimestampToString(Timestamp timestamp, String format){
        try {
            return new SimpleDateFormat(format).format(timestamp);
        }catch (Exception e){
            log.warn("Error while converting timestamp to String", e);
        }
        return null;
    }
}
