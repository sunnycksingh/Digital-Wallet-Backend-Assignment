package com.digitalwallet.PaymentsWalletLedger.job;

import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.RunView;
import com.digitalwallet.PaymentsWalletLedger.exception.ApiException;
import com.digitalwallet.PaymentsWalletLedger.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    /** Every night at 02:00 India time. */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
    public void nightly() {
        try {
            RunView run = service.run();
            log.info("Nightly reconciliation finished: run {} found {} problem(s)",
                    run.runId(), run.problemCount());
        } catch (ApiException e) {
            log.info("Nightly reconciliation skipped: {}", e.getMessage());   // another run was active
        } catch (Exception e) {
            log.error("Nightly reconciliation failed", e);
        }
    }
}
