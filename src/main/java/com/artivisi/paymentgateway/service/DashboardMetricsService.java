package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.web.viewmodel.AttentionItemView;
import com.artivisi.paymentgateway.web.viewmodel.KpiView;
import com.artivisi.paymentgateway.web.viewmodel.RecentPaymentView;
import com.artivisi.paymentgateway.web.viewmodel.TrendBarView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.artivisi.paymentgateway.web.viewmodel.ViewFormats.DISPLAY_ZONE;
import static com.artivisi.paymentgateway.web.viewmodel.ViewFormats.relativeDay;
import static com.artivisi.paymentgateway.web.viewmodel.ViewFormats.rupiah;
import static com.artivisi.paymentgateway.web.viewmodel.ViewFormats.shortId;
import static com.artivisi.paymentgateway.web.viewmodel.ViewFormats.time;

/**
 * Read-only aggregates for the admin dashboard: KPIs, the collections trend, and
 * "needs attention" signals. Nothing here mutates state or is used outside the dashboard view.
 */
@Service
public class DashboardMetricsService {

    private static final List<ChargeStatus> OUTSTANDING_STATUSES = List.of(ChargeStatus.ACTIVE, ChargeStatus.PARTIALLY_PAID);
    private static final int TREND_DAYS = 14;
    private static final DateTimeFormatter DAY_NUMBER = DateTimeFormatter.ofPattern("d");
    private static final DateTimeFormatter DAY_TOOLTIP = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("id-ID"));
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);

    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    public DashboardMetricsService(ChargeRepository chargeRepository, PaymentRepository paymentRepository,
                                   ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    public List<KpiView> kpis(Instant now) {
        ZonedDateTime nowJkt = now.atZone(DISPLAY_ZONE);
        Instant startOfToday = nowJkt.toLocalDate().atStartOfDay(DISPLAY_ZONE).toInstant();
        Instant startOfMonth = nowJkt.toLocalDate().withDayOfMonth(1).atStartOfDay(DISPLAY_ZONE).toInstant();

        BigDecimal outstanding = chargeRepository.sumOutstanding(OUTSTANDING_STATUSES);
        long activeCount = chargeRepository.countByStatus(ChargeStatus.ACTIVE)
                + chargeRepository.countByStatus(ChargeStatus.PARTIALLY_PAID);

        BigDecimal collectedToday = paymentRepository.sumAmountInPeriod(PaymentStatus.ACCEPTED, startOfToday, now);
        long paymentsToday = paymentRepository.countByStatusAndTransactionTimeBetween(PaymentStatus.ACCEPTED, startOfToday, now);

        BigDecimal collectedMonth = paymentRepository.sumAmountInPeriod(PaymentStatus.ACCEPTED, startOfMonth, now);
        long paymentsMonth = paymentRepository.countByStatusAndTransactionTimeBetween(PaymentStatus.ACCEPTED, startOfMonth, now);

        long chargesThisMonth = chargeRepository.countByCreatedAtBetween(startOfMonth, now);
        long paidThisMonth = chargeRepository.countByStatusAndCreatedAtBetween(ChargeStatus.PAID, startOfMonth, now);
        String rate = chargesThisMonth == 0 ? "—" : Math.round(100.0 * paidThisMonth / chargesThisMonth) + "%";

        return List.of(
                new KpiView("Outstanding", rupiah(outstanding), plural(activeCount, "active charge"), "bg-amber-400"),
                new KpiView("Collected today", rupiah(collectedToday), plural(paymentsToday, "payment") + " applied", "bg-accent-500"),
                new KpiView("Collected · " + nowJkt.format(MONTH_LABEL), rupiah(collectedMonth),
                        plural(paymentsMonth, "payment") + " this month", "bg-primary-500"),
                new KpiView("Collection rate", rate, "of this month's charges settled", "bg-primary-500")
        );
    }

    public List<TrendBarView> collectionsTrend(Instant now) {
        LocalDate today = now.atZone(DISPLAY_ZONE).toLocalDate();
        LocalDate start = today.minusDays(TREND_DAYS - 1L);
        Instant queryStart = start.atStartOfDay(DISPLAY_ZONE).toInstant();

        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        for (Payment p : paymentRepository.findByStatusAndTransactionTimeGreaterThanEqualOrderByTransactionTime(
                PaymentStatus.ACCEPTED, queryStart)) {
            LocalDate day = p.getTransactionTime().atZone(DISPLAY_ZONE).toLocalDate();
            byDay.merge(day, p.getAmount(), BigDecimal::add);
        }
        BigDecimal max = byDay.values().stream().max(BigDecimal::compareTo).filter(v -> v.signum() > 0).orElse(BigDecimal.ONE);

        List<TrendBarView> bars = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = start.plusDays(i);
            BigDecimal total = byDay.getOrDefault(day, BigDecimal.ZERO);
            double ratio = total.divide(max, 8, RoundingMode.HALF_UP).doubleValue();
            int heightPx = 8 + (int) Math.round(ratio * 88);
            bars.add(new TrendBarView(day.format(DAY_NUMBER), heightPx, day.equals(today),
                    day.format(DAY_TOOLTIP) + " · " + rupiah(total)));
        }
        return bars;
    }

    public List<AttentionItemView> needsAttention(Instant now) {
        List<AttentionItemView> items = new ArrayList<>();

        long expiringSoon = chargeRepository.countExpiringBetween(OUTSTANDING_STATUSES, now, now.plus(48, ChronoUnit.HOURS));
        if (expiringSoon > 0) {
            items.add(new AttentionItemView("bg-amber-400",
                    plural(expiringSoon, "charge") + " expire within 48 hours without payment",
                    "/admin/charges?status=ACTIVE"));
        }

        long recentDiscrepancies = discrepancyRepository.countByReconciliationRunStartedAtGreaterThanEqual(
                now.minus(7, ChronoUnit.DAYS));
        if (recentDiscrepancies > 0) {
            items.add(new AttentionItemView("bg-rose-500",
                    recentDiscrepancies + " reconciliation discrepanc" + (recentDiscrepancies == 1 ? "y" : "ies")
                            + " flagged in the last 7 days",
                    "/admin/reconciliations"));
        }

        Instant startOfToday = now.atZone(DISPLAY_ZONE).toLocalDate().atStartOfDay(DISPLAY_ZONE).toInstant();
        long cancelledToday = chargeRepository.countByStatusAndUpdatedAtBetween(ChargeStatus.CANCELLED, startOfToday, now);
        if (cancelledToday > 0) {
            items.add(new AttentionItemView("bg-slate-400",
                    plural(cancelledToday, "charge") + " cancelled today — see audit trail",
                    "/admin/audit?category=CHARGE"));
        }
        return items;
    }

    public List<RecentPaymentView> recentPayments(int limit, Instant now) {
        return paymentRepository.findRecentWithVaAndCharge(PageRequest.of(0, limit)).stream()
                .map(p -> new RecentPaymentView(
                        relativeDay(p.getTransactionTime(), now) + ", " + time(p.getTransactionTime()),
                        p.getVirtualAccount().getVaNumber(),
                        rupiah(p.getAmount()),
                        shortId(p.getCharge().getId()),
                        "/admin/charges/" + p.getCharge().getId()))
                .toList();
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
