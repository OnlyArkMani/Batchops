package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "uploads")
public class Upload {
    @Id
    @Column(name = "upload_id")
    public UUID uploadId = UUID.randomUUID();
    public String department;
    public String filename;
    @Column(name = "mime_type")
    public String mimeType = "";
    public String status = "pending";
    @Column(name = "received_at")
    public Instant receivedAt = Instant.now();
    public String notes;
    public String sha256;
    @Column(name = "size_bytes")
    public long sizeBytes;
    @Column(name = "source_key")
    public String sourceKey = "";
    @Column(name = "current_key")
    public String currentKey = "";
    public int version = 1;
    @Column(name = "file_path")
    public String filePath = "";
    @Column(name = "report_path")
    public String reportPath = "";
    @Column(name = "report_csv_key")
    public String reportCsvKey = "";
    @Column(name = "report_pdf_key")
    public String reportPdfKey = "";
    @Column(name = "report_generated_at")
    public Instant reportGeneratedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_meta", columnDefinition = "jsonb")
    public Map<String, Object> reportMeta = new LinkedHashMap<>();
    @Column(name = "process_mode")
    public String processMode = "transform_gradebook";
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "process_config", columnDefinition = "jsonb")
    public Map<String, Object> processConfig = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_report", columnDefinition = "jsonb")
    public Map<String, Object> validationReport = new LinkedHashMap<>();
    @Column(name = "created_by")
    public String createdBy;
    @Version
    @Column(name = "row_version")
    public long rowVersion;
}
