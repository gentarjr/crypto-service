package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;

public interface SignalService {

    /**
     * Evaluate indicator snapshot dan generate signal
     *
     * @param snapshot semua nilai indikator yang sudah dihitung
     * @return Signal (BUY/SELL/HOLD + details)
     */
    Signal evaluate(GetIndicatorResponse snapshot);

    /**
     * Nama strategy untuk logging
     */
    String getStrategyName();
}
