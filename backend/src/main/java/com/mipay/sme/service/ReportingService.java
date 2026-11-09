package com.mipay.sme.service;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.admin.MonitoringMetric;
import com.mipay.sme.domain.ops.FeeReport;
import com.mipay.sme.domain.ops.Settlement;
import com.mipay.sme.repository.FeeReportRepository;
import com.mipay.sme.repository.FeeTransactionRepository;
import com.mipay.sme.repository.IncidentRepository;
import com.mipay.sme.repository.InvoicePaymentRepository;
import com.mipay.sme.repository.MonitoringMetricRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.SettlementRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Settlements, fee reports and platform health (T12/T13, X13, UAT SB p.17). */
@Service
@RequiredArgsConstructor
public class ReportingService {

    private final SettlementRepository settlementRepo;
    private final FeeReportRepository feeReportRepo;
    private final FeeTransactionRepository feeTxRepo;
    private final PaymentRepository paymentRepo;
    private final InvoicePaymentRepository invoicePaymentRepo;
    private final IncidentRepository incidentRepo;
    private final MonitoringMetricRepository metricRepo;
    private final AppProperties props;
    private final AuditService auditService;

    /** Platform settlement for a date+cycle: receipts in, supplier payments out (BC p.27). */
    @Transactional
    public Dtos.SettlementRow settlement(LocalDate date, String cycle) {
        Instant from = utcStart(date);
        Instant to = utcStart(date.plusDays(1));
        BigDecimal receipts = paymentRepo.confirmedSumPlatform(Enums.PaymentStatus.CONFIRMED, from, to);
        BigDecimal out = invoicePaymentRepo.confirmedSumPlatform(Enums.InvoicePaymentStatus.CONFIRMED, from, to);
        BigDecimal total = receipts.subtract(out).setScale(2, RoundingMode.HALF_UP);

        Settlement s = new Settlement();
        s.setSettlementDate(date);
        s.setCycle(cycle == null ? "CYCLE_1" : cycle);
        s.setTotalAmount(total);
        s.setStatus(Enums.SettlementStatus.SETTLED);
        settlementRepo.save(s);

        auditService.record("system", "TPTS_OPS", "settlement_generated", "settlement", s.getId(),
                null, null, "date=" + date + " cycle=" + s.getCycle(), false, null);
        return new Dtos.SettlementRow(s.getId(), date, s.getCycle(), s.getTotalAmount(), s.getStatus().name());
    }

    /** Fees billed vs collected per code for a date (T13, TC-OPS-14). */
    @Transactional
    public List<Dtos.FeeReportRow> feeReports(LocalDate date) {
        Instant from = utcStart(date);
        Instant to = utcStart(date.plusDays(1));
        List<Object[]> rows = feeTxRepo.totalsPerCode(Enums.FeeStatus.APPLIED, from, to);
        List<Dtos.FeeReportRow> out = new ArrayList<>();
        BigDecimal collected = BigDecimal.ZERO;
        for (Object[] row : rows) {
            String code = (String) row[0];
            BigDecimal billed = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            FeeReport fr = new FeeReport();
            fr.setReportDate(date);
            fr.setFeeCode(code);
            fr.setAmountBilled(billed);
            fr.setAmountCollected(billed); // same-day collection in the simulation
            feeReportRepo.save(fr);
            out.add(new Dtos.FeeReportRow(fr.getId(), date, code, billed, billed));
            collected = collected.add(billed);
        }
        recordMetric("fee_income_daily", collected);
        return out;
    }

    public void recordMetric(String name, BigDecimal value) {
        MonitoringMetric m = new MonitoringMetric();
        m.setMetricName(name);
        m.setValue(value == null ? BigDecimal.ZERO : value);
        metricRepo.save(m);
        auditService.sim("system", "MINDLABS_ADMIN", "metric_recorded", "monitoring_metric", m.getMetricName(),
                "value=" + m.getValue(), null);
    }

    @Transactional(readOnly = true)
    public Dtos.HealthView health() {
        BigDecimal availability = latest("availability_pct").orElse(new BigDecimal("99.95"));
        BigDecimal latency = latest("latency_ms_p95").orElse(new BigDecimal("180"));
        BigDecimal tps = latest("tps").orElse(BigDecimal.ZERO);
        int openCritical = (int) incidentRepo.findByStatus(Enums.IncidentStatus.OPEN).stream()
                .filter(i -> i.getSeverity() == Enums.IncidentSeverity.CRITICAL)
                .count();
        return new Dtos.HealthView(props.getEnvironment(), availability, latency, tps, openCritical,
                props.isSimulatedEnvironment() ? "SIMULATED metrics" : null);
    }

    private java.util.Optional<BigDecimal> latest(String name) {
        return metricRepo.findTopByMetricNameOrderByRecordedAtDesc(name).map(MonitoringMetric::getValue);
    }

    private static Instant utcStart(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
