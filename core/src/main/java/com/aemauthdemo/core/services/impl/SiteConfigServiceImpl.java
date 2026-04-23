package com.aemauthdemo.core.services.impl;

import com.aemauthdemo.core.config.SiteConfig;
import com.aemauthdemo.core.services.SiteConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

@Component(service = SiteConfigService.class, immediate = true)
@Designate(ocd = SiteConfig.class)
public class SiteConfigServiceImpl implements SiteConfigService {

    private String publishHost;

    @Activate
    @Modified
    protected void activate(SiteConfig config) {
        String host = config.publishHost().trim();
        this.publishHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    @Override
    public String getPublishHost() {
        return publishHost;
    }
}
