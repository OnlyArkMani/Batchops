package com.batchops.mail;

import com.batchops.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Fire-and-forget mail. Delivery problems are logged and never fail the caller. */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final AppProperties props;

    public MailService(ObjectProvider<JavaMailSender> sender, AppProperties props) {
        this.sender = sender;
        this.props = props;
    }

    @Async
    public void send(String to, String subject, String body) {
        if (!props.mail().enabled() || to == null || to.isBlank()) {
            return;
        }
        JavaMailSender s = sender.getIfAvailable();
        if (s == null) {
            log.info("Mail disabled (no sender configured); would send '{}' to {}", subject, to);
            return;
        }
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(props.mail().from());
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            s.send(m);
        } catch (Exception e) {
            log.warn("Could not send mail '{}' to {}: {}", subject, to, e.toString());
        }
    }
}
