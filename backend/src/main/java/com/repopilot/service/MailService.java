package com.repopilot.service;

import com.repopilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final String from;

    public MailService(AppProperties props) {
        AppProperties.Mail mail = props.mail();
        this.from = mail.from();
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(mail.host());
        impl.setPort(mail.port());
        impl.setUsername(mail.username());
        impl.setPassword(mail.password());
        Properties javaMailProps = new Properties();
        javaMailProps.put("mail.smtp.auth", "true");
        javaMailProps.put("mail.smtp.starttls.enable", "true");
        impl.setJavaMailProperties(javaMailProps);
        this.sender = impl;
    }

    public boolean isConfigured() {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;
        String user = impl.getUsername();
        String pass = impl.getPassword();
        return user != null && !user.isBlank() && pass != null && !pass.isBlank();
    }

    public void send(String to, String subject, String body) {
        if (!isConfigured()) {
            log.warn("SMTP 未配置，跳过邮件发送: {} - {}", to, subject);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
            log.info("邮件已发送: {} -> {} ({})", from, to, subject);
        } catch (MailException e) {
            log.error("邮件发送失败: {} -> {} ({}): {}", from, to, subject, e.getMessage());
            throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
        }
    }
}
