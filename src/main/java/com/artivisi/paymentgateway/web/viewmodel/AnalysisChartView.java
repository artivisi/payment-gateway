package com.artivisi.paymentgateway.web.viewmodel;

import com.artivisi.paymentgateway.dto.AnalysisReportRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Chart geometry computed server-side, exactly as the dashboard's trend bars are.
 *
 * <p>No charting library: the pages are under a strict CSP and the project keeps npm HTTP/JS
 * dependencies out. Bars are divs with a pixel height; the survival curve is an inline SVG polyline
 * whose points are computed here, so the template stays declarative.
 */
public final class AnalysisChartView {

    private static final int BAR_MAX_PX = 160;
    private static final int SVG_W = 720;
    private static final int SVG_H = 220;
    private static final int PAD_X = 44;
    private static final int PAD_Y = 18;

    private AnalysisChartView() {
    }

    /** One cohort's "ever paid" rate, as a bar. */
    public record CohortBar(String label, int bills, int paid, String paidPercent, int heightPx,
                            boolean lowPayer, Integer medianDaysToPay) {
    }

    public static List<CohortBar> cohortBars(List<AnalysisReportRequest.Cohort> cohorts) {
        List<CohortBar> bars = new ArrayList<>();
        for (AnalysisReportRequest.Cohort c : cohorts) {
            double pct = c.paidPercent();
            bars.add(new CohortBar(c.label(), c.bills(), c.paid(),
                    String.format("%.1f%%", pct),
                    (int) Math.round(BAR_MAX_PX * pct / 100.0),
                    pct < 25,                       // the abandonment signature, flagged in the UI
                    c.medianDaysToPay()));
        }
        return bars;
    }

    /** A survival curve: x = day checkpoint (evenly spaced), y = % of still-unpaid bills ever paid. */
    public record Curve(String label, String polyline, List<Dot> dots, String colourClass) {
    }

    public record Dot(int x, int y, int day, String percent, int laterPaid, int stillUnpaid) {
    }

    public static List<Curve> curves(List<AnalysisReportRequest.SurvivalSeries> series) {
        List<Curve> out = new ArrayList<>();
        String[] palette = {"curve-primary", "curve-secondary", "curve-tertiary"};
        int idx = 0;
        for (AnalysisReportRequest.SurvivalSeries s : series) {
            List<Dot> dots = new ArrayList<>();
            StringBuilder points = new StringBuilder();
            int n = s.points().size();
            for (int i = 0; i < n; i++) {
                AnalysisReportRequest.SurvivalSeries.Point p = s.points().get(i);
                int x = PAD_X + (n == 1 ? 0 : (SVG_W - 2 * PAD_X) * i / (n - 1));
                // Percentages are the comparable quantity across cohorts of wildly different size.
                int y = SVG_H - PAD_Y - (int) Math.round((SVG_H - 2 * PAD_Y) * p.laterPaidPercent() / 100.0);
                points.append(x).append(',').append(y).append(' ');
                dots.add(new Dot(x, y, p.day(), String.format("%.1f%%", p.laterPaidPercent()),
                        p.laterPaid(), p.stillUnpaid()));
            }
            out.add(new Curve(s.label(), points.toString().trim(), dots, palette[idx++ % palette.length]));
        }
        return out;
    }

    public static int svgWidth() {
        return SVG_W;
    }

    public static int svgHeight() {
        return SVG_H;
    }
}
