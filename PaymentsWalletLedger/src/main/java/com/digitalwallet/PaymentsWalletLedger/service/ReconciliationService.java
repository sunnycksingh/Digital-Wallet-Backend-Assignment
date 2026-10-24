package com.digitalwallet.PaymentsWalletLedger.service;

import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.RunReport;
import com.digitalwallet.PaymentsWalletLedger.dto.ReconciliationDtos.RunView;
import com.digitalwallet.PaymentsWalletLedger.exception.ApiException;
import com.digitalwallet.PaymentsWalletLedger.repository.ReconciliationRepository;
import com.digitalwallet.PaymentsWalletLedger.repository.ReconciliationRepository.Issue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReconciliationService {

    private final DataSource dataSource;
    private final ReconciliationRepository repo;

    public ReconciliationService(DataSource dataSource, ReconciliationRepository repo) {
        this.dataSource = dataSource;
        this.repo = repo;
    }

    /**
     * One full run. It only REPORTS problems and never fixes them.
     * Everything happens in ONE REPEATABLE READ transaction: all 5 checks see the same snapshot,
     * even if transfers keep happening while the job runs.
     */
    public RunView run() {
        repo.ensureLockRow();

        long runId;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);                                              // BEGIN
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                // Only one run at a time. If another run holds the row, stop right away.
                if (!repo.tryLock(c)) {
                    c.rollback();
                    throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_RUNNING",
                            "A reconciliation run is already in progress");
                }

                runId = repo.insertRun(c);

                List<Issue> issues = new ArrayList<>();
                issues.addAll(repo.checkGlobalBalance(c));            // check 1
                issues.addAll(repo.checkTransferBalance(c));          // check 2
                issues.addAll(repo.checkStoredBalance(c));            // check 3
                issues.addAll(repo.checkOverdrafts(c));               // check 4
                issues.addAll(repo.checkTransfersWithoutEntries(c));  // check 5a
                issues.addAll(repo.checkEntriesWithoutTransfer(c));   // check 5b

                for (Issue i : issues) repo.insertIssue(c, runId, i);
                repo.completeRun(c, runId, issues.size());
                c.commit();                                                      // COMMIT
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                c.rollback();
                repo.recordFailedRun();
                throw new RuntimeException("Reconciliation failed", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error", e);
        }
        return repo.findRun(runId);
    }

    public RunReport getReport(long runId) {
        RunView run = repo.findRun(runId);
        if (run == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Reconciliation run not found");
        }
        return new RunReport(run, repo.findIssues(runId));
    }
}
