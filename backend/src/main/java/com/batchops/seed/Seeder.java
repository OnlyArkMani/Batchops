package com.batchops.seed;

import com.batchops.config.AppProperties;
import com.batchops.domain.KnownErrorCatalog;
import com.batchops.persistence.Job;
import com.batchops.persistence.JobRepository;
import com.batchops.persistence.KnownError;
import com.batchops.persistence.KnownErrorRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.persistence.UserRepository;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Idempotent startup seeding: known-error catalogue, the pipeline job row, and the first admin. */
@Component
public class Seeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private final KnownErrorRepository known;
    private final JobRepository jobs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final TransactionTemplate tx;

    public Seeder(KnownErrorRepository known, JobRepository jobs, UserRepository users, PasswordEncoder encoder,
                  AppProperties props, TransactionTemplate tx) {
        this.known = known;
        this.jobs = jobs;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            seedKnownErrors();
            seedPipelineJob();
            seedAdmin();
        });
    }

    private void seedKnownErrors() {
        for (KnownErrorCatalog.Definition d : KnownErrorCatalog.defaults()) {
            if (!known.existsByName(d.name())) {
                KnownError k = new KnownError();
                k.name = d.name();
                k.pattern = d.pattern();
                k.fix = d.fix();
                known.save(k);
            }
        }
    }

    private void seedPipelineJob() {
        if (jobs.findByName("results_pipeline").isEmpty()) {
            Job j = new Job();
            j.name = "results_pipeline";
            j.jobType = "python";
            jobs.save(j);
        }
    }

    private void seedAdmin() {
        if (users.count() > 0) {
            return;
        }
        String password = props.security().bootstrapAdminPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            byte[] raw = new byte[9];
            new SecureRandom().nextBytes(raw);
            password = HexFormat.of().formatHex(raw);
        }
        UserAccount admin = new UserAccount();
        admin.username = props.security().bootstrapAdminUsername();
        admin.passwordHash = encoder.encode(password);
        admin.role = "admin";
        admin.superuser = true;
        admin.emailVerified = true;
        users.save(admin);
        if (generated) {
            log.warn("==========================================================================");
            log.warn("Created first admin '{}' with generated password: {}", admin.username, password);
            log.warn("Shown once. Set BATCHOPS_ADMIN_PASSWORD to choose your own before first start.");
            log.warn("==========================================================================");
        } else {
            log.info("Created first admin '{}'", admin.username);
        }
    }
}
