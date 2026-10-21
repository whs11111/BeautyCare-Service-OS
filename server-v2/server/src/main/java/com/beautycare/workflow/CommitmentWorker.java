package com.beautycare.workflow;

import com.beautycare.api.ReceptionController;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Separate bounded worker: model latency never holds message-send locks or delays emotion analysis. */
@Component
public class CommitmentWorker {
    private final ReceptionController reception;
    private final CommitmentAnalyzer analyzer;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.AbortPolicy());
    public CommitmentWorker(ReceptionController reception, CommitmentAnalyzer analyzer) {
        this.reception = reception; this.analyzer = analyzer;
    }
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        for (Map<String, Object> job : reception.commitmentJobs()) {
            String key = job.get("customerId") + ":" + job.get("messageId");
            if (!running.add(key)) continue;
            try { executor.submit(() -> { try { process(job); } finally { running.remove(key); } }); }
            catch (RejectedExecutionException ignored) { running.remove(key); }
        }
    }
    @SuppressWarnings("unchecked")
    private void process(Map<String, Object> job) {
        CommitmentAnalyzer.Result result = null;
        String status = "MODEL";
        try { result = analyzer.analyze((Map<String, Object>)job.get("source"), (List<Map<String, Object>>)job.get("context")); }
        catch (Exception e) { status = "RULE_FALLBACK"; org.slf4j.LoggerFactory.getLogger(getClass()).warn("Commitment model unavailable or invalid for message {}", job.get("messageId")); }
        try { reception.finishCommitment(job.get("customerId").toString(), job.get("messageId").toString(), result, status); }
        catch (java.io.IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot persist commitment extraction", e); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
