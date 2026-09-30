package com.batchops.pipeline;

import com.batchops.config.AppProperties;
import com.batchops.domain.ColumnNames;
import com.batchops.domain.NumericCoercion;
import com.batchops.domain.ProcessingPlan;
import com.batchops.domain.SchemaValidator;
import com.batchops.domain.Stage;
import com.batchops.domain.SummaryBuilder;
import com.batchops.domain.Table;
import com.batchops.io.PdfReportWriter;
import com.batchops.io.TableIO;
import com.batchops.io.TableLoader;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.Upload;
import com.batchops.storage.ObjectStore;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The business logic of each pipeline stage. Each stage reads the previous stage's immutable
 * artifact from the object store and writes its own, keyed by upload version, so re-running a stage
 * is always safe (same inputs, same output key).
 */
@Component
public class StageProcessor {

    private final ObjectStore store;
    private final AppProperties props;
    private final JobRunService runs;

    public StageProcessor(ObjectStore store, AppProperties props, JobRunService runs) {
        this.store = store;
        this.props = props;
        this.runs = runs;
    }

    public void execute(Stage stage, Upload u, JobRun run) throws IOException {
        switch (stage) {
            case STANDARDIZER -> standardize(u, run);
            case VALIDATOR -> validate(u, run);
            case TRANSFORMER -> transform(u, run);
            case PUBLISHER -> publish(u, run);
        }
    }

    // ------------------------------------------------------------------ standardize

    private void standardize(Upload u, JobRun run) throws IOException {
        String key = u.currentKey == null || u.currentKey.isBlank() ? ArtifactKeys.source(u) : u.currentKey;
        byte[] data = store.get(key);
        Table t = TableLoader.load(key, data, new TableLoader.Options(charsetFor(u)));
        ColumnNames.normalizeAll(t);
        ColumnNames.AliasResult aliases = ColumnNames.applyAliases(t);

        Map<String, Object> meta = new LinkedHashMap<>(u.reportMeta == null ? Map.of() : u.reportMeta);
        meta.remove("column_aliases");
        if (aliases.changed()) {
            meta.put("column_aliases", aliases.matched());
        }
        u.reportMeta = meta;

        store.put(ArtifactKeys.standardized(u), TableIO.toCsvBytes(t), "text/csv");
        runs.step(run, "standardize_results", t.rowCount() + " row(s), " + t.colCount() + " column(s)"
                + (aliases.changed() ? ", aliased " + aliases.matched() : ""));
    }

    // ------------------------------------------------------------------ validate

    private void validate(Upload u, JobRun run) throws IOException {
        Table t = readArtifact(ArtifactKeys.standardized(u));
        NumericCoercion.coerce(t);
        SchemaValidator.Report report = SchemaValidator.validate(t, settingsFor(u));

        Map<String, Object> vr = new LinkedHashMap<>();
        vr.put("ok", report.ok());
        vr.put("errors", report.errors());
        vr.put("warnings", report.warnings());
        List<Map<String, Object>> issues = new ArrayList<>();
        for (SchemaValidator.RowIssue i : report.rowIssues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", i.row());
            m.put("column", i.column());
            m.put("rule", i.rule());
            m.put("value", i.value());
            issues.add(m);
        }
        vr.put("row_issues", issues);
        vr.put("total_row_issues", report.totalRowIssues());
        vr.put("checked_at", Instant.now().toString());

        if (!report.ok()) {
            throw new ValidationFailedException(report.errorMessage(), vr);
        }
        u.validationReport = vr;
        store.put(ArtifactKeys.validated(u), TableIO.toCsvBytes(t), "text/csv");
        runs.step(run, "validate_results", report.warnings().isEmpty() ? "ok" : "ok with " + report.warnings().size()
                + " warning(s)");
    }

    @SuppressWarnings("unchecked")
    SchemaValidator.Settings settingsFor(Upload u) {
        Map<String, Object> cfg = u.processConfig == null ? Map.of() : u.processConfig;
        SchemaValidator.Settings base = SchemaValidator.Settings.defaults();
        List<String> required = base.requiredColumns();
        if (cfg.get("required_columns") instanceof List<?> l && !l.isEmpty()) {
            required = new ArrayList<>();
            for (Object o : l) {
                required.add(ColumnNames.normalize(String.valueOf(o)));
            }
        }
        Map<String, Object> v = cfg.get("validation") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        boolean range = !Boolean.FALSE.equals(v.get("enforce_score_range"));
        boolean unique = !Boolean.FALSE.equals(v.get("enforce_uniqueness"));
        double min = v.get("score_min") instanceof Number n ? n.doubleValue() : props.pipeline().scoreMin();
        double max = v.get("score_max") instanceof Number n ? n.doubleValue() : props.pipeline().scoreMax();
        return new SchemaValidator.Settings(required, true, range, min, max, unique);
    }

    // ------------------------------------------------------------------ transform

    private void transform(Upload u, JobRun run) throws IOException {
        Table t = readArtifact(ArtifactKeys.validated(u));
        NumericCoercion.coerce(t);
        String note = "Standard transform applied";
        Map<String, Object> meta = new LinkedHashMap<>(u.reportMeta == null ? Map.of() : u.reportMeta);
        meta.remove("processing_plan");
        if (!ProcessingPlan.isSummaryMode(u.processMode)) {
            ProcessingPlan.Result res = ProcessingPlan.apply(t, u.processMode, u.processConfig);
            t = res.table();
            note = res.summary();
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("mode", res.mode());
            plan.put("description", res.summary());
            plan.put("config", u.processConfig == null ? Map.of() : u.processConfig);
            meta.put("processing_plan", plan);
        }
        meta.put("plan_notes", note);
        u.reportMeta = meta;
        store.put(ArtifactKeys.transformed(u), TableIO.toCsvBytes(t), "text/csv");
        runs.step(run, "transform_gradebook", note);
    }

    // ------------------------------------------------------------------ publish

    private void publish(Upload u, JobRun run) throws IOException {
        Table t = readArtifact(ArtifactKeys.transformed(u));
        NumericCoercion.coerce(t);
        Map<String, Object> meta = new LinkedHashMap<>(u.reportMeta == null ? Map.of() : u.reportMeta);
        String mode = ProcessingPlan.normalizeMode(u.processMode);
        String planNotes = String.valueOf(meta.getOrDefault("plan_notes", ""));

        List<String> columns;
        List<List<String>> rows;
        if (ProcessingPlan.isSummaryMode(u.processMode)) {
            SummaryBuilder.Summary s = SummaryBuilder.build(t, u.department, u.filename, mode, planNotes,
                    ZonedDateTime.now(ZoneOffset.UTC));
            columns = List.of("field", "value");
            rows = s.rows();
            meta.put("rows", s.rowCount());
            meta.put("cols", s.colCount());
            meta.put("columns", t.headers());
            meta.put("numeric_cols", s.numericColumns());
            meta.put("describe", cleanDescribe(s.describe()));
            meta.put("summary_rows", s.rows());
        } else {
            columns = new ArrayList<>(t.headers());
            rows = t.rows();
            meta.put("rows", t.rowCount());
            meta.put("cols", t.colCount());
            meta.put("columns", t.headers());
        }
        runs.step(run, "generate_summary", rows.size() + " report row(s)");

        store.put(ArtifactKeys.reportCsv(u), TableIO.toCsvBytes(columns, rows), "text/csv");
        store.put(ArtifactKeys.reportPdf(u), PdfReportWriter.render("Upload " + u.uploadId, columns, rows),
                "application/pdf");
        u.reportCsvKey = ArtifactKeys.reportCsv(u);
        u.reportPdfKey = ArtifactKeys.reportPdf(u);
        u.reportPath = u.reportCsvKey;
        u.reportGeneratedAt = Instant.now();
        u.reportMeta = meta;
        runs.step(run, "publish_results", "report stored at " + u.reportCsvKey);
    }

    private static Map<String, Map<String, Object>> cleanDescribe(Map<String, Map<String, Double>> in) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : in.entrySet()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            for (Map.Entry<String, Double> s : e.getValue().entrySet()) {
                Double v = s.getValue();
                stats.put(s.getKey(), v == null || v.isNaN() || v.isInfinite() ? null : v);
            }
            out.put(e.getKey(), stats);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Table readArtifact(String key) throws IOException {
        return TableLoader.load(key, store.get(key));
    }

    private static Charset charsetFor(Upload u) {
        Object enc = u.processConfig == null ? null : u.processConfig.get("_encoding");
        if (enc != null) {
            try {
                return Charset.forName(String.valueOf(enc));
            } catch (RuntimeException ignored) {
                // fall through to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }
}
