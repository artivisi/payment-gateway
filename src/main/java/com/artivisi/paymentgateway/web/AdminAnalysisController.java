package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.AnalysisReportRequest;
import com.artivisi.paymentgateway.service.AnalysisReportService;
import com.artivisi.paymentgateway.web.viewmodel.AnalysisChartView;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/** Renders analysis runs uploaded through the API. Read-only: the gateway never computes these. */
@Controller
@RequestMapping("/admin/analysis")
public class AdminAnalysisController {

    private final AnalysisReportService service;

    public AdminAnalysisController(AnalysisReportService service) {
        this.service = service;
    }

    @GetMapping
    public String latest(Model model) {
        var history = service.history();
        model.addAttribute("history", history);
        if (history.isEmpty()) {
            return "admin/analysis/empty";
        }
        return show(history.getFirst().getId(), model);
    }

    @GetMapping("/{id}")
    public String show(@PathVariable String id, Model model) {
        AnalysisReportRequest report = service.parse(id);
        model.addAttribute("report", report);
        model.addAttribute("selectedId", id);
        model.addAttribute("history", service.history());
        model.addAttribute("cohortBars", AnalysisChartView.cohortBars(report.cohorts()));
        model.addAttribute("curves", AnalysisChartView.curves(
                report.survival() == null ? java.util.List.of() : report.survival()));
        model.addAttribute("svgWidth", AnalysisChartView.svgWidth());
        model.addAttribute("svgHeight", AnalysisChartView.svgHeight());
        return "admin/analysis/show";
    }
}
