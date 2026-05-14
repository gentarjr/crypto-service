package com.bot.testnet.crypto.utils;

public class Constants {

    private Constants(){

    }

    public static final String DATEFORMAT_YYYYMMDDT_HHMMSSSSSZ = "yyyy-MM-dd'T'HH:mm:ss.sssZ";

    public static final String SUBMITTED_STATUS = "SUBMITTED";
    public static final String FILLED_STATUS = "FILLED";
    public static final String FAILED_STATUS = "FAILED";
    public static final String ERROR_STATUS = "ERROR";

    public static final String TESTNET_QUIRK_NOTE = "Order succeeded despite exception (Binance Testnet quirk)";
    public static final String NO_ORDER_ID_TESTNET = "N/A (testnet quirk)";
}
