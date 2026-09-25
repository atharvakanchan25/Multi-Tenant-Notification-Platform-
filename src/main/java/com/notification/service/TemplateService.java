package com.notification.service;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.notification.domain.Channel;
import com.notification.domain.NotificationTemplate;
import com.notification.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class TemplateService {

    private final NotificationTemplateRepository templateRepo;
    private final Handlebars handlebars = new Handlebars();

    public TemplateService(NotificationTemplateRepository templateRepo) {
        this.templateRepo = templateRepo;
    }

    public record RenderedMessage(String subject, String body) {}

    public RenderedMessage render(String tenantId, String templateId,
                                  Channel channel, Map<String, String> data) {
        NotificationTemplate tmpl = templateRepo
                .findByTenantIdAndTemplateIdAndChannel(tenantId, templateId, channel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Template not found: " + templateId + " / " + channel));

        try {
            Map<String, String> ctx = data != null ? data : Map.of();
            Template bodyTmpl = handlebars.compileInline(tmpl.getBody());
            Template subjectTmpl = handlebars.compileInline(tmpl.getSubject());
            return new RenderedMessage(subjectTmpl.apply(ctx), bodyTmpl.apply(ctx));
        } catch (IOException e) {
            throw new IllegalStateException("Template rendering failed for " + templateId, e);
        }
    }
}
